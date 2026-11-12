package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{Decoder, HCursor, Json}
import io.circe.parser.parse as parseJson
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.Luma
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, QueryParams, Request, URL}
import zio.{RIO, Scope, ZIO}

import java.time.{OffsetDateTime, ZoneId}
import scala.util.Try

/** Events of a Luma calendar (https://luma.com), from a meetup's `lumaUrl`: `https://luma.com/sloai` (a calendar's
  * slug) or `https://luma.com/calendar/cal-…` (its id).
  *
  * Luma's public API (the one its own pages use):
  *   - `GET api.luma.com/url?url=<slug>` resolves a slug: `{"kind": "calendar", "data": {"calendar": {"api_id": …}}}`;
  *   - `GET api.luma.com/calendar/get-items?calendar_api_id=…&period=future|past` lists the calendar's events, in pages
  *     (`has_more`, `next_cursor`).
  *
  * An event has exact start and end instants and a venue (`geo_address_info`) or is online. Only public events are
  * kept. The event's page is `luma.com/<url>`.
  */
final case class LumaParser(meetup: Meetup) extends Parser:
  import LumaParser.*

  private def getJson(client: Client, url: URL): RIO[Scope, Json] = for
    response <- client.requestMetered(Request.get(url))
    _        <- ZIO.unless(response.status.isSuccess)(ZIO.fail(new RuntimeException(s"Luma $url: ${response.status}")))
    body     <- response.body.asString
    json     <- ZIO.fromEither(parseJson(body))
  yield json

  private def calendarID(client: Client, lumaUrl: URL): RIO[Scope, String] =
    idIn(lumaUrl) match
      case Some(id) => ZIO.succeed(id)
      case None     =>
        for
          slug <- fromOption(slugOf(lumaUrl)).orElseFail(new RuntimeException(s"No Luma calendar in $lumaUrl"))
          json <- getJson(client, api.path("/url").setQueryParams(QueryParams("url" -> slug)))
          id   <- ZIO.fromEither(calendarOf(json.hcursor)).mapError(new RuntimeException(_))
        yield id

  private def items(client: Client, calendar: String, period: String, maxPages: Int): RIO[Scope, List[Json]] =
    def go(page: Int, cursor: Option[String], acc: List[Json]): RIO[Scope, List[Json]] =
      val params = QueryParams(
        "calendar_api_id"  -> calendar,
        "period"           -> period,
        "pagination_limit" -> pageSize.toString
      ) ++ cursor.fold(QueryParams.empty)(c => QueryParams("pagination_cursor" -> c))
      for
        json    <- getJson(client, api.path("/calendar/get-items").setQueryParams(params))
        c        = json.hcursor
        entries <- ZIO.fromEither(c.get[List[Json]]("entries"))
        all      = acc ++ entries
        next     = c.get[Option[String]]("next_cursor").toOption.flatten
        hasMore  = c.get[Boolean]("has_more").getOrElse(false)
        result  <- if hasMore && next.isDefined && page < maxPages then go(page + 1, next, all) else ZIO.succeed(all)
      yield result
    go(1, None, Nil)

  private def toEvent(sourceURL: URL)(item: Item): Event =
    Event
      .empty(
        id = s"luma-${item.id}",
        meetupID = meetup.id,
        source = Luma,
        sourceURL = sourceURL,
        title = item.name,
        startDateTime = item.start
      )
      .copy(
        eventURL = Some(site.path(s"/${item.path}")),
        endDateTime = item.end.filter(_.isAfter(item.start)),
        hasStartTime = true,
        hasEndTime = item.end.exists(_.isAfter(item.start)),
        locationName = item.place,
        locationAddress = item.address
      )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    calendar <- calendarID(client, url)
    future   <- items(client, calendar, "future", maxPages = 4)
    // Recent past events too, as other sources keep them; one page is plenty for a meetup.
    past     <- items(client, calendar, "past", maxPages = 1)
    decoded  <- ZIO.foreach(future ++ past)(json =>
                  json.as[Item] match
                    case Right(item) => ZIO.some(item)
                    case Left(err)   => logWarning(s"Skipping Luma entry in $calendar: ${err.getMessage}").as(None)
                )
  yield decoded.flatten
    .filter(_.public)
    .distinctBy(_.id)
    .map(toEvent(url))

object LumaParser:
  private val api      = URL.decode("https://api.luma.com").toOption.get
  private val site     = URL.decode("https://luma.com").toOption.get
  private val zone     = ZoneId.of("Europe/Ljubljana")
  private val pageSize = 50

  private val calendarIdPattern = """^cal-[A-Za-z0-9]+$""".r

  /** `luma.com/calendar/cal-…` or `luma.com/cal-…`: the calendar's id is in the link. */
  def idIn(url: URL): Option[String] =
    url.path.segments.find(s => calendarIdPattern.matches(s))

  /** `luma.com/sloai` -> "sloai". */
  def slugOf(url: URL): Option[String] =
    url.path.segments.filter(_.nonEmpty).toList match
      case slug :: Nil => Some(slug)
      case _           => None

  def calendarOf(c: HCursor): Either[String, String] =
    c.get[String]("kind").left.map(_ => "Not a Luma link").flatMap {
      case "calendar" =>
        c.downField("data").downField("calendar").get[String]("api_id").left.map(_ => "No calendar id")
      case other      => Left(s"The Luma link is a(n) $other, not a calendar")
    }

  final private[parsers] case class Item(
    id: String,
    name: String,
    path: String,
    start: OffsetDateTime,
    end: Option[OffsetDateTime],
    public: Boolean,
    place: Option[String],
    address: Option[String]
  )

  private def local(raw: String): Either[String, OffsetDateTime] =
    Try(OffsetDateTime.parse(raw).atZoneSameInstant(zone).toOffsetDateTime).toEither.left.map(_.getMessage)

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)

  given Decoder[Item] = Decoder.instance: entry =>
    val e = entry.downField("event")
    for
      id         <- e.get[String]("api_id")
      name       <- e.get[String]("name")
      path       <- e.get[String]("url")
      rawStart   <- e.get[String]("start_at")
      start      <- local(rawStart).left.map(io.circe.DecodingFailure(_, e.history))
      rawEnd     <- e.get[Option[String]]("end_at")
      end        <- rawEnd.fold(Right(None))(local(_).map(Some(_))).left.map(io.circe.DecodingFailure(_, e.history))
      visibility <- e.get[Option[String]]("visibility")
      location   <- e.get[Option[String]]("location_type")
      geo         = e.downField("geo_address_info")
      venue      <- geo.get[Option[String]]("address").orElse(Right(None))
      full       <- geo.get[Option[String]]("full_address").orElse(Right(None))
      online      = location.exists(t => t == "online" || t == "zoom" || t == "virtual")
    yield Item(
      id = id,
      name = name.trim,
      path = path,
      start = start,
      end = end,
      public = visibility.forall(_ == "public"),
      place = nonEmpty(venue).orElse(Option.when(online)("Online")),
      address = nonEmpty(full).filterNot(f => nonEmpty(venue).contains(f))
    )
