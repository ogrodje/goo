package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.Katapult
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.logWarning
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, ZIO}

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** Events of Katapult, the hardware startup accelerator in Trbovlje (https://katapult.si/dogodki).
  *
  * The site (Laravel Livewire) lists events as cards linking to posts, `/novice/<slug>`, 8 per page (`?page=N`). A
  * card's title may carry the date ("… I 17.11.2026") or not, so each post is read: its side column states when and
  * where,
  * {{{
  * Kdaj? Torek, 17. november 2026, od 9.00 do 12.00
  * Kje? Katapult in Dewesoft, Trbovlje
  * }}}
  * written in many ways: "Četrtek, 22.10.2026, 9.00 - 12.00", "Četrtek, 22. oktobra, ob 17.30–20.30" (no year), "Od
  * četrtka, 26., do sobote, 28. novembra 2026", "23., 27. in 29. oktober 2026, vsak dan od 9.00 do 16.00". The date
  * under a post's title is when it was published.
  *
  * A post without a "Kdaj" line naming a day (a course over months, a workshop in two terms) is logged and skipped.
  */
final case class KatapultParser(meetup: Meetup) extends Parser:
  import KatapultParser.*

  private def get(client: Client, url: URL): RIO[Scope, Document] = for
    response <- client.requestMetered(Request.get(url))
    _        <- ZIO.unless(response.status.isSuccess)(ZIO.fail(new RuntimeException(s"$url: ${response.status}")))
    html     <- response.body.asString
  yield Jsoup.parse(html, url.encode)

  private def listing(client: Client, url: URL): RIO[Scope, List[String]] =
    ZIO
      .foldLeft(1 to maxPages)((List.empty[String], false)) { case ((acc, done), page) =>
        if done then ZIO.succeed(acc -> true)
        else
          val pageURL =
            if page == 1 then url.path("/dogodki")
            else url.path("/dogodki").setQueryParams(QueryParams("page" -> page.toString))
          get(client, pageURL).map(doc => slugsIn(doc)).map(slugs => (acc ++ slugs, slugs.isEmpty))
      }
      .map(_._1.distinct)

  private def toEvent(base: URL, slug: String, post: Post): Event =
    val when  = post.when
    val start = at(when.startDate, when.startTime.getOrElse(LocalTime.MIDNIGHT))
    val end   = when.endDate match
      case Some(endDate) => Some(at(endDate, when.endTime.getOrElse(LocalTime.MIDNIGHT)))
      case None          => when.endTime.map(at(when.startDate, _))

    val endDateTime = end.filter(_.isAfter(start))
    Event
      .empty(
        id = s"katapult-$slug",
        meetupID = meetup.id,
        source = Katapult,
        sourceURL = base.path("/dogodki"),
        title = post.title,
        startDateTime = start
      )
      .copy(
        description = post.description,
        eventURL = Some(base.path(s"/novice/$slug")),
        endDateTime = endDateTime,
        hasStartTime = when.startTime.isDefined,
        hasEndTime = endDateTime.isDefined && when.endTime.isDefined,
        locationName = post.place
      )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    slugs  <- listing(client, url)
    events <-
      ZIO.foreach(slugs)(slug =>
        get(client, url.path(s"/novice/$slug"))
          .flatMap(doc => ZIO.fromOption(postIn(doc)).orElseFail(new RuntimeException("no 'Kdaj' line with a day")))
          .map(post => Some(toEvent(url, slug, post)))
          .catchAll(err => logWarning(s"Skipping Katapult post $slug: ${err.getMessage}").as(None))
      )
  yield events.flatten

object KatapultParser:
  private val zone     = ZoneId.of("Europe/Ljubljana")
  // 8 posts per page: two pages reach back about half a year.
  private val maxPages = 2

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  /** When an event happens: its first (and last, for several days) day, and the time of day. */
  final private[parsers] case class When(
    startDate: LocalDate,
    startTime: Option[LocalTime],
    endDate: Option[LocalDate],
    endTime: Option[LocalTime]
  )

  final private[parsers] case class Post(
    title: String,
    when: When,
    place: Option[String],
    description: Option[String]
  )

  /** The posts a listing page links to, in order. */
  def slugsIn(doc: Document): List[String] =
    doc
      .select("h3.headline3 a[href^=/novice/], h3.headline3 a[href*=katapult.si/novice/]")
      .asScala
      .map(_.attr("href").split("/novice/").last.takeWhile(c => c != '?' && c != '#').stripSuffix("/"))
      .filter(_.nonEmpty)
      .toList
      .distinct

  private val publishedFormat = DateTimeFormatter.ofPattern("d.M.yyyy")

  /** A post's title, when and where; `None` without a "Kdaj" line naming a day. */
  def postIn(doc: Document): Option[Post] =
    val paragraphs = doc.select("div.e-text p").asScala.map(_.text.replace(' ', ' ').trim).toList
    val published  = Option(doc.selectFirst(".blog-single-info-meta span.text-left"))
      .flatMap(e => Try(LocalDate.parse(e.text.trim, publishedFormat)).toOption)
      .getOrElse(LocalDate.now(zone))
    for
      title <- Option(doc.selectFirst("h1")).map(h => cleanTitle(h.text)).filter(_.nonEmpty)
      kdaj  <- paragraphs.collectFirst { case whenLine(rest) => rest }
      when  <- whenOf(kdaj, published)
    yield Post(
      title = title,
      when = when,
      place = paragraphs.collectFirst { case whereLine(rest) => rest.trim }.filter(_.nonEmpty),
      description = Option(doc.selectFirst(".blog-single-subtitle")).map(_.text.trim).filter(_.nonEmpty)
    )

  // "Kdaj? …", "Kdaj: …", "Datum: …"; "Kje? …", "Kje: …", "Lokacija: …" — not "Kje se najpogosteje zatakne: …".
  private val whenLine: Regex  = """(?iu)^(?:kdaj|datum)\s*[?:]\s*(.+)$""".r
  private val whereLine: Regex = """(?iu)^(?:kje|lokacija|kraj)\s*[?:]\s*(.+)$""".r

  // "Dogodek: Obisk Katapulta …" -> "Obisk Katapulta …"; "… I 17.11.2026", "… 22.10.2026", "… (23., 27. in 29. oktober)"
  // dropped: the date is in the event's fields.
  private val datedSuffix: Regex      = """\s+(?:I\s+)?\d{1,2}\.[\d.\s–—-]*\d{4}\s*$""".r
  private val bracketedDays: Regex    = """\s*\(\d{1,2}\.[^)]*\)\s*$""".r
  private val dogodekPrefix: Regex    = """(?iu)^(?:brezplačni\s+)?dogodek\s*:\s*""".r
  def cleanTitle(raw: String): String =
    val noDate = bracketedDays.replaceAllIn(datedSuffix.replaceAllIn(raw.trim, ""), "")
    dogodekPrefix.replaceFirstIn(noDate, "").trim

  private val months: Map[String, Int] = Map(
    "jan" -> 1,
    "feb" -> 2,
    "mar" -> 3,
    "apr" -> 4,
    "maj" -> 5,
    "jun" -> 6,
    "jul" -> 7,
    "avg" -> 8,
    "sep" -> 9,
    "okt" -> 10,
    "nov" -> 11,
    "dec" -> 12
  )

  // A day, then maybe a month (number with a dot, or name), then maybe a year: "17. november 2026", "22.10.2026",
  // "16. 12. 2025", "16.6. 2026", "22. oktobra", or a bare "26." whose month comes later.
  private val dayToken: Regex =
    """(?iu)(?<![\d.])(\d{1,2})\.\s*(?:(\d{1,2})\.|(jan|feb|mar|apr|maj|jun|jul|avg|sep|okt|nov|dec)\p{L}*)?(?:\s*,?\s*(\d{4}))?""".r

  // "od 9.00 do 12.00", "9.00 - 12.00", "ob 17.30–20.30", "15.00–18.00", "17.30".
  private val timeRange: Regex =
    """(\d{1,2})[:.](\d{2})(?!\d|\.\d)(?:\s*(?:–|—|-|do)\s*(\d{1,2})[:.](\d{2})(?!\d))?""".r

  final private case class Token(day: Int, month: Option[Int], year: Option[Int], end: Int)

  private def tokens(text: String): List[Token] =
    dayToken
      .findAllMatchIn(text)
      .flatMap { m =>
        val month  = Option(m.group(2))
          .flatMap(_.toIntOption)
          .orElse(Option(m.group(3)).flatMap(n => months.get(n.toLowerCase.take(3))))
        val year   = Option(m.group(4)).flatMap(_.toIntOption)
        // "9.00", "17.30": a time, not a day — the dot is followed by digits that aren't a month.
        val isTime = month.isEmpty && year.isEmpty && text.lift(m.start(1) + m.group(1).length + 1).exists(_.isDigit)
        Option.unless(isTime || month.exists(mm => mm < 1 || mm > 12))(Token(m.group(1).toInt, month, year, m.end))
      }
      .toList

  private def clock(h: String, m: String): Option[LocalTime] =
    (h.toIntOption, m.toIntOption) match
      case (Some(24), Some(0))                        => Some(LocalTime.of(23, 59))
      case (Some(hh), Some(mm)) if hh < 24 && mm < 60 => Some(LocalTime.of(hh, mm))
      case _                                          => None

  /** When, from a "Kdaj" line, given when the post was published. A day without a month takes the next day's month
    * ("26., do sobote, 28. novembra"); a missing year is the next day's, else the publish year. A date two months
    * before publishing is taken to be next year: posts announce, and one says "9.3.2025" for March 2026.
    */
  def whenOf(text: String, published: LocalDate): Option[When] =
    val found  = tokens(text)
    // Fill a missing month and year from the days after it.
    val filled = found.foldRight(List.empty[Token]) { (t, later) =>
      val month = t.month.orElse(later.headOption.flatMap(_.month))
      val year  = t.year.orElse(later.headOption.flatMap(_.year))
      t.copy(month = month, year = year) :: later
    }
    val dates  = filled.flatMap { t =>
      for
        month <- t.month
        date  <- Try(LocalDate.of(t.year.getOrElse(published.getYear), month, t.day)).toOption
      yield (if date.isBefore(published.minusDays(60)) then date.plusYears(1) else date) -> t.end
    }
    dates.headOption.map { case (startDate, _) =>
      val lastDay = dates.last
      val endDate = Some(lastDay._1).filter(d => d.isAfter(startDate) && !d.isAfter(startDate.plusDays(31)))
      // The time of day follows the (last) day: "…, od 9.00 do 12.00", "… 2026, vsak dan od 9.00 do 16.00".
      val times   = timeRange.findFirstMatchIn(text.substring(lastDay._2))
      val start   = times.flatMap(m => clock(m.group(1), m.group(2)))
      val end     = times.flatMap(m => Option(m.group(3)).flatMap(h => clock(h, m.group(4)))).filter(_ => start.isDefined)
      When(startDate, start, endDate, end)
    }
