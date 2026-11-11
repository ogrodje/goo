package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.SpletneUrice
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.logWarning
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, ZIO}

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** Talks of Spletne urice Maribor (#wwwhmb, https://wwwhmb.si/predavanja/).
  *
  * The site is WordPress. Talks are posts in the "predavanja" category, read through the REST API. The post's date is
  * when it was published; the talk's own date, time and place open the post content, in one of two forms:
  *   - since 2026: `1. december 2026 ob 19:05 // BarCoda, Vetrinjc` (with the year),
  *   - before: `torek, 3. marec @ 19:05 // Vetrinjc – BarCoda, Mb //` (no year: it is the first such date on or after
  *     the post was published).
  */
final case class SpletneUriceParser(meetup: Meetup) extends Parser:
  import SpletneUriceParser.*

  private val cetZone = ZoneId.of("Europe/Ljubljana")

  private def listingURL(url: URL): URL =
    url
      .path("/wp-json/wp/v2/posts")
      .setQueryParams(
        QueryParams(
          "categories" -> talksCategory.toString,
          "per_page"   -> perPage.toString,
          "_fields"    -> "id,date,link,title,content"
        )
      )

  private def at(localDateTime: LocalDateTime): OffsetDateTime =
    OffsetDateTime.of(localDateTime, cetZone.getRules.getOffset(localDateTime))

  private def toEvent(sourceURL: URL, post: Post): Either[String, Event] = for talk <-
      talkFrom(post.contentHtml, post.published).toRight("No talk date found")
  yield Event
    .empty(
      id = s"spletne-urice-${post.id}",
      meetupID = meetup.id,
      source = SpletneUrice,
      sourceURL = sourceURL,
      title = post.title,
      startDateTime = at(talk.start)
    )
    .copy(
      description = talk.description,
      eventURL = Some(post.link),
      endDateTime = None,
      hasStartTime = true,
      hasEndTime = false,
      locationName = talk.location
    )

  // The newest `perPage` talks: one a month, so these reach back well over a year.
  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    response  <- client.requestMetered(Request.get(listingURL(url)))
    body      <- response.body.asString
    json      <- ZIO.fromEither(parseJson(body))
    posts     <- ZIO.fromEither(json.as[List[Post]])
    sourceURL <- ZIO.succeed(url.path("/predavanja/"))
    events    <- ZIO.foreach(posts)(post =>
                   toEvent(sourceURL, post) match
                     case Right(event) => ZIO.some(event)
                     case Left(reason) =>
                       logWarning(s"Skipping Spletne urice post ${post.link}: $reason").as(None)
                 )
  yield events.flatten

object SpletneUriceParser:
  import DocumentOps.urlDecoder

  private val talksCategory = 2
  private val perPage       = 20

  private val months: Map[String, Int] = Map(
    "januar"    -> 1,
    "februar"   -> 2,
    "marec"     -> 3,
    "april"     -> 4,
    "maj"       -> 5,
    "junij"     -> 6,
    "julij"     -> 7,
    "avgust"    -> 8,
    "september" -> 9,
    "oktober"   -> 10,
    "november"  -> 11,
    "december"  -> 12
  )

  // "1. december 2026 ob 19:05", "torek, 3. marec @ 19:05", "11. JANUAR @ 19:05".
  private val dateLine: Regex =
    """(?iu)(\d{1,2})\.\s*(januar|februar|marec|april|maj|junij|julij|avgust|september|oktober|november|december)\s*(\d{4})?\s*(?:ob|@)\s*(\d{1,2})[:.](\d{2})""".r

  // Some old posts were later given a copied subtitle ("3. marec 2026" on a talk published in 2022): a date with a
  // year has to fall within this window around the publish date to be believed.
  private val earliestBeforePublish = 60L
  private val latestAfterPublish    = 365L

  final case class Talk(start: LocalDateTime, location: Option[String], description: Option[String])

  /** The talk's start, place and lede from a post's content HTML, given when the post was published. */
  def talkFrom(contentHtml: String, published: LocalDateTime): Option[Talk] =
    val document = Jsoup.parseBodyFragment(contentHtml)
    val blocks   = document.select("h1, h2, h3, h4, p").asScala.toList
    val texts    = blocks.map(_.text.replace(' ', ' ').trim)

    texts.zipWithIndex.iterator
      .flatMap((text, i) => dateLine.findFirstMatchIn(text).map(m => (text, i, m)))
      .nextOption()
      .flatMap { (text, i, m) =>
        for
          month <- months.get(m.group(2).toLowerCase)
          day    = m.group(1).toInt
          time  <- Try(LocalTime.of(m.group(4).toInt, m.group(5).toInt)).toOption
          date  <- Option(m.group(3)) match
                     case Some(year) =>
                       Try(LocalDate.of(year.toInt, month, day)).toOption.filter(believable(_, published))
                     case None       => inferYear(day, month, published)
        yield
          // "date // place //" — the place is the segment after the date.
          val location    = text.split("//").lift(1).map(_.trim).filter(_.nonEmpty)
          // The first real paragraph after it: words, not the "+ + + +" divider of the old posts.
          val description = texts.drop(i + 1).find(t => t.count(_.isLetter) >= 40 && dateLine.findFirstIn(t).isEmpty)
          Talk(date.atTime(time), location, description)
      }

  private def believable(date: LocalDate, published: LocalDateTime): Boolean =
    val publishedOn = published.toLocalDate
    !date.isBefore(publishedOn.minusDays(earliestBeforePublish)) &&
    !date.isAfter(publishedOn.plusDays(latestAfterPublish))

  // Without a year: the first such date that is not well before the post was published.
  private def inferYear(day: Int, month: Int, published: LocalDateTime): Option[LocalDate] =
    val publishedOn = published.toLocalDate
    List(publishedOn.getYear, publishedOn.getYear + 1).iterator
      .flatMap(year => Try(LocalDate.of(year, month, day)).toOption)
      .find(!_.isBefore(publishedOn.minusDays(30)))

  final private[parsers] case class Post(
    id: Long,
    published: LocalDateTime,
    link: URL,
    title: String,
    contentHtml: String
  )

  private given Decoder[Post] = Decoder.instance: c =>
    for
      id        <- c.get[Long]("id")
      published <- c.get[String]("date")
                     .flatMap(raw =>
                       Try(LocalDateTime.parse(raw)).toEither.left.map(e => io.circe.DecodingFailure(e.getMessage, Nil))
                     )
      link      <- c.get[URL]("link")
      title     <- c.downField("title").get[String]("rendered")
      content   <- c.downField("content").get[String]("rendered")
    yield Post(id, published, link, Jsoup.parse(title).text.trim, content)
