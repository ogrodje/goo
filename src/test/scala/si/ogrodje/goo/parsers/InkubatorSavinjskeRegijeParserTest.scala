package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.LocalTime

object InkubatorSavinjskeRegijeParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("inkubatorsr", "Inkubator Savinjske regije")
  private val host   = "https://www.inkubatorsr.si"

  // Mirrors the real Elementor markup: every labelled field is a `.date` text widget. The mega menu shows the
  // newest event's date on every page with the same markup, so it must not be mistaken for this event's date.
  private def detailHtml(date: String, location: String, description: String): String =
    s"""<!DOCTYPE html><html><head>
       |<meta name="description" content="$description" />
       |</head><body>
       |<nav>
       |  <div class="elementor-element elementor-element-77e636b date elementor-widget elementor-widget-text-editor">
       |    <div class="elementor-widget-container">31. 12. 2099</div>
       |  </div>
       |</nav>
       |<main>
       |  <div class="elementor-element elementor-element-c1221f5 date elementor-widget elementor-widget-text-editor">
       |    <div class="elementor-widget-container">$date</div>
       |  </div>
       |  <div class="e-con-full e-flex e-con e-child">
       |    <div class="elementor-element elementor-element-296d1e8 date elementor-widget elementor-widget-text-editor">
       |      <div class="elementor-widget-container"><p>Lokacija dogodka</p></div>
       |    </div>
       |    <div class="elementor-element elementor-element-e81a0fd date elementor-widget elementor-widget-text-editor">
       |      <div class="elementor-widget-container">$location</div>
       |    </div>
       |  </div>
       |</main>
       |</body></html>""".stripMargin

  private val details: Map[String, String] = Map(
    "klub-ai"          -> detailHtml("26. 11. 2026", "Inkubator Savinjske regije", "Mesečna srečanja o AI."),
    "pitchaj"          -> detailHtml("12. 11. 2026", "OOZ Celje, Cesta na Ostrožno 4, Celje", "Pitch delavnica."),
    "brez-ure"         -> detailHtml("05. 11. 2026", "Inkubator Savinjske regije", "Dogodek brez ure."),
    // No "Lokacija dogodka" block at all: skipped, the rest still come through.
    "pokvarjen"        -> "<html><body><h1>Nič</h1></body></html>",
    "na-tretji-strani" -> detailHtml("01. 01. 2024", "Inkubator Savinjske regije", "Stran 3.")
  )

  private def post(id: Long, slug: String, title: String, content: String): Json =
    Json.obj(
      "id"      -> Json.fromLong(id),
      "link"    -> Json.fromString(s"$host/dogodki/$slug/"),
      "title"   -> Json.obj("rendered" -> Json.fromString(title)),
      "content" -> Json.obj("rendered" -> Json.fromString(content))
    )

  // Three pages exist, but the parser is bounded to the two newest.
  private val pages: Map[String, List[Json]] = Map(
    "1" -> List(
      post(
        5112,
        "klub-ai",
        "KLUB AI NAVDU&#352;ENCEV: Od AI pomo&#269;nikov do avtonomnih agentov",
        "<p><strong> 📅 26. november 2026 | 🕘 9.00 &#8211; 10.30 | 📍 Inkubator Savinjske regije</strong></p>"
      ),
      post(5104, "pitchaj", "Pitchaj prepričljivo", "<p>📅 12. november 2026 | 🕓 16.00–19.00 | 📍 OOZ Celje</p>")
    ),
    "2" -> List(
      post(5090, "brez-ure", "Dogodek brez ure", "<p>Ideja lahko zveni odlično. Ampak ali se številke izidejo?</p>"),
      post(5080, "pokvarjen", "Pokvarjen dogodek", "<p>📅 1. november 2026</p>")
    ),
    "3" -> List(post(1000, "na-tretji-strani", "Star dogodek", "<p>ob 10.00</p>"))
  )

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val path        = request.url.path.toString
        val responseZIO =
          if path == "/wp-json/wp/v2/dogodki" then
            val page = request.url.queryParams.getAll("page").headOption.getOrElse("1")
            ZIO
              .fromOption(pages.get(page))
              .map(posts =>
                Response
                  .text(Json.fromValues(posts).noSpaces)
                  .addHeader("X-WP-TotalPages", pages.size.toString)
              )
              .orElseFail(new Exception(s"No page $page"))
          else
            ZIO
              .fromOption(details.get(path.stripPrefix("/dogodki/").stripSuffix("/")))
              .map(Response.text(_))
              .orElseFail(new Exception(s"No response for $path"))
        Handler.fromZIO(responseZIO).catchAll(_ => Handler.notFound)
  } *> test

  // The meetup's homepage in Hygraph has no "www".
  private val homepage = URL.decode("https://inkubatorsr.si").toOption.get

  private def events = InkubatorSavinjskeRegijeParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("InkubatorSavinjskeRegijeParserTest")(
      test("collects events from the two newest API pages and skips broken detail pages") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.title).toSet == Set(
              "KLUB AI NAVDUŠENCEV: Od AI pomočnikov do avtonomnih agentov",
              "Pitchaj prepričljivo",
              "Dogodek brez ure"
            ),
            events.forall(_.source == Source.InkubatorSavinjskeRegije)
          )
        }
      },
      test("reads the event's own date (not the menu's), time range and location") {
        route {
          for
            events <- events
            event   = events.find(_.title.startsWith("KLUB AI")).get
          yield assertTrue(
            event.startDateTime.toLocalDate.toString == "2026-11-26",
            event.startDateTime.toLocalTime == LocalTime.of(9, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600, // CET
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(10, 30)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Inkubator Savinjske regije"),
            event.description.contains("Mesečna srečanja o AI."),
            event.eventURL.exists(_.toString == s"$host/dogodki/klub-ai/"),
            event.sourceURL.path.toString == "/aktualno/dogodki/"
          )
        }
      },
      test("keeps a location other than the incubator") {
        route {
          for events <- events
          yield assertTrue(
            events
              .find(_.title == "Pitchaj prepričljivo")
              .exists(e => e.locationName.contains("OOZ Celje, Cesta na Ostrožno 4, Celje") && e.hasEndTime)
          )
        }
      },
      test("marks events without a time as all-day") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Dogodek brez ure").get
          yield assertTrue(
            !event.hasStartTime,
            !event.hasEndTime,
            event.endDateTime.isEmpty,
            event.startDateTime.toLocalDate.toString == "2026-11-05"
          )
        }
      },
      test("uses stable ids from the WordPress post id") {
        route {
          for events <- events
          yield assertTrue(events.map(_.id).toSet == Set("inkubatorsr-5112", "inkubatorsr-5104", "inkubatorsr-5090"))
        }
      },
      test("reads times in the formats the site uses, and never a date as a time") {
        import InkubatorSavinjskeRegijeParser.timesFrom
        def t(h: Int, m: Int) = LocalTime.of(h, m)
        assertTrue(
          timesFrom("📅 26. november 2026 | 🕘 9.00 – 10.30 | 📍 Inkubator").contains(t(9, 0)    -> Some(t(10, 30))),
          timesFrom("📅 18. 2. 2026 | 🕘 14.00 | 📍 Inkubator").contains(t(14, 0)                -> None),
          timesFrom("📅 Sreda, 10. september 2025 ⏰ 10.00–13.00 📍 Celje").contains(t(10, 0)     -> Some(t(13, 0))),
          timesFrom("Termin: 9. oktober, ob 11:00 Cena: brezplačno").contains(t(11, 0)           -> None),
          timesFrom("četrtek, 11. december 2025, od 14.00 do 17.00 ure").contains(t(14, 0)       -> Some(t(17, 0))),
          timesFrom("📅 24. avgust 2026 (9:00 – 10:30): 1. srečanje").contains(t(9, 0)           -> Some(t(10, 30))),
          timesFrom("📅 May 27, 2026, at 11:30 📍 Celje").contains(t(11, 30)                     -> None),
          timesFrom("Date: 03.12.2025 Time: 10:00–15:30 Venue: Celje").contains(t(10, 0)         -> Some(t(15, 30))),
          timesFrom("Datumi srečanj: 16.03.2026, 17.03.2026 – od 10.00 – 14.00 ure").contains(
            t(10, 0)                                                                             -> Some(t(14, 0))
          ),
          timesFrom("ki bo v sredo, 16. julija 2025 ob 9.00. Kaj boš pridobil").contains(t(9, 0) -> None),
          timesFrom("Ideja lahko zveni odlično. 24.9.2025").isEmpty,
          timesFrom("od 16.03.2026 naprej").isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
