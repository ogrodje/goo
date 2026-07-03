package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

object KCUIParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("kcui", "KCUI")

  // A detail page mirrors the real KCUI markup: an <h1> title, a lede <p>, and a
  // "Kdaj"/"Kje" info block where each label <span> is immediately followed by its value <span>.
  private def detailHtml(
    title: String,
    lede: String,
    when: String,
    where: String
  ): String =
    s"""<!DOCTYPE html><html><body>
       |<h1 class="text-3xl font-bold">$title</h1>
       |<p class="text-xl text-muted">$lede</p>
       |<div class="bg-surface rounded-lg border p-6">
       |  <div class="flex items-start gap-4">
       |    <span class="block text-sm font-semibold uppercase tracking-wider">Kdaj</span>
       |    <span class="text-base text-muted">$when</span>
       |  </div>
       |  <div class="flex items-start gap-4">
       |    <span class="block text-sm font-semibold uppercase tracking-wider">Kje</span>
       |    <span class="text-base text-muted">$where</span>
       |  </div>
       |</div>
       |</body></html>""".stripMargin

  // Each detail page keyed by its slug (the path segment under /izobrazevanja/).
  private val details: Map[String, String] = Map(
    "agentna-ui"    -> detailHtml(
      "Agentna umetna inteligenca: od pričakovanj do resničnih učinkov",
      "Spoznajte prehod od pasivnih digitalnih orodij k avtonomnim AI agentom.",
      "Petek, 17. april 2026 ob 10:00",
      "Spletni dogodek"
    ),
    "inferencna-ui" -> detailHtml(
      "Inferenčna UI infrastruktura za rast",
      "Posvet za oblikovanje arhitekture slovenske inferenčne UI infrastrukture.",
      "Sreda, 10. junij 2026 ob 10:00",
      "GZS, dvorana G, Dimičeva ulica 13, Ljubljana"
    ),
    // Date only, no time — hasStartTime must be false.
    "brez-ure"      -> detailHtml(
      "Dogodek brez ure",
      "Ta dogodek nima navedene ure.",
      "Torek, 2. junij 2026",
      "Zoom dogodek"
    )
  )

  private val listingHtml: String =
    s"""<!DOCTYPE html><html><body>
       |<section>
       |  <h2>Aktualna izobraževanja</h2>
       |  <a class="group" href="/izobrazevanja/agentna-ui">Agentna UI</a>
       |  <a class="group" href="/izobrazevanja/brez-ure">Brez ure</a>
       |</section>
       |<section>
       |  <h2>Pretekla izobraževanja (1)</h2>
       |  <a class="group" href="/izobrazevanja/inferencna-ui">Inferenčna UI</a>
       |</section>
       |<footer>
       |  <a href="/izobrazevanja">Vsa izobraževanja</a>
       |  <a href="/o-nas">O nas</a>
       |  <a href="https://kcui.si/izobrazevanja/agentna-ui">Duplicate absolute link</a>
       |</footer>
       |</body></html>""".stripMargin

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val path        = request.url.path.toString
        val responseZIO =
          if path == "/izobrazevanja" then ZIO.succeed(Response.text(listingHtml))
          else
            ZIO
              .fromOption(details.get(path.stripPrefix("/izobrazevanja/")))
              .map(Response.text(_))
              .orElseFail(new Exception(s"No response for $path"))
        Handler.fromZIO(responseZIO).catchAll(_ => Handler.notFound)
  } *> test

  private val homepage = URL.decode("https://kcui.si").toOption.get

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("KCUIParserTest")(
      test("collects both current and past events from the listing") {
        route {
          for events <- KCUIParser(meetup).streamEventsFrom(homepage).runCollect
          yield assertTrue(
            events.size == 3,
            events.exists(_.title.startsWith("Agentna umetna inteligenca")),
            events.exists(_.title.startsWith("Inferenčna UI infrastruktura")),
            events.forall(_.source == Source.KCUI)
          )
        }
      },
      test("parses date, time and location from a detail page") {
        route {
          for
            events <- KCUIParser(meetup).streamEventsFrom(homepage).runCollect
            event   = events.find(_.title.startsWith("Agentna")).get
          yield assertTrue(
            event.startDateTime.getYear == 2026,
            event.startDateTime.getMonthValue == 4,
            event.startDateTime.getDayOfMonth == 17,
            event.startDateTime.getHour == 10,
            event.hasStartTime,
            event.locationName.contains("Spletni dogodek"),
            event.eventURL.exists(_.path.toString == "/izobrazevanja/agentna-ui"),
            event.description.exists(_.contains("avtonomnim AI agentom"))
          )
        }
      },
      test("marks events without a time as all-day") {
        route {
          for
            events <- KCUIParser(meetup).streamEventsFrom(homepage).runCollect
            event   = events.find(_.title == "Dogodek brez ure").get
          yield assertTrue(
            !event.hasStartTime,
            event.startDateTime.getMonthValue == 6,
            event.startDateTime.getDayOfMonth == 2
          )
        }
      },
      test("generates stable, source-prefixed ids") {
        route {
          for events <- KCUIParser(meetup).streamEventsFrom(homepage).runCollect
          yield assertTrue(
            events.forall(_.id.startsWith("kcui-")),
            events.map(_.id).toSet.size == events.size
          )
        }
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
