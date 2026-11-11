package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime}

object SpletneUriceParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("spletne-urice", "Spletne Urice Maribor")
  private val host   = "https://wwwhmb.si"

  // Since 2026: a subtitle paragraph with the year.
  private def newFormat(when: String, lede: String): String =
    s"""<div class="wp-block-group is-content-justification-center is-nowrap is-layout-flex">
       |<p class="has-text-align-center is-style-text-subtitle is-style-text-subtitle--1 wp-block-paragraph" style="font-weight:700">$when</p>
       |<p class="has-text-align-center wp-block-paragraph">(<a href="https://www.google.com/maps/dir//Vetrinjc+BarCoda">Kam?</a>)</p>
       |</div>
       |<p class="is-style-default has-x-large-font-size wp-block-paragraph"><em>$lede</em></p>
       |<p class="wp-block-paragraph">Pri programiranju ima AI jasno povratno informacijo.</p>""".stripMargin

  // Before 2026: a heading without the year, then a "+ + +" divider.
  private def oldFormat(when: String, place: String, lede: String): String =
    s"""<h2 class="wp-block-heading has-accent-color has-text-color">$when <br><strong>// $place //</strong></h2>
       |<p class="has-text-align-left wp-block-paragraph">+ + + + + + + + + + + + + + + + + + + + + + + + + + + + + + + +</p>
       |<div style="height:16px" aria-hidden="true" class="wp-block-spacer"></div>
       |<p class="is-style-default wp-block-paragraph"><strong>$lede</strong></p>""".stripMargin

  private def post(id: Long, date: String, slug: String, title: String, content: String): Json =
    Json.obj(
      "id"      -> Json.fromLong(id),
      "date"    -> Json.fromString(date),
      "link"    -> Json.fromString(s"$host/$slug/"),
      "title"   -> Json.obj("rendered" -> Json.fromString(title)),
      "content" -> Json.obj("rendered" -> Json.fromString(content))
    )

  private val lede = "Programirati z AI zna že skoraj vsak. A kaj, ko je treba z njim voditi še podjetje?"

  private val posts: List[Json] = List(
    post(
      2432,
      "2026-11-11T14:17:05",
      "176-firma-na-avtopilotu",
      "#176: Firma na avtopilotu?",
      newFormat("1. december 2026 ob 19:05 // BarCoda, Vetrinjc", lede)
    ),
    post(
      2398,
      "2026-05-25T11:53:35",
      "173-skletne-urice",
      "#173: S&#8217;kletne Urice!",
      newFormat("2. junij 2026 ob 19:05 // KGB, Maribor", "Poletni zaključek sezone v kletnih prostorih kluba KGB.")
    ),
    post(
      2052,
      "2026-03-03T19:05:23",
      "170-od-maribora-do-orbite",
      "#170: Od Maribora do orbite",
      oldFormat(
        "torek, 3. marec @ 19:05",
        "Vetrinjc &#8211; BarCoda, Mb",
        "Razvoj programske opreme ni več samo zemeljski. Inženirji pišejo kodo, ki zakroži okoli Zemlje."
      )
    ),
    // Published in November, the talk is in December of the same year.
    post(
      1900,
      "2025-11-24T10:00:00",
      "167-pure",
      "#167: Pure: od prvega swipa",
      oldFormat(
        "torek, 2. december @ 19:05",
        "Vetrinjc &#8211; BarCoda, Mb",
        "Kako je nastala aplikacija in kaj smo se pri tem naučili."
      )
    ),
    // Published in late December, the talk is in January of the next year.
    post(
      1880,
      "2024-12-20T09:00:00",
      "159-rootkiti",
      "#159: Rootkiti",
      oldFormat(
        "torek, 7. januar @ 19:05",
        "Pisarna, Mb",
        "Kako globoko v sistem seže napadalec in kako ga od tam spravimo ven."
      )
    ),
    // An old post that later got a copied 2026 subtitle: not believable, skipped.
    post(
      900,
      "2022-02-15T10:00:00",
      "137-zero-day-design",
      "#137: Zero Day Design",
      newFormat(
        "3. marec 2026 ob 19:05 // BarCoda, Vetrinjc",
        "Startup playbook za oblikovalce, ki gradijo produkte od nule."
      )
    ),
    // No date line at all: skipped.
    post(
      880,
      "2022-04-05T10:00:00",
      "wwwhmb-retrospektiva",
      "#wwwhmb RETROspektiva",
      "<p>V duhu zaključevanja kvartalov.</p>"
    )
  )

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val responseZIO =
          if request.url.path.toString == "/wp-json/wp/v2/posts" &&
            request.url.queryParams.getAll("categories").toList == List("2")
          then ZIO.succeed(Response.text(Json.fromValues(posts).noSpaces))
          else ZIO.fail(new Exception(s"No response for ${request.url}"))
        Handler.fromZIO(responseZIO).catchAll(_ => Handler.notFound)
  } *> test

  private val homepage = URL.decode(host).toOption.get

  private def events = SpletneUriceParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("SpletneUriceParserTest")(
      test("reads talks from the predavanja category, skipping posts without a believable date") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "spletne-urice-2432",
              "spletne-urice-2398",
              "spletne-urice-2052",
              "spletne-urice-1900",
              "spletne-urice-1880"
            ),
            events.forall(_.source == Source.SpletneUrice),
            events.forall(_.hasStartTime),
            events.forall(e => !e.hasEndTime && e.endDateTime.isEmpty)
          )
        }
      },
      test("reads the new format: date with year, time, place and lede") {
        route {
          for
            events <- events
            event   = events.find(_.id == "spletne-urice-2432").get
          yield assertTrue(
            event.title == "#176: Firma na avtopilotu?",
            event.startDateTime.toLocalDate == LocalDate.of(2026, 12, 1),
            event.startDateTime.toLocalTime == LocalTime.of(19, 5),
            event.startDateTime.getOffset.getTotalSeconds == 3600, // CET
            event.locationName.contains("BarCoda, Vetrinjc"),
            event.description.contains(lede),
            event.eventURL.exists(_.toString == s"$host/176-firma-na-avtopilotu/"),
            event.sourceURL.path.toString == "/predavanja/"
          )
        }
      },
      test("reads the old format, in summer time, and decodes the title") {
        route {
          for
            events <- events
            summer  = events.find(_.id == "spletne-urice-2398").get
            old     = events.find(_.id == "spletne-urice-2052").get
          yield assertTrue(
            summer.title == "#173: S’kletne Urice!",
            summer.startDateTime.getOffset.getTotalSeconds == 7200, // CEST
            summer.locationName.contains("KGB, Maribor"),
            old.startDateTime.toLocalDate == LocalDate.of(2026, 3, 3),
            old.locationName.contains("Vetrinjc – BarCoda, Mb"),
            old.description.exists(_.startsWith("Razvoj programske opreme"))
          )
        }
      },
      test("infers the missing year from the publish date, across New Year") {
        route {
          for events <- events
          yield assertTrue(
            events.find(_.id == "spletne-urice-1900").exists(_.startDateTime.toLocalDate == LocalDate.of(2025, 12, 2)),
            events.find(_.id == "spletne-urice-1880").exists(_.startDateTime.toLocalDate == LocalDate.of(2025, 1, 7))
          )
        }
      },
      test("reads the date line in the forms the site has used") {
        import SpletneUriceParser.talkFrom
        val published = LocalDateTime.of(2021, 5, 4, 10, 0)
        assertTrue(
          talkFrom("<p>11. maj @ 18:30 izjemoma!! //ONLINE// Vsak dan proizvedemo več vsebine.</p>", published)
            .exists(t => t.start == LocalDateTime.of(2021, 5, 11, 18, 30) && t.location.contains("ONLINE")),
          talkFrom("<p>11. JANUAR @ 19:05 //ONLINE//</p>", LocalDateTime.of(2022, 1, 5, 10, 0))
            .exists(_.start == LocalDateTime.of(2022, 1, 11, 19, 5)),
          talkFrom("<p>torek , 10. oktober @ 19:05 // Pisarna, Mb //</p>", LocalDateTime.of(2023, 10, 2, 10, 0))
            .exists(t => t.start.toLocalDate == LocalDate.of(2023, 10, 10) && t.location.contains("Pisarna, Mb")),
          talkFrom("<p>Prijave do 15. maja.</p>", published).isEmpty,
          talkFrom("<p>30. februar 2026 ob 19:05 // BarCoda</p>", LocalDateTime.of(2026, 2, 1, 10, 0)).isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
