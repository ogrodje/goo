package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import org.jsoup.nodes.Document
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.RacunalniskiMuzej
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, Request, URL}
import zio.{RIO, Scope, Task, ZIO}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try
import scala.util.matching.Regex

/** Events of Računalniški muzej (https://www.racunalniski-muzej.si/event/).
  *
  * The site is WordPress with the "ova-events" plugin, which does not expose events over the REST API, so the event
  * archive is read as HTML. It is sorted by event date, newest first. The archive cards have no year, so each event's
  * detail page is read for the date and time. Its "add to calendar" links carry the start and end in one fixed format
  * (`e[0][date_start]=12/02/2026 17:00`, month first), for single and multi-day events alike.
  */
final case class RacunalniskiMuzejParser(meetup: Meetup) extends Parser:
  import DocumentOps.*
  import RacunalniskiMuzejParser.*

  private val cetZone = ZoneId.of("Europe/Ljubljana")

  private def archiveURL(url: URL, page: Int): URL =
    if page == 1 then url.path("/event/") else url.path(s"/event/page/$page/")

  // Bounded to `maxPages`: the newest pages hold every upcoming event, and the older ones are long past. A page past
  // the end is a 404 without event cards, which ends paging too.
  private def eventURLs(client: Client, url: URL): RIO[Scope, List[URL]] =
    def go(page: Int, acc: List[URL]): RIO[Scope, List[URL]] = for
      document <- client.requestMetered(Request.get(archiveURL(url, page))).flatMap(_.body.asDocument)
      links    <- document.query(".archive_event .event_title a[href]").map(_.map(_.attr("abs:href")))
      urls     <- ZIO.foreach(links.distinct)(link => ZIO.fromEither(URL.decode(link)))
      all       = acc ++ urls.filterNot(acc.contains)
      result   <- if urls.nonEmpty && page < maxPages then go(page + 1, all) else ZIO.succeed(all)
    yield result
    go(1, Nil)

  private def at(localDateTime: LocalDateTime): OffsetDateTime =
    OffsetDateTime.of(localDateTime, cetZone.getRules.getOffset(localDateTime))

  // The value next to a label in the order box, e.g. "Lokacija:" -> "Računalniški muzej".
  private def infoValue(document: Document, label: String): Task[Option[String]] =
    document
      .query(".single_event .info_order li")
      .map(
        _.find(li => Option(li.selectFirst(".label")).exists(_.text.trim.equalsIgnoreCase(label)))
          .flatMap(li => Option(li.selectFirst(".label + span")))
          .map(_.text.replace(' ', ' ').trim)
          .filter(_.nonEmpty)
      )

  private def calendarParam(document: Document, name: String): Task[Option[String]] =
    document
      .queryFirst(".single_event a[href*=addtocalendar.com/atc/]")
      .map(_.flatMap(a => calendarParamIn(a.attr("href"), name)))

  private def parseDetail(client: Client, sourceURL: URL, eventURL: URL): RIO[Scope, Event] = for
    document <- client.requestMetered(Request.get(eventURL)).flatMap(_.body.asDocument)
    title    <- document.first(".single_event h1").map(_.text.trim)

    rawStart               <- calendarParam(document, "date_start")
                                .flatMap(fromOption(_).orElseFail(new RuntimeException("No calendar start date found")))
    (startDate, startTime) <- fromOption(calendarDateTime(rawStart))
                                .orElseFail(new RuntimeException(s"Could not parse start date from '$rawStart'"))
    end                    <- calendarParam(document, "date_end").map(_.flatMap(calendarDateTime))

    start        = startDate.atTime(startTime.getOrElse(LocalTime.MIDNIGHT))
    // Only an end with a time that is after the start: one event on the site ends two days before it begins.
    endDateTime  = end.collect { case (date, Some(time)) if date.atTime(time).isAfter(start) => date.atTime(time) }
    location    <- infoValue(document, "Lokacija:")
    description <- document
                     .queryFirst("meta[name=description]")
                     .map(_.map(_.attr("content").trim).filter(_.nonEmpty))
    postID      <- document
                     .queryFirst("body")
                     .map(_.flatMap(body => postIDClass.findFirstMatchIn(body.className)).map(_.group(1)))
    id           = postID.getOrElse(eventURL.path.toString.stripSuffix("/").split('/').last)
  yield Event
    .empty(
      id = s"racunalniski-muzej-$id",
      meetupID = meetup.id,
      source = RacunalniskiMuzej,
      sourceURL = sourceURL,
      title = title,
      startDateTime = at(start)
    )
    .copy(
      description = description,
      eventURL = Some(eventURL),
      endDateTime = endDateTime.map(at),
      hasStartTime = startTime.isDefined,
      hasEndTime = endDateTime.isDefined,
      locationName = location
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    urls      <- eventURLs(client, url)
    sourceURL <- ZIO.succeed(url.path("/event/"))
    events    <- ZIO
                   .foreachPar(urls)(eventURL =>
                     parseDetail(client, sourceURL, eventURL).asSome
                       .catchAll(err =>
                         logWarning(s"Failed to parse Računalniški muzej event $eventURL: ${err.getMessage}").as(None)
                       )
                   )
                   .withParallelism(detailParallelism)
  yield events.flatten

object RacunalniskiMuzejParser:
  private val maxPages          = 3
  private val detailParallelism = 4

  private val postIDClass: Regex = """\bpostid-(\d+)\b""".r

  // "12/02/2026 17:00" (month first) or just "12/02/2026".
  private val calendarDate: Regex = """(\d{1,2})/(\d{1,2})/(\d{4})(?:\s+(\d{1,2}):(\d{2}))?""".r

  /** The value of `e[0][<name>]` in an addtocalendar.com link (plain or percent-encoded). */
  def calendarParamIn(href: String, name: String): Option[String] =
    val keys = Set(s"e[0][$name]", s"e%5B0%5D%5B$name%5D")
    href
      .split('&')
      .iterator
      .map(_.split("=", 2))
      .collectFirst { case Array(key, value) if keys.contains(key.split('?').last) => value }
      .map(value => Try(URLDecoder.decode(value, StandardCharsets.UTF_8)).getOrElse(value).trim)
      .filter(_.nonEmpty)

  /** Date and (optional) time from a calendar value such as "12/02/2026 17:00". */
  def calendarDateTime(raw: String): Option[(LocalDate, Option[LocalTime])] =
    calendarDate.findFirstMatchIn(raw.trim).flatMap { m =>
      Try(LocalDate.of(m.group(3).toInt, m.group(1).toInt, m.group(2).toInt)).toOption
        .map(date => date -> Option(m.group(4)).flatMap(h => Try(LocalTime.of(h.toInt, m.group(5).toInt)).toOption))
    }
