package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{Decoder, Json}
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.PodjetniskiInkubatorPodbreznik
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, ZIO}

import java.time.{LocalDate, LocalDateTime, OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** Events of Podjetniški inkubator Podbreznik, the incubator of Razvojni center Novo mesto
  * (https://www.rc-nm.si/dogodki/).
  *
  * The site is WordPress with an event post type, `dogodek`, listed through the REST API. RC Novo mesto files events by
  * department (`oddelek`); only the entrepreneurship department's ("Podjetništvo": the incubator's meetups, workshops
  * and competitions) are read. The other departments' are regional development and community events (herb days, film
  * weeks, spatial planning), which aren't what goo collects.
  *
  * The REST data has no event fields, so each event's page is read:
  *   - its schema.org `Event` (`startDate`, `endDate`, `eventAttendanceMode`). The site writes local Ljubljana time
  *     with a wrong "+00:00" offset (16:00 on the page is "16:00:00+00:00"), so the offset is ignored;
  *   - the sidebar: `.single-event-meta-schedule-time-start/-end` say whether there is a time at all (an event without
  *     one has "00:00" in its `startDate`), and `.event-location` holds the place and its address, one per line.
  */
final case class PodbreznikParser(meetup: Meetup) extends Parser:
  import PodbreznikParser.*

  private val zone = ZoneId.of("Europe/Ljubljana")

  private def get(client: Client, url: URL): RIO[Scope, String] = for
    response <- client.requestMetered(Request.get(url))
    _        <- ZIO.unless(response.status.isSuccess)(ZIO.fail(new RuntimeException(s"$url: ${response.status}")))
    body     <- response.body.asString
  yield body

  private def entrepreneurship(client: Client, url: URL): RIO[Scope, Long] = for
    body <- get(client, url.path("/wp-json/wp/v2/oddelek").setQueryParams(QueryParams("slug" -> departmentSlug)))
    ids  <- ZIO.fromEither(parseJson(body).flatMap(_.as[List[Json]])).map(_.flatMap(_.hcursor.get[Long]("id").toOption))
    id   <- fromOption(ids.headOption).orElseFail(new RuntimeException(s"No '$departmentSlug' department"))
  yield id

  private def posts(client: Client, url: URL, department: Long): RIO[Scope, List[Post]] = for
    body  <- get(
               client,
               url
                 .path("/wp-json/wp/v2/dogodek")
                 .setQueryParams(
                   QueryParams(
                     "oddelek"  -> department.toString,
                     "per_page" -> perPage.toString,
                     "_fields"  -> "id,link,title"
                   )
                 )
             )
    posts <- ZIO.fromEither(parseJson(body).flatMap(_.as[List[Post]]))
  yield posts

  private def at(dateTime: LocalDateTime): OffsetDateTime =
    OffsetDateTime.of(dateTime, zone.getRules.getOffset(dateTime))

  private def toEvent(sourceURL: URL, post: Post, page: Page): Event =
    val start         = if page.hasStartTime then page.start else page.start.toLocalDate.atStartOfDay
    val startDateTime = at(start)
    val end           = page.end.flatMap { end =>
      if page.hasEndTime then Some(at(end)).filter(_.isAfter(startDateTime))
      // All day, over several days: it ends on its last day.
      else Option.when(end.toLocalDate.isAfter(start.toLocalDate))(at(end.toLocalDate.atStartOfDay))
    }

    Event
      .empty(
        id = s"rcnm-${post.id}",
        meetupID = meetup.id,
        source = PodjetniskiInkubatorPodbreznik,
        sourceURL = sourceURL,
        title = post.title,
        startDateTime = startDateTime
      )
      .copy(
        description = page.description,
        eventURL = Some(post.link),
        endDateTime = end,
        hasStartTime = page.hasStartTime,
        hasEndTime = page.hasEndTime && end.isDefined,
        locationName = page.place,
        locationAddress = page.address
      )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    department <- entrepreneurship(client, url)
    posts      <- posts(client, url, department)
    sourceURL   = url.path("/dogodki/")
    events     <- ZIO.foreach(posts)(post =>
                    get(client, post.link)
                      .flatMap(html => ZIO.fromEither(pageOf(html)))
                      .map(page => Some(toEvent(sourceURL, post, page)))
                      .catchAll(err => logWarning(s"Skipping RC Novo mesto event ${post.link}: ${err.getMessage}").as(None))
                  )
  yield events.flatten

object PodbreznikParser:
  import DocumentOps.urlDecoder

  private val departmentSlug = "oddelek-podjetnistvo"
  // Newest published first; a couple of entrepreneurship events a month, so this reaches back half a year or more.
  private val perPage        = 20

  final private[parsers] case class Post(id: Long, link: URL, title: String)

  private given Decoder[Post] = Decoder.instance: c =>
    for
      id    <- c.get[Long]("id")
      link  <- c.get[URL]("link")
      title <- c.downField("title").get[String]("rendered")
    yield Post(id, link, Jsoup.parse(title).text.trim)

  /** What an event's page says: when (local time), whether it has a start and end time, where, and its lede. */
  final private[parsers] case class Page(
    start: LocalDateTime,
    end: Option[LocalDateTime],
    hasStartTime: Boolean,
    hasEndTime: Boolean,
    place: Option[String],
    address: Option[String],
    description: Option[String]
  )

  private val clockTime: Regex = """(\d{1,2})[:.](\d{2})""".r.unanchored

  private def hasTime(document: Document, query: String): Boolean =
    Option(document.selectFirst(query)).exists(e => clockTime.findFirstIn(e.text).isDefined)

  // "2026-11-25T16:00:00+00:00" -> 2026-11-25T16:00, the offset being wrong (see the class comment).
  private def localOf(raw: String): Option[LocalDateTime] =
    Try(LocalDateTime.parse(raw.take(19))).orElse(Try(LocalDate.parse(raw.take(10)).atStartOfDay)).toOption

  /** The schema.org `Event` among the page's JSON-LD blocks (Yoast puts it in an `@graph`). */
  def eventIn(document: Document): Option[Json] =
    document
      .select("script[type=application/ld+json]")
      .asScala
      .iterator
      .flatMap(script => parseJson(script.data).toOption)
      .flatMap(json => json.hcursor.get[List[Json]]("@graph").getOrElse(List(json)))
      .find(_.hcursor.get[String]("@type").contains("Event"))

  def pageOf(html: String): Either[Throwable, Page] =
    val document = Jsoup.parse(html)
    for
      event <- eventIn(document).toRight(new RuntimeException("No schema.org Event on the page"))
      c      = event.hcursor
      start <- c.get[String]("startDate").toOption.flatMap(localOf).toRight(new RuntimeException("No startDate"))
    yield
      val end    = c.get[String]("endDate").toOption.flatMap(localOf)
      val online = c.get[String]("eventAttendanceMode").toOption.exists(_.contains("Online"))
      val lines  = Option(document.selectFirst(".event-location")).toList
        .flatMap(_.html.split("(?i)<br\\s*/?>").toList)
        .map(line => Jsoup.parse(line).text.replace(' ', ' ').trim)
        .filter(_.nonEmpty)

      Page(
        start = start,
        end = end,
        hasStartTime = hasTime(document, ".single-event-meta-schedule-time-start"),
        hasEndTime = hasTime(document, ".single-event-meta-schedule-time-end"),
        place = lines.headOption.orElse(Option.when(online)("Online")),
        address = Option(lines.drop(1).mkString(", ")).filter(_.nonEmpty),
        description = c.get[String]("description").toOption.map(_.trim).filter(_.nonEmpty)
      )
