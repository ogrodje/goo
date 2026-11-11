package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.SLAIF
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.logWarning
import zio.http.{Client, Request, URL}
import zio.{RIO, Scope, ZIO}

import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try
import scala.util.matching.Regex

/** Events of Slovenska tovarna UI (SLAIF, https://www.slaif.si/events).
  *
  * The site is a single-page app that reads its events from a public JSON API, `/api/events`:
  * {{{
  * {"id": 52, "title": "Vzpostavljanje podatkovne kulture … | Building a data culture …", "event_date": "2027-01-20",
  *  "time_range": "13:00–15:00", "location": "VK", "lecturer": "Marko Drobnjak", "tags": ["Workshop", "Online"],
  *  "indico_url": "https://indico.ijs.si/event/3989/", "status": "published"}
  * }}}
  * A title holds both languages, "slovensko | English"; like the site in Slovenian, the parser takes the first half. An
  * empty `time_range` means the time isn't known yet: the event is all-day. The event links to its Indico page (where
  * registration is), else to SLAIF's events page.
  */
final case class SLAIFParser(meetup: Meetup) extends Parser:
  import SLAIFParser.*

  private val zone = ZoneId.of("Europe/Ljubljana")

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private def toEvent(eventsPage: URL)(item: Item): Event =
    val times = timesOf(item.timeRange)
    val start = at(item.date, times.map(_._1).getOrElse(LocalTime.MIDNIGHT))
    val end   = times.flatMap(_._2).map(at(item.date, _)).filter(_.isAfter(start))

    Event
      .empty(
        id = s"slaif-${item.id}",
        meetupID = meetup.id,
        source = SLAIF,
        sourceURL = eventsPage,
        title = slovenian(item.title),
        startDateTime = start
      )
      .copy(
        description = item.lecturer.map(lecturer => s"Izvajalci: $lecturer"),
        eventURL = Some(item.indicoURL.getOrElse(eventsPage)),
        endDateTime = end,
        hasStartTime = times.isDefined,
        hasEndTime = end.isDefined,
        locationName = item.location
      )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    response  <- client.requestMetered(Request.get(url.path("/api/events")))
    body      <- response.body.asString
    json      <- ZIO.fromEither(parseJson(body))
    // Decoded one by one: an item that doesn't fit is logged and skipped, not the whole list.
    raw       <- ZIO.fromEither(json.as[List[io.circe.Json]])
    items     <- ZIO.foreach(raw)(item =>
                   item.as[Item] match
                     case Right(decoded) => ZIO.some(decoded)
                     case Left(err)      =>
                       logWarning(s"Skipping SLAIF event ${item.hcursor.get[Long]("id").getOrElse("?")}: $err").as(None)
                 )
    eventsPage = url.path("/events")
  yield items.flatten.filter(_.status == "published").map(toEvent(eventsPage))

object SLAIFParser:
  final private[parsers] case class Item(
    id: Long,
    title: String,
    date: LocalDate,
    timeRange: Option[String],
    location: Option[String],
    lecturer: Option[String],
    indicoURL: Option[URL],
    status: String
  )

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

  private given Decoder[Item] = Decoder.instance: c =>
    for
      id        <- c.get[Long]("id")
      title     <- c.get[String]("title")
      rawDate   <- c.get[String]("event_date")
      date      <- Try(LocalDate.parse(rawDate)).toEither.left.map(e => io.circe.DecodingFailure(e.getMessage, c.history))
      timeRange <- c.get[Option[String]]("time_range")
      location  <- c.get[Option[String]]("location")
      lecturer  <- c.get[Option[String]]("lecturer")
      indico    <- c.get[Option[String]]("indico_url")
      status    <- c.get[Option[String]]("status")
    yield Item(
      id = id,
      title = title,
      date = date,
      timeRange = nonEmpty(timeRange),
      location = nonEmpty(location),
      lecturer = nonEmpty(lecturer),
      indicoURL = nonEmpty(indico).flatMap(URL.decode(_).toOption),
      status = status.getOrElse("published")
    )

  /** "slovensko | English" -> "slovensko", the way the site shows it in Slovenian; a title in one language as is. */
  def slovenian(title: String): String =
    title.split(" \\| ") match
      case Array(sl, _) if sl.trim.nonEmpty => sl.trim
      case _                                => title.trim

  private val range: Regex = """(\d{1,2})[:.](\d{2})(?:\s*[–—-]\s*(\d{1,2})[:.](\d{2}))?""".r.unanchored

  private def clock(h: String, m: String): Option[LocalTime] =
    Try(LocalTime.of(h.toInt, m.toInt)).toOption

  /** "09:00–13:00" -> (09:00, Some(13:00)); "10:00" -> (10:00, None). */
  def timesOf(timeRange: Option[String]): Option[(LocalTime, Option[LocalTime])] =
    for
      raw   <- timeRange
      m     <- range.findFirstMatchIn(raw)
      start <- clock(m.group(1), m.group(2))
    yield start -> Option(m.group(3)).flatMap(h => clock(h, m.group(4)))
