package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.LUI
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logDebug}
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, Task, ZIO}

import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try
import scala.util.matching.Regex

/** Events of Ljubljanski univerzitetni inkubator (https://lui.si/aktualno/).
  *
  * The site is WordPress: events are ordinary posts in the "dogodki" category, listed through the REST API. The post
  * date is when it was published, and there are no event fields, so the date, time and place are read from the post's
  * own text, where LUI writes them in many ways:
  *   - `KDAJ: 27. oktober 2026, med 13:00 – 16:00 KJE: LUI – …, Vojkova cesta 63, Ljubljana`
  *   - `Datum: torek, 13. oktober 2026` / `Čas: 14.00–17.00` / `Lokacija: LUI`
  *   - `Kdaj: Ponedeljek, 31. 8. ob 9.00 (do 12.00)` (no year)
  *   - `KDAJ: petek, 25. september in sobota, 26. september 2026` (two days)
  *   - `📅 7. oktober 2026, 10.00–13.00` / `📍 LUI, Vojkova cesta 63, Ljubljana`
  *
  * A post without a recognisable date (a programme announcement rather than an event) is skipped.
  */
final case class LUIParser(meetup: Meetup) extends Parser:
  import LUIParser.*

  private val zone = ZoneId.of("Europe/Ljubljana")

  private def request(client: Client, url: URL): RIO[Scope, (String, Option[Int])] = for
    response <- client.requestMetered(Request.get(url))
    body     <- response.body.asString
  yield body -> response.rawHeader("X-WP-TotalPages").flatMap(_.toIntOption)

  private def categoryID(client: Client, url: URL): RIO[Scope, Long] = for
    (body, _) <- request(client, url.path("/wp-json/wp/v2/categories").setQueryParams(QueryParams("slug" -> "dogodki")))
    ids       <- ZIO.fromEither(parseJson(body).flatMap(_.as[List[Long]](using Decoder.decodeList(using idDecoder))))
    id        <- fromOption(ids.headOption).orElseFail(new RuntimeException("No 'dogodki' category"))
  yield id

  // Newest published first, bounded to `maxPages`: older posts are long past.
  private def posts(client: Client, url: URL, category: Long): RIO[Scope, List[Post]] =
    def go(page: Int, acc: List[Post]): RIO[Scope, List[Post]] = for
      (body, totalPages) <- request(
                              client,
                              url
                                .path("/wp-json/wp/v2/posts")
                                .setQueryParams(
                                  QueryParams(
                                    "categories" -> category.toString,
                                    "per_page"   -> perPage.toString,
                                    "page"       -> page.toString,
                                    "_fields"    -> "id,date,link,title,content,yoast_head_json.description"
                                  )
                                )
                            )
      posts              <- ZIO.fromEither(parseJson(body).flatMap(_.as[List[Post]]))
      all                 = acc ++ posts
      result             <-
        if page < totalPages.getOrElse(page) && page < maxPages then go(page + 1, all) else ZIO.succeed(all)
    yield result
    go(1, Nil)

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private def toEvent(sourceURL: URL, post: Post): Task[Option[Event]] =
    ZIO.attempt(details(linesOf(post.contentHtml), post.published)).map {
      case None          => None
      case Some(details) =>
        val start = at(details.startDate, details.startTime.getOrElse(LocalTime.MIDNIGHT))
        // A time range on one day gives the end time; a second day gives the end date.
        val end   = details.endDate match
          case Some(endDate) => Some(at(endDate, LocalTime.MIDNIGHT))
          case None          =>
            for
              _       <- details.startTime
              endTime <- details.endTime
              end      = at(details.startDate, endTime)
              if end.isAfter(start)
            yield end

        Some(
          Event
            .empty(
              id = s"lui-${post.id}",
              meetupID = meetup.id,
              source = LUI,
              sourceURL = sourceURL,
              title = post.title,
              startDateTime = start
            )
            .copy(
              description = post.description,
              eventURL = Some(post.link),
              endDateTime = end,
              hasStartTime = details.startTime.isDefined,
              hasEndTime = details.endDate.isEmpty && end.isDefined,
              locationName = details.location
            )
        )
    }

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    category  <- categoryID(client, url)
    posts     <- posts(client, url, category)
    sourceURL <- ZIO.succeed(url.path("/aktualno/"))
    events    <- ZIO.foreach(posts)(post =>
                   toEvent(sourceURL, post).tap {
                     case None => logDebug(s"LUI post without an event date, skipped: ${post.link}")
                     case _    => ZIO.unit
                   }
                 )
  yield events.flatten

object LUIParser:
  import DocumentOps.urlDecoder

  private val perPage  = 20
  private val maxPages = 2

  /** What a post's text says about when and where. */
  final private[parsers] case class Details(
    startDate: LocalDate,
    startTime: Option[LocalTime],
    endTime: Option[LocalTime],
    endDate: Option[LocalDate],
    location: Option[String]
  )

  final private[parsers] case class Post(
    id: Long,
    published: LocalDate,
    link: URL,
    title: String,
    contentHtml: String,
    description: Option[String]
  )

  private val idDecoder: Decoder[Long] = Decoder.instance(_.get[Long]("id"))

  private given Decoder[Post] = Decoder.instance: c =>
    for
      id          <- c.get[Long]("id")
      date        <- c.get[String]("date")
      published   <-
        Try(LocalDate.parse(date.take(10))).toEither.left.map(e => io.circe.DecodingFailure(e.getMessage, c.history))
      link        <- c.get[URL]("link")
      title       <- c.downField("title").get[String]("rendered")
      content     <- c.downField("content").get[String]("rendered")
      description <- c.downField("yoast_head_json").get[Option[String]]("description").orElse(Right(None))
    yield Post(id, published, link, Jsoup.parse(title).text.trim, content, description.map(_.trim).filter(_.nonEmpty))

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

  // A line that says when: "KDAJ:", "Kdaj in kje:", "Datum:", or one starting with 📅.
  private val whenMarker: Regex  = """(?iu)^\W*(?:(?:kdaj|datum)\b[^:]{0,12}:|📅)""".r
  private val whereMarker: Regex = """(?iu)(?:\b(?:kje|lokacija)\s*:|📍)\s*""".r
  // Where the "when" part ends.
  private val stopMarker: Regex  = """(?iu)\b(?:kje|lokacija|prijav\w*|udeležba|cena)\s*:|📍""".r

  // "13. november 2026", "25. september", "03. 09. 2026", "31. 8.", "16.5.2026"
  private val date: Regex =
    """(?iu)\b(\d{1,2})\.\s*(?:(\d{1,2})\.|(jan|feb|mar|apr|maj|jun|jul|avg|sep|okt|nov|dec)[\p{L}]*)(?:\s*,?\s*(\d{4}))?""".r
  private val year: Regex = """\b(20\d\d)\b""".r

  // "9:00 – 10:30", "ob 9.00 (do 12.00)", "od 12.00 do 24.00", "14.00–17.00"; never "16.5.2026".
  private val timeRange: Regex =
    """(?iu)(\d{1,2})[:.](\d{2})(?!\d|\.\d)(?:\s*(?:uri?|h)?[\s(]*(?:–|—|-|do|in)\s*(\d{1,2})[:.](\d{2})(?!\d|\.\d))?""".r

  /** The post's text as lines, split where the HTML breaks lines or paragraphs. */
  def linesOf(html: String): List[String] =
    html
      .replaceAll("""(?i)<br\s*/?>|</(?:p|li|h\d|div)>""", "\n")
      .split('\n')
      .iterator
      .map(chunk => Jsoup.parse(chunk).text.replace(' ', ' ').replaceAll("""\s+""", " ").trim)
      .filter(_.nonEmpty)
      .toList

  private def clock(h: String, m: String): Option[LocalTime] =
    (h.toIntOption, m.toIntOption) match
      case (Some(24), Some(0))                        => Some(LocalTime.of(23, 59))
      case (Some(hh), Some(mm)) if hh < 24 && mm < 60 => Some(LocalTime.of(hh, mm))
      case _                                          => None

  final private case class Found(date: LocalDate, start: Int, end: Int)

  /** Dates in `text`, in order. A missing year is the one written later in the text, else the publish year; a date that
    * would be two months before publishing is taken to be in the next year (posts announce, they don't recap — one post
    * says "3. februar 2025" for February 2026).
    */
  private def datesIn(text: String, published: LocalDate): List[Found] =
    date
      .findAllMatchIn(text)
      .flatMap { m =>
        val month   = Option(m.group(2))
          .flatMap(_.toIntOption)
          .orElse(Option(m.group(3)).flatMap(n => months.get(n.toLowerCase.take(3))))
        val yearNum = Option(m.group(4))
          .flatMap(_.toIntOption)
          .orElse(year.findFirstMatchIn(text.substring(m.end)).map(_.group(1).toInt))
          .getOrElse(published.getYear)
        for
          mm   <- month
          d    <- Try(LocalDate.of(yearNum, mm, m.group(1).toInt)).toOption
          fixed = if d.isBefore(published.minusDays(60)) then d.plusYears(1) else d
        yield Found(fixed, m.start, m.end)
      }
      .toList

  /** When and where, from the lines of a post; `None` when it names no date. */
  def details(lines: List[String], published: LocalDate): Option[Details] =
    val indexed = lines.toIndexedSeq
    for
      at     <- indexed.indices.find(i => whenMarker.findFirstIn(indexed(i)).isDefined)
      marker <- whenMarker.findFirstMatchIn(indexed(at))
      window  =
        val raw = (indexed(at).substring(marker.end) +: indexed.slice(at + 1, at + 3)).mkString(" ").trim
        stopMarker.findFirstMatchIn(raw).filter(_.start > 0).fold(raw)(m => raw.substring(0, m.start))
      found   = datesIn(window, published)
      first  <- found.headOption
      endDate = found.drop(1).map(_.date).find(d => d.isAfter(first.date) && !d.isAfter(first.date.plusDays(14)))
      // The time of the first day: between the first and the second date, else anywhere after the first date.
      segment = window.substring(first.end, found.lift(1).map(_.start).getOrElse(window.length))
      times   = timeRange.findFirstMatchIn(segment).orElse(timeRange.findFirstMatchIn(window.substring(first.end)))
      start   = times.flatMap(m => clock(m.group(1), m.group(2)))
      end     = times.flatMap(m => Option(m.group(3)).flatMap(h => clock(h, m.group(4))))
    yield Details(first.date, start, end.filter(_ => start.isDefined), endDate, locationIn(indexed))

  // The place after "KJE:"/"Lokacija:"/📍, or on the next line when the marker stands alone. Per-day directions
  // ("Petek, 16. oktober 2026 – zbirno mesto …") are not a place.
  private def locationIn(lines: IndexedSeq[String]): Option[String] =
    lines.indices.iterator
      .flatMap(i =>
        whereMarker.findFirstMatchIn(lines(i)).map { m =>
          val sameLine = cut(lines(i).substring(m.end))
          if sameLine.nonEmpty then sameLine else lines.lift(i + 1).map(cut).getOrElse("")
        }
      )
      .find(place => place.nonEmpty && whenMarker.findFirstIn(place).isEmpty && !startsWithDate(place))
      .map(_.take(200))

  private def cut(text: String): String =
    stopMarker.findFirstMatchIn(text).filter(_.start > 0).fold(text)(m => text.substring(0, m.start)).trim

  private val weekdayThenDate: Regex                = """(?iu)^(?:[\p{L}]+,\s*)?\d{1,2}\.\s*""".r
  private def startsWithDate(text: String): Boolean = weekdayThenDate.findFirstIn(text).isDefined
