package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.DataScience
import si.ogrodje.goo.models.{Event, Meetup}
import zio.http.{Client, Request, URL}
import zio.{Clock, RIO, Scope, ZIO}

import java.text.Normalizer
import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try
import scala.util.matching.Regex

/** Events of DataScience@UL-FRI (https://datascience.fri.uni-lj.si/events/), the data science initiative of FRI.
  *
  * The events page is static; its calendar is drawn from JSON embedded in the page, `<script type=application/json
  * id=events-data>`, one item per post:
  * {{{
  * {"iso": "2026-11-26", "kind": "workshop", "title": "Workshop: Pair programming with AI", "time": "17:00",
  *  "meta": "17:00 · FRI, Lecture room 3", "url": "/news/workshop_2026-11-26/",
  *  "excerpt": "When? Thursday, 26 November 2026, 17:00–20:00.\nWhere? Faculty of …, Ljubljana. Lecture room 3.\nWhat?\n…"}
  * }}}
  * The list also holds news and competition announcements and counselling slots, which are not events. The end time and
  * the place come from the excerpt's "When?" and "Where?" lines; without them, `meta` is the place of a meetup (for
  * older workshops it is the presenter, so it isn't used).
  *
  * FRI's own calendar repeats some of these events; [[FRIParser]] leaves out the ones listed here (see [[sameEvent]]).
  */
final case class DataScienceParser(meetup: Meetup) extends Parser:
  import DataScienceParser.*

  private def toEvent(base: URL)(item: Item): Event =
    val when  = timesOf(item.when)
    val start = item.time.flatMap(clock).orElse(when.map(_._1))
    val end   = when.filter((from, _) => start.contains(from)).flatMap(_._2)

    val startDateTime = at(item.date, start.getOrElse(LocalTime.MIDNIGHT))
    val endDateTime   = end.map(at(item.date, _)).filter(_.isAfter(startDateTime))

    Event
      .empty(
        id = s"datascience-${item.slug}",
        meetupID = meetup.id,
        source = DataScience,
        sourceURL = base.path("/events/"),
        title = item.title,
        startDateTime = startDateTime
      )
      .copy(
        description = item.description,
        eventURL = Some(base.path(item.path)),
        endDateTime = endDateTime,
        hasStartTime = start.isDefined,
        hasEndTime = endDateTime.isDefined,
        locationName = item.location
      )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    today <- Clock.localDateTime.map(_.toLocalDate)
    items <- listed(client, url, today)
  yield items.map(toEvent(url))

object DataScienceParser:
  /** Where DataScience@UL-FRI lists its events; [[FRIParser]] reads it too, to skip events listed here. */
  val homepage: URL = URL.decode("https://datascience.fri.uni-lj.si").toOption.get

  private val zone = ZoneId.of("Europe/Ljubljana")

  // Posts that are not events: announcements, results, and counselling appointments booked one by one.
  private val notEvents = Set("news", "competition", "counseling", "counselling")

  // Older posts reach back to 2021.
  private val monthsBack = 12L

  final private[parsers] case class Item(
    path: String,
    date: LocalDate,
    kind: String,
    title: String,
    time: Option[String],
    meta: Option[String],
    excerpt: Option[String]
  ):
    def slug: String = path.split('/').filter(_.nonEmpty).lastOption.getOrElse(path).replaceAll("[^A-Za-z0-9_-]", "-")

    private def lines: List[String]                 = excerpt.toList.flatMap(_.split('\n').map(_.trim).filter(_.nonEmpty))
    private def line(label: String): Option[String] =
      lines.collectFirst { case l if l.startsWith(label) => l.stripPrefix(label).trim }.filter(_.nonEmpty)

    def when: Option[String] = line("When?")

    def location: Option[String] =
      line("Where?")
        .map(_.stripSuffix(".").trim)
        .orElse(meta.collect { case m if m.contains(" · ") => m.split(" · ", 2)(1).trim })
        .orElse(meta.filter(_ => kind == "meetup"))
        .filter(_.nonEmpty)

    /** The "What?" part of the excerpt, else the excerpt itself when it has no When/Where structure. */
    def description: Option[String] =
      val what = lines.dropWhile(_ != "What?").drop(1)
      val text =
        if what.nonEmpty then what.mkString("\n")
        else if when.isEmpty && line("Where?").isEmpty then lines.mkString("\n")
        else ""
      Option(text.trim).filter(_.nonEmpty)

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

  private given Decoder[Item] = Decoder.instance: c =>
    for
      path    <- c.get[String]("url")
      iso     <- c.get[String]("iso")
      date    <- Try(LocalDate.parse(iso)).toEither.left.map(e => io.circe.DecodingFailure(e.getMessage, c.history))
      kind    <- c.get[Option[String]]("kind")
      title   <- c.get[String]("title")
      time    <- c.get[Option[String]]("time")
      meta    <- c.get[Option[String]]("meta")
      excerpt <- c.get[Option[String]]("excerpt")
    yield Item(
      path = path,
      date = date,
      kind = kind.map(_.trim.toLowerCase).getOrElse(""),
      title = title.trim,
      time = nonEmpty(time),
      meta = nonEmpty(meta),
      excerpt = nonEmpty(excerpt)
    )

  /** The items of the events page's embedded JSON; items that don't decode are left out. */
  def itemsIn(html: String): Either[Throwable, List[Item]] = for
    script <- Option(Jsoup.parse(html).selectFirst("script#events-data"))
                .toRight(new RuntimeException("No events-data on the DataScience events page"))
    json   <- parseJson(script.data)
    items  <- json.as[List[io.circe.Json]]
  yield items.flatMap(_.as[Item].toOption)

  /** The events DataScience lists, from `monthsBack` months ago on. */
  def listed(client: Client, url: URL, today: LocalDate): RIO[Scope, List[Item]] = for
    response <- client.requestMetered(Request.get(url.path("/events/")))
    html     <- response.body.asString
    items    <- ZIO.fromEither(itemsIn(html))
  yield items
    .filterNot(item => notEvents.contains(item.kind))
    .filterNot(_.date.isBefore(today.minusMonths(monthsBack)))

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private val clockTime: Regex                      = """^(\d{1,2})[:.](\d{2})$""".r
  private def clock(raw: String): Option[LocalTime] = raw.trim match
    case clockTime(h, m) => Try(LocalTime.of(h.toInt, m.toInt)).toOption
    case _               => None

  private val range: Regex =
    """(?<![\d.])(\d{1,2})[:.](\d{2})(?:\s*[–—-]\s*(\d{1,2})[:.](\d{2}))?""".r.unanchored

  /** "Thursday, 26 November 2026, 17:00–20:00." -> (17:00, Some(20:00)); "… at 19:00." -> (19:00, None). */
  def timesOf(when: Option[String]): Option[(LocalTime, Option[LocalTime])] =
    for
      raw   <- when
      m     <- range.findFirstMatchIn(raw)
      start <- Try(LocalTime.of(m.group(1).toInt, m.group(2).toInt)).toOption
    yield start -> Option(m.group(3)).flatMap(h => Try(LocalTime.of(h.toInt, m.group(4).toInt)).toOption)

  // Words that every DataScience title may carry, and filler, which say nothing about which event it is.
  private val noise = Set(
    "data",
    "science",
    "datascience",
    "ul",
    "fri",
    "the",
    "and",
    "with",
    "for",
    "by",
    "in",
    "of",
    "on",
    "na",
    "za",
    "in",
    "pri",
    "workshop",
    "delavnica"
  )

  private def words(title: String): Set[String] =
    Normalizer
      .normalize(title.toLowerCase, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .split("[^a-z]+")
      .filter(w => w.length >= 2 && !noise.contains(w))
      .toSet

  /** Whether an event on FRI's calendar is one DataScience lists: on the same day, and at least half the words of the
    * shorter title (after the generic ones) in the other, e.g. "Data Science: Spark Sessions" and "Spark sessions 007".
    */
  def sameEvent(date: LocalDate, title: String, item: Item): Boolean =
    date == item.date && {
      val (a, b) = (words(title), words(item.title))
      val shared = (a intersect b).size
      shared > 0 && shared * 2 >= math.min(a.size, b.size)
    }
