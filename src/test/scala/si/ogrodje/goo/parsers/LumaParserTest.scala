package si.ogrodje.goo.parsers

import io.circe.Json
import io.circe.parser.parse as parseJson
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object LumaParserTest extends ZIOSpecDefault:
  import LumaParser.{calendarOf, idIn, slugOf}

  private val meetup   = Meetup.make("sloai", "SLO AI")
  private val calendar = "cal-n3XMAUsSIWCgyC9"

  private def entry(
    id: String,
    name: String,
    path: String,
    start: String,
    end: String,
    location: String = "offline",
    visibility: String = "public",
    geo: Json = Json.Null
  ): Json =
    Json.obj(
      "api_id" -> Json.fromString(s"calev-$id"),
      "event"  -> Json.obj(
        "api_id"           -> Json.fromString(s"evt-$id"),
        "name"             -> Json.fromString(name),
        "url"              -> Json.fromString(path),
        "start_at"         -> Json.fromString(start),
        "end_at"           -> Json.fromString(end),
        "timezone"         -> Json.fromString("Europe/Belgrade"),
        "location_type"    -> Json.fromString(location),
        "visibility"       -> Json.fromString(visibility),
        "geo_address_info" -> geo
      )
    )

  // The live SLO AI entry (trimmed), as /calendar/get-items returns it.
  private val techSales = parseJson(
    """{"api_id":"calev-F5B8Yzki8vBOqn3","event":{"api_id":"evt-wtCE4YZv1za4d5F",
      |"name":"SLOAI Tech Sales #1: How Tech Sales Merged Five Roles Into One","url":"rfb719i8",
      |"start_at":"2026-11-12T17:00:00.000Z","end_at":"2026-11-12T19:00:00.000Z","timezone":"Europe/Belgrade",
      |"location_type":"offline","visibility":"public","geo_address_info":{"address":"Celtra, razvoj informacijskih tehnologij, d.o.o.",
      |"full_address":"Celtra, razvoj informacijskih tehnologij, d.o.o., Park Zvezda, Kongresni trg 3, 1000 Ljubljana, Slovenia",
      |"city":"Ljubljana"}}}""".stripMargin
  ).toOption.get

  private val online   = entry(
    "online1",
    "SLOAI Online: Agents",
    "agents1",
    "2026-12-03T17:00:00.000Z",
    "2026-12-03T18:30:00.000Z",
    location = "online"
  )
  private val summer   =
    entry("summer1", "SLOAI Summer Meetup", "summer1", "2027-06-10T16:00:00.000Z", "2027-06-10T18:00:00.000Z")
  private val private_ = entry(
    "priv1",
    "Organizers only",
    "priv1",
    "2026-12-10T17:00:00.000Z",
    "2026-12-10T18:00:00.000Z",
    visibility = "private"
  )
  private val broken   =
    Json.obj("api_id" -> Json.fromString("calev-x"), "event" -> Json.obj("name" -> Json.fromString("No dates")))
  private val past     =
    entry("past1", "SLOAI Meetup #12", "meetup12", "2026-09-17T16:00:00.000Z", "2026-09-17T18:00:00.000Z")

  private def page(entries: List[Json], next: Option[String]): String =
    Json
      .obj(
        "entries"     -> Json.fromValues(entries),
        "has_more"    -> Json.fromBoolean(next.isDefined),
        "next_cursor" -> next.fold(Json.Null)(Json.fromString)
      )
      .noSpaces

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val q               = request.url.queryParams
        def p(name: String) = q.getAll(name).headOption
        request.url.path.toString match
          case "/url" if p("url").contains("sloai")                             =>
            Handler.fromResponse(Response.text(s"""{"kind":"calendar","data":{"calendar":{"api_id":"$calendar"}}}"""))
          case "/url" if p("url").contains("rfb719i8")                          =>
            Handler.fromResponse(Response.text(s"""{"kind":"event","data":{"calendar":{"api_id":"$calendar"}}}"""))
          case "/calendar/get-items" if p("calendar_api_id").contains(calendar) =>
            val body = (p("period"), p("pagination_cursor")) match
              case (Some("future"), None)             => page(List(techSales, online, private_), Some("cursor-2"))
              case (Some("future"), Some("cursor-2")) => page(List(summer, broken, techSales), None)
              case (Some("past"), _)                  => page(List(past), None)
              case _                                  => page(Nil, None)
            Handler.fromResponse(Response.text(body))
          case _                                                                => Handler.notFound
  } *> test

  private def events(link: String) =
    LumaParser(meetup).streamEventsFrom(URL.decode(link).toOption.get).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("LumaParserTest")(
      test("resolves a calendar's slug, reads every page of future events and the recent past; public ones once") {
        route {
          for events <- events("https://luma.com/sloai")
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "luma-evt-wtCE4YZv1za4d5F",
              "luma-evt-online1",
              "luma-evt-summer1",
              "luma-evt-past1"
            ),
            events.size == 4,
            events.forall(_.source == Source.Luma)
          )
        }
      },
      test("an event: exact start and end in Ljubljana's time, venue and address, link to its Luma page") {
        route {
          for
            events <- events("https://luma.com/sloai")
            event   = events.find(_.id == "luma-evt-wtCE4YZv1za4d5F").get
            summer  = events.find(_.id == "luma-evt-summer1").get
            online  = events.find(_.id == "luma-evt-online1").get
          yield assertTrue(
            event.title == "SLOAI Tech Sales #1: How Tech Sales Merged Five Roles Into One",
            event.startDateTime.toLocalDate == LocalDate.of(2026, 11, 12),
            event.startDateTime.toLocalTime == LocalTime.of(18, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600,
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(20, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Celtra, razvoj informacijskih tehnologij, d.o.o."),
            event.locationAddress.exists(_.contains("Kongresni trg 3, 1000 Ljubljana")),
            event.eventURL.exists(_.toString == "https://luma.com/rfb719i8"),
            event.sourceURL.toString == "https://luma.com/sloai",
            summer.startDateTime.toLocalTime == LocalTime.of(18, 0),
            summer.startDateTime.getOffset.getTotalSeconds == 7200,
            online.locationName.contains("Online"),
            online.locationAddress.isEmpty
          )
        }
      },
      test("a calendar's id in the link needs no lookup") {
        route {
          for events <- events(s"https://luma.com/calendar/$calendar")
          yield assertTrue(events.size == 4)
        }
      },
      test("a link to an event, not a calendar, yields nothing") {
        route {
          for events <- events("https://luma.com/rfb719i8")
          yield assertTrue(events.isEmpty)
        }
      } @@ TestAspect.timeout(5.minutes),
      test("links and lookups") {
        val decode = (s: String) => URL.decode(s).toOption.get
        assertTrue(
          idIn(decode(s"https://luma.com/calendar/$calendar")).contains(calendar),
          idIn(decode("https://luma.com/sloai")).isEmpty,
          slugOf(decode("https://luma.com/sloai")).contains("sloai"),
          slugOf(decode("https://lu.ma/sloai/")).contains("sloai"),
          slugOf(decode("https://luma.com/")).isEmpty,
          calendarOf(
            parseJson(s"""{"kind":"calendar","data":{"calendar":{"api_id":"$calendar"}}}""").toOption.get.hcursor
          )
            .contains(calendar),
          calendarOf(parseJson("""{"kind":"event","data":{}}""").toOption.get.hcursor).isLeft
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
