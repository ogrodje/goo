package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.Decoder
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.{fromOption, logDebug}
import zio.http.{Client, QueryParams, Request, URL}
import zio.{Clock, RIO, Scope, Task, ZIO}

import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try

/** Events of a WordPress site that announces them as ordinary posts in an events category ("dogodki"), read through the
  * REST API: the newest `maxPages` pages of the category. The post has no event fields, so when and where come from its
  * text ([[EventText]]); a post that names no date (news, results, calls) is skipped.
  *
  * A site that posts again about the same event (a changed date, a reminder) gets one event per day and title: the
  * newest post's.
  */
final case class WordPressEventsParser(
  meetup: Meetup,
  site: WordPressEventsParser.Site,
  // The day `monthsBack` counts from; today unless given (tests).
  asOf: Option[LocalDate] = None
) extends Parser:
  import WordPressEventsParser.*

  private val zone = ZoneId.of("Europe/Ljubljana")

  private def request(client: Client, url: URL): RIO[Scope, (String, Option[Int])] = for
    response <- client.requestMetered(Request.get(url))
    body     <- response.body.asString
  yield body -> response.rawHeader("X-WP-TotalPages").flatMap(_.toIntOption)

  private def categoryID(client: Client, url: URL): RIO[Scope, Long] = for
    (body, _) <-
      request(client, url.path("/wp-json/wp/v2/categories").setQueryParams(QueryParams("slug" -> site.category)))
    ids       <- ZIO.fromEither(parseJson(body).flatMap(_.as[List[Long]](using Decoder.decodeList(using idDecoder))))
    id        <- fromOption(ids.headOption).orElseFail(new RuntimeException(s"No '${site.category}' category on $url"))
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
        if page < totalPages.getOrElse(page) && page < site.maxPages then go(page + 1, all) else ZIO.succeed(all)
    yield result
    go(1, Nil)

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private def toEvent(sourceURL: URL, post: Post): Task[Option[(Post, Event)]] =
    ZIO.attempt(EventText.details(EventText.linesOf(post.contentHtml), post.published, site.sentences)).map {
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
          post -> Event
            .empty(
              id = s"${site.idPrefix}-${post.id}",
              meetupID = meetup.id,
              source = site.source,
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
    sourceURL <- ZIO.succeed(url.path(site.listingPath))
    today     <- asOf.fold(Clock.localDateTime.map(_.toLocalDate))(ZIO.succeed(_))
    events    <- ZIO.foreach(posts)(post =>
                   toEvent(sourceURL, post).tap {
                     case None => logDebug(s"${site.source} post without an event date, skipped: ${post.link}")
                     case _    => ZIO.unit
                   }
                 )
    recent     = events.flatten.filter((_, e) =>
                   site.monthsBack.forall(months => !e.startDateTime.toLocalDate.isBefore(today.minusMonths(months)))
                 )
  yield onePerEvent(recent)

object WordPressEventsParser:
  import DocumentOps.urlDecoder

  /** A site: whose events they are, its events category, the page that lists them, and how it writes them. */
  final case class Site(
    source: Source,
    idPrefix: String,
    listingPath: String,
    category: String = "dogodki",
    // Read the event from a sentence when no line is marked ("KDAJ:", "📅", …).
    sentences: Boolean = false,
    // Leave out events that ended more than this long ago (some categories go back years).
    monthsBack: Option[Long] = None,
    maxPages: Int = 2
  )

  val LUI: Site = Site(Source.LUI, "lui", "/aktualno/")

  val PomurskiTehnoloskiPark: Site =
    Site(Source.PomurskiTehnoloskiPark, "ptech", "/kategorija/dogodki/", sentences = true, monthsBack = Some(12))

  val InkubatorBelaKrajina: Site =
    Site(Source.InkubatorBelaKrajina, "belakrajina", "/category/dogodki/", monthsBack = Some(12))

  val InkubatorRogaskaSlatina: Site =
    Site(Source.InkubatorRogaskaSlatina, "inkubatorrs", "/category/dogodki/", sentences = true, monthsBack = Some(12))

  private val perPage = 20

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

  /** One event per day and title: of posts about the same event, the newest (by post id) stays. */
  private def onePerEvent(events: List[(Post, Event)]): List[Event] =
    events
      .filterNot((post, event) =>
        events.exists((other, e) =>
          other.id > post.id &&
            e.startDateTime.toLocalDate == event.startDateTime.toLocalDate &&
            EventText.similarTitles(e.title, event.title)
        )
      )
      .map(_._2)
