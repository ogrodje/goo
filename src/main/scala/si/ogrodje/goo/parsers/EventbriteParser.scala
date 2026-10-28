package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{Decoder, Json}
import io.circe.parser.parse as parseJson
import si.ogrodje.goo.models.Source.Eventbrite
import si.ogrodje.goo.models.{Event, Meetup}
import zio.http.{Client, URL}
import zio.{Scope, ZIO}

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try

object EventbriteParser:
  sealed abstract class Failure(message: String)    extends RuntimeException(message)
  // The page rendered a bot-challenge/captcha instead of organizer data. Retryable with backoff.
  final case class CaptchaDetected(url: String)
      extends Failure(s"Eventbrite served a bot-challenge (captcha) instead of event data for $url")
  // The __NEXT_DATA__ payload was not valid JSON.
  final case class MalformedPayload(reason: String) extends Failure(s"Malformed Eventbrite payload: $reason")

final case class EventbriteParser(meetup: Meetup) extends Parser:
  import DocumentOps.given

  // Eventbrite's organizer page ("/o/...") no longer exposes JSON-LD `itemListElement`. Events are
  // server-rendered into the Next.js payload at props.pageProps.upcomingEvents. The organizer page
  // is also protected by a bot wall on plain HTTP, so we render it with Chromium (Parser.withPage)
  // rather than the HTTP client.

  // Each upcomingEvents item carries split date/time/zone fields, e.g.
  //   "start_date": "2026-07-17", "start_time": "19:00:00", "timezone": "America/Los_Angeles"
  private def toOffsetDateTime(date: String, time: String, zone: String): OffsetDateTime =
    val localDateTime = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
    val zoneId        = ZoneId.of(zone)
    OffsetDateTime.of(localDateTime, zoneId.getRules.getOffset(localDateTime))

  private def locationFromItem(cursor: io.circe.ACursor): (Option[String], Option[String]) =
    val venue           = cursor.downField("primary_venue")
    val name            = venue.get[String]("name").toOption.filter(_.nonEmpty)
    val address         = venue.downField("address").get[String]("localized_address_display").toOption
    val onlineOrMissing = cursor.get[Boolean]("is_online_event").getOrElse(false)
    if onlineOrMissing then None -> None else name -> address.filter(_.nonEmpty)

  private def decodeEvent(sourceURL: URL, item: Json): Either[String, Event] =
    val cursor = item.hcursor
    for
      id        <- cursor.get[String]("id").left.map(_.getMessage)
      name      <- cursor.get[String]("name").left.map(_.getMessage)
      startDate <- cursor.get[String]("start_date").left.map(_.getMessage)
      startTime <- cursor.get[String]("start_time").left.map(_.getMessage)
      timezone  <- cursor.get[String]("timezone").left.map(_.getMessage)
      eventURL  <- cursor.get[URL]("url").left.map(_.getMessage)
      start     <- Try(toOffsetDateTime(startDate, startTime, timezone)).toEither.left.map(_.getMessage)
    yield
      val end                                    =
        for
          endDate <- cursor.get[String]("end_date").toOption
          endTime <- cursor.get[String]("end_time").toOption
          parsed  <- Try(toOffsetDateTime(endDate, endTime, timezone)).toOption
        yield parsed
      val summary                                = cursor.get[String]("summary").toOption.filter(_.nonEmpty)
      val (maybeLocationName, maybeLocationAddr) = locationFromItem(cursor)
      Event
        .empty(
          id = s"eventbrite-$id",
          meetupID = meetup.id,
          source = Eventbrite,
          sourceURL = sourceURL,
          title = name,
          startDateTime = start
        )
        .copy(
          description = summary,
          eventURL = Some(eventURL),
          endDateTime = end,
          hasStartTime = !cursor.get[Boolean]("hide_start_date").getOrElse(false),
          hasEndTime = !cursor.get[Boolean]("hide_end_date").getOrElse(false),
          locationName = maybeLocationName,
          locationAddress = maybeLocationAddr
        )

  private def eventsFromNextData(sourceURL: URL, nextData: Json): List[Event] =
    val items = nextData.hcursor
      .downField("props")
      .downField("pageProps")
      .downField("upcomingEvents")
      .focus
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
    items.toList.map(decodeEvent(sourceURL, _)).collect { case Right(event) => event }

  /** A genuine organizer page carries `pageProps.organizer` alongside the `upcomingEvents` list. The bot-challenge
    * interstitial renders a `__NEXT_DATA__` with only shell keys (locale, messages, globalHeaderProps, footerProps,
    * serverUrl) and NONE of the organizer/events keys. We use that structural difference to tell "blocked" apart from
    * "organizer genuinely has no events" — the former should back off and retry, the latter is a legitimate empty
    * result.
    */
  private def looksBlocked(nextData: Json): Boolean =
    val pageProps    = nextData.hcursor.downField("props").downField("pageProps")
    val hasOrganizer = pageProps.downField("organizer").focus.exists(!_.isNull)
    val hasUpcoming  = pageProps.downField("upcomingEvents").focus.isDefined
    !(hasOrganizer || hasUpcoming)

  /** Pure seam: decode the raw `__NEXT_DATA__` payload into events. Returns a Left describing why the payload was
    * unusable (invalid JSON, or a bot-challenge page). Exposed for testing so the parsing and captcha-detection logic
    * can be verified without driving a real browser.
    */
  private[parsers] def eventsFromRawNextData(
    sourceURL: URL,
    raw: String
  ): Either[EventbriteParser.Failure, List[Event]] =
    parseJson(raw).left.map(err => EventbriteParser.MalformedPayload(err.getMessage)).flatMap { json =>
      if looksBlocked(json) then Left(EventbriteParser.CaptchaDetected(sourceURL.toString))
      else Right(eventsFromNextData(sourceURL, json))
    }

  override protected def parse(
    client: Client,
    url: URL
  ): ZIO[Scope & Browser, Throwable, List[Event]] =
    withPage { page =>
      ZIO.attemptBlocking {
        // A <script> tag is never "visible", so we must wait for ATTACHED, not the default VISIBLE
        // (which would always time out). Bounded to 10s: Eventbrite intermittently serves a
        // bot-challenge interstitial that never renders __NEXT_DATA__ — fail fast rather than block
        // on Playwright's 30s default for every retry attempt.
        val waitOptions = new com.microsoft.playwright.Page.WaitForSelectorOptions()
          .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
          .setTimeout(10_000)
        page.navigate(url.toString)
        page.waitForSelector("script#__NEXT_DATA__", waitOptions)
        page.locator("script#__NEXT_DATA__").first().innerHTML()
      }
    }
      // A timeout here means the challenge page never rendered __NEXT_DATA__ at all — treat it as a
      // block so the retry/backoff (Parser.parseWithClient) kicks in.
      .mapError {
        case _: com.microsoft.playwright.TimeoutError => EventbriteParser.CaptchaDetected(url.toString)
        case other                                    => other
      }
      .flatMap(raw => ZIO.fromEither(eventsFromRawNextData(url, raw)))
