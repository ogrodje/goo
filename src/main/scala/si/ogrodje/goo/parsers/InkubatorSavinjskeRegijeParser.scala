package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.InkubatorSavinjskeRegije
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, Task, ZIO}

import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.matching.Regex

/** Events of Inkubator Savinjske regije (https://www.inkubatorsr.si/aktualno/dogodki/).
  *
  * The site is WordPress with an Elementor theme. Events are the `dogodki` post type, listed through the WordPress REST
  * API (`/wp-json/wp/v2/dogodki`) which gives the title, link and post content. The API's `date` is when the post was
  * published, not when the event happens, so the event date and location are read from each detail page. The time is
  * only ever written in the post content (e.g. "📅 26. november 2026 | 🕘 9.00 – 10.30 | 📍 ..."), so it is optional.
  */
final case class InkubatorSavinjskeRegijeParser(meetup: Meetup) extends Parser:
  import DocumentOps.*
  import InkubatorSavinjskeRegijeParser.*

  private val cetZone = ZoneId.of("Europe/Ljubljana")

  private def listingURL(url: URL, page: Int): URL =
    url
      .path("/wp-json/wp/v2/dogodki")
      .setQueryParams(
        QueryParams(
          "per_page" -> perPage.toString,
          "page"     -> page.toString,
          "_fields"  -> "id,link,title,content"
        )
      )

  // Newest published first. Bounded to `maxPages`: older posts are long past and re-reading their detail pages on
  // every sync would only add load on their side. WordPress answers a page past the end with a 400, hence the header.
  private def fetchPosts(client: Client, url: URL): RIO[Scope, List[Post]] =
    def go(page: Int, acc: List[Post]): RIO[Scope, List[Post]] = for
      response  <- client.requestMetered(Request.get(listingURL(url, page)))
      body      <- response.body.asString
      json      <- ZIO.fromEither(parseJson(body))
      posts     <- ZIO.fromEither(json.as[List[Post]])
      totalPages = response.rawHeader("X-WP-TotalPages").flatMap(_.toIntOption).getOrElse(page)
      all        = acc ++ posts
      result    <- if page < totalPages && page < maxPages then go(page + 1, all) else ZIO.succeed(all)
    yield result
    go(1, Nil)

  // Elementor renders every labelled field of the event template as a `.date` text widget, in order:
  // [..., <date>, "Lokacija dogodka", <location>, ...]. The site's mega menu also shows the newest event's date
  // with the same markup on every page, so the date is the one right before the location label, not the first.
  private def detailFields(document: Document): Task[(LocalDate, Option[String])] = for
    widgets  <- document.query(".elementor-widget.date").map(_.map(widgetText))
    labelAt   = widgets.indexWhere(_.equalsIgnoreCase(locationLabel))
    _        <- ZIO.when(labelAt < 0)(ZIO.fail(new RuntimeException(s"No '$locationLabel' field found")))
    rawDate  <- fromOption(widgets.take(labelAt).findLast(date.matches))
                  .orElseFail(new RuntimeException("No event date found"))
    localDate = rawDate match
                  case date(d, m, y) => LocalDate.of(y.toInt, m.toInt, d.toInt)
    location  = widgets.lift(labelAt + 1).filter(_.nonEmpty)
  yield localDate -> location

  private def at(localDate: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = localDate.atTime(time)
    OffsetDateTime.of(localDateTime, cetZone.getRules.getOffset(localDateTime))

  private def parseDetail(client: Client, sourceURL: URL, post: Post): RIO[Scope, Event] = for
    document              <- client.requestMetered(Request.get(post.link)).flatMap(_.body.asDocument)
    (localDate, location) <- detailFields(document)
    description           <- document
                               .queryFirst("meta[name=description]")
                               .map(_.map(_.attr("content").trim).filter(_.nonEmpty))
    times                  = timesFrom(post.contentText)
  yield Event
    .empty(
      id = s"inkubatorsr-${post.id}",
      meetupID = meetup.id,
      source = InkubatorSavinjskeRegije,
      sourceURL = sourceURL,
      title = post.title,
      startDateTime = at(localDate, times.map(_._1).getOrElse(LocalTime.MIDNIGHT))
    )
    .copy(
      description = description,
      eventURL = Some(post.link),
      endDateTime = times.flatMap(_._2).map(at(localDate, _)),
      hasStartTime = times.isDefined,
      hasEndTime = times.exists(_._2.isDefined),
      locationName = location
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    posts     <- fetchPosts(client, url)
    sourceURL <- ZIO.succeed(url.path("/aktualno/dogodki/"))
    events    <- ZIO
                   .foreachPar(posts)(post =>
                     parseDetail(client, sourceURL, post).asSome
                       .catchAll(err => logWarning(s"Failed to parse ISR event ${post.link}: ${err.getMessage}").as(None))
                   )
                   .withParallelism(detailParallelism)
  yield events.flatten

object InkubatorSavinjskeRegijeParser:
  import DocumentOps.urlDecoder

  private val perPage           = 20
  private val maxPages          = 2
  private val detailParallelism = 4
  private val locationLabel     = "Lokacija dogodka"

  // "26. 11. 2026"
  private val date: Regex = """(\d{1,2})\.\s*(\d{1,2})\.\s*(\d{4})""".r

  // A time is only trusted after a marker the site uses for it: a clock emoji (🕐–🕧, ⏰), "ob 16.00", "od 9.00 do",
  // "Ura:", "Time:", "at 11:30" or "(9:00 – 10:30)". The negative lookahead keeps dates such as "16.03.2026" out,
  // while still allowing a sentence-final period ("ob 9.00.").
  private val time: Regex =
    """(?iu)(?:[\x{1F550}-\x{1F567}\x{23F0}]\x{FE0F}?|\bob|\bod|\bura:|\btime:|\bat|\()\s*(\d{1,2})[.:](\d{2})(?!\d|\.\d)(?:\s*(?:h|uri?)?\s*(?:[–—-]|do)\s*(\d{1,2})[.:](\d{2})(?!\d|\.\d))?""".r

  private def localTime(h: String, m: String): Option[LocalTime] =
    (h.toIntOption, m.toIntOption) match
      case (Some(hh), Some(mm)) if hh < 24 && mm < 60 => Some(LocalTime.of(hh, mm))
      case _                                          => None

  /** Start and (optional) end time from the first time-like marker in the post content. */
  def timesFrom(text: String): Option[(LocalTime, Option[LocalTime])] =
    time
      .findAllMatchIn(text)
      .flatMap(m => localTime(m.group(1), m.group(2)).map(_ -> Option(m.group(3)).flatMap(localTime(_, m.group(4)))))
      .nextOption()

  private def widgetText(element: Element): String = element.text.replace(' ', ' ').trim

  final private[parsers] case class Post(id: Long, link: URL, title: String, contentText: String)

  private given Decoder[Post] = Decoder.instance: c =>
    for
      id      <- c.get[Long]("id")
      link    <- c.get[URL]("link")
      title   <- c.downField("title").get[String]("rendered")
      content <- c.downField("content").get[String]("rendered")
    yield Post(id, link, Jsoup.parse(title).text.trim, Jsoup.parse(content).text.replace('\u00a0', ' '))
