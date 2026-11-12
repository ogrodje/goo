package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime, ZoneId}

object DataScienceParserTest extends ZIOSpecDefault:
  import DataScienceParser.{sameEvent, timesOf, Item}
  import FRIParser.{dateOf, DayMonth}

  private val zone  = ZoneId.of("Europe/Ljubljana")
  private val today = LocalDate.now(zone)

  private def item(
    iso: LocalDate,
    kind: String,
    title: String,
    url: String,
    time: String = "",
    meta: String = "",
    excerpt: String = ""
  ): Json =
    Json.obj(
      "iso"     -> Json.fromString(iso.toString),
      "date"    -> Json.fromString(s"${iso}T00:00:00Z"),
      "kind"    -> Json.fromString(kind),
      "title"   -> Json.fromString(title),
      "url"     -> Json.fromString(url),
      "time"    -> Json.fromString(time),
      "meta"    -> Json.fromString(meta),
      "excerpt" -> Json.fromString(excerpt),
      "hasBody" -> Json.fromBoolean(excerpt.nonEmpty)
    )

  // FRI's calendar has no year; the day FRIParser gives "21. okt" is the one DataScience must list it on.
  private val careerDay = dateOf(DayMonth("21.", "okt"), today).get

  // Items as the events page embeds them (texts copied from the live page; dates relative to today).
  private val items: List[Json] = List(
    item(
      today.plusDays(14),
      "workshop",
      "Workshop: Pair programming with AI",
      "/news/workshop_2026-11-26/",
      time = "17:00",
      meta = "17:00 · FRI, Lecture room 3",
      excerpt = "When? Thursday, 26 November 2026, 17:00–20:00.\nWhere? Faculty of Computer and Information Science, " +
        "Večna pot 113, Ljubljana. Lecture room 3.\nWhat?\nGet into the flow of AI-assisted …"
    ),
    item(
      today.plusDays(2),
      "meetup",
      "Data Science Talks",
      "/news/datasciencetalks001/",
      meta = "Teads Ljubljana, Dunajska cesta 5, 1000 Ljubljana",
      excerpt = "The Data Science Talks are a recurring event of the Data Science Initiative …"
    ),
    item(
      today.minusDays(30),
      "meetup",
      "Spark sessions 005",
      "/news/spark005/",
      meta = "FRI, Večna pot 113, Ljubljana",
      excerpt =
        "When? November 18 2025 at 18:00.\nWhere? Faculty of Computer and Information Science, Večna pot 113, " +
          "Ljubljana.\nWhat?\nA new and improved traffic model for Slovenia …"
    ),
    item(
      today.minusDays(40),
      "workshop",
      "Predictive Modelling with Python by Jure Žabkar",
      "/news/workshop_x/",
      meta = "Jure Žabkar"
    ),
    item(
      careerDay,
      "job fair, workshops",
      "Data Science Career Day",
      "/news/workshop_2026-10-21-3/",
      meta = "Lobby FRI",
      excerpt = "When? 21 October 2026\nWhere? Lobby FRI\nWhat?\nJoin us on Data Science Career Day 2026."
    ),
    item(
      careerDay,
      "counseling",
      "Personal counseling: CV & networking",
      "/news/workshop_2026-10-21-2/",
      meta = "Zoom"
    ),
    item(today.minusDays(5), "news", "Project competition 2026 results", "/news/pc2026results/"),
    item(today.minusYears(3), "workshop", "Introduction to Network Science in Python", "/news/workshop_2021-12-14/"),
    Json.obj("title" -> Json.fromString("No date"), "url" -> Json.fromString("/news/x/"))
  )

  private val eventsHtml: String =
    s"""<!doctype html><html><body><main id=main><div id=events-app></div></main>
       |<script type=application/json id=events-data>${Json.fromValues(items).noSpaces}</script>
       |</body></html>""".stripMargin

  private def friCard(day: String, month: String, slug: String, title: String, hour: String): String =
    s"""<div class="dogodek-item"><div class="date"><div class="date-single-top"> $day </div>
       |<div class="date-single-bottom"> $month </div></div><div class="dogodki-opis">
       |<div class="event-title"><a href="/sl/dogodek/$slug">$title</a></div>
       |<div class="dogodki-hour">$hour</div></div></div>""".stripMargin

  // FRI lists DataScience's Career Day too, next to an unrelated event on the same day.
  private val friHtml: String =
    s"""<html><body><div id="vsi-dogodki">
       |${friCard("21.", "okt", "data-science-career-day", "Data Science Career Day", "ob 10:00")}
       |${friCard("21.", "okt", "arena-tehnologij", "Arena tehnologij", "ob 12:00")}
       |</div></body></html>""".stripMargin

  private def route[R](test: RIO[R, TestResult], dataScienceUp: Boolean = true): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        request.url.path.toString match
          case "/events/" if dataScienceUp => Handler.fromResponse(Response.text(eventsHtml))
          case "/sl/koledar-dogodkov"      => Handler.fromResponse(Response.text(friHtml))
          case _                           => Handler.notFound
  } *> test

  private def dataScience =
    DataScienceParser(Meetup.make("ds", "DataScience")).streamEventsFrom(DataScienceParser.homepage).runCollect
  private def fri         =
    FRIParser(Meetup.make("fri", "FRI")).streamEventsFrom(URL.decode("https://fri.uni-lj.si").toOption.get).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("DataScienceParserTest")(
      test("reads meetups, workshops and the career day; skips news, counselling, old posts and broken items") {
        route {
          for events <- dataScience
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "datascience-workshop_2026-11-26",
              "datascience-datasciencetalks001",
              "datascience-spark005",
              "datascience-workshop_x",
              "datascience-workshop_2026-10-21-3"
            ),
            events.forall(_.source == Source.DataScience)
          )
        }
      },
      test("time, end time and place from the When?/Where? lines; link to the post") {
        route {
          for
            events <- dataScience
            event   = events.find(_.id == "datascience-workshop_2026-11-26").get
          yield assertTrue(
            event.startDateTime.toLocalDate == today.plusDays(14),
            event.startDateTime.toLocalTime == LocalTime.of(17, 0),
            event.startDateTime.getOffset == zone.getRules.getOffset(event.startDateTime.toLocalDateTime),
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(20, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains(
              "Faculty of Computer and Information Science, Večna pot 113, Ljubljana. Lecture room 3"
            ),
            event.description.contains("Get into the flow of AI-assisted …"),
            event.eventURL.exists(_.toString == "https://datascience.fri.uni-lj.si/news/workshop_2026-11-26/"),
            event.sourceURL.toString == "https://datascience.fri.uni-lj.si/events/"
          )
        }
      },
      test("without a time field: time from When?, else all-day; a meetup's meta is its place, a workshop's is not") {
        route {
          for
            events   <- dataScience
            spark     = events.find(_.id == "datascience-spark005").get
            talks     = events.find(_.id == "datascience-datasciencetalks001").get
            workshop  = events.find(_.id == "datascience-workshop_x").get
            careerDay = events.find(_.id == "datascience-workshop_2026-10-21-3").get
          yield assertTrue(
            spark.startDateTime.toLocalTime == LocalTime.of(18, 0),
            spark.hasStartTime,
            !spark.hasEndTime,
            !talks.hasStartTime,
            talks.locationName.contains("Teads Ljubljana, Dunajska cesta 5, 1000 Ljubljana"),
            talks.description.contains("The Data Science Talks are a recurring event of the Data Science Initiative …"),
            workshop.locationName.isEmpty,
            careerDay.locationName.contains("Lobby FRI"),
            !careerDay.hasStartTime
          )
        }
      },
      test("FRI leaves out the events DataScience lists, and keeps the rest") {
        route {
          for events <- fri
          yield assertTrue(events.map(_.title) == List("Arena tehnologij"))
        }
      },
      test("FRI keeps everything when DataScience's page can't be read") {
        route(
          for events <- fri
          yield assertTrue(events.map(_.title).toSet == Set("Data Science Career Day", "Arena tehnologij")),
          dataScienceUp = false
        )
      },
      test("same event: same day and matching title words, not just the generic ones") {
        val day                                    = LocalDate.of(2026, 3, 24)
        def ds(title: String, on: LocalDate = day) = Item("/news/x/", on, "meetup", title, None, None, None)
        assertTrue(
          sameEvent(day, "Data Science: Spark Sessions", ds("Spark sessions 007")),
          sameEvent(day, "DataScience Spark Sessions", ds("Spark sessions 004")),
          sameEvent(day, "Data Science Career Day", ds("Data Science Career Day")),
          !sameEvent(day, "Data Science: Spark Sessions", ds("Spark sessions 007", day.plusDays(1))),
          !sameEvent(day, "Vabljeno predavanje: Taming Big Data", ds("Data Science Talks")),
          !sameEvent(day, "Arena tehnologij", ds("Data Science Career Day"))
        )
      },
      test("time ranges") {
        assertTrue(
          timesOf(Some("Thursday, 26 November 2026, 17:00–20:00.")).contains(
            LocalTime.of(17, 0)                                                                -> Some(LocalTime.of(20, 0))
          ),
          timesOf(Some("Wednesday, 21 October 2026, 8:30–11:30.")).contains(
            LocalTime.of(8, 30)                                                                -> Some(LocalTime.of(11, 30))
          ),
          timesOf(Some("Wednesday, 11 November 2026, at 19:00.")).contains(LocalTime.of(19, 0) -> None),
          timesOf(Some("21 October 2026")).isEmpty,
          timesOf(None).isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
