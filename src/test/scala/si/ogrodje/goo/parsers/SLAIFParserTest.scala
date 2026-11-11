package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime, ZoneId}

object SLAIFParserTest extends ZIOSpecDefault:
  import SLAIFParser.{slovenian, timesOf}

  private val meetup = Meetup.make("slaif", "SLAIF")
  private val zone   = ZoneId.of("Europe/Ljubljana")

  // Items as /api/events returns them (copied from the live API), plus a draft and one that doesn't decode.
  private val eventsJson: String =
    """[
      |{"id":52,"title":"Vzpostavljanje podatkovne kulture: zbiranje, upravljanje in hramba podatkov | Building a data culture: data collection, management and preservation","tags":["Workshop","Online"],"event_date":"2027-01-20","time_range":"13:00–15:00","location":"VK","lecturer":"Marko Drobnjak","difficulty":1,"indico_url":"https://indico.ijs.si/event/3989/","status":"published"},
      |{"id":14,"title":"Scaling AI with Multi-GPU","tags":[],"event_date":"2027-01-18","time_range":"","location":"Fakulteta za informacijske študije, Univerza v Novem mestu, Ljubljanska cesta 31a, 8000 Novo mesto","lecturer":"Robi Pritržnik","difficulty":2,"indico_url":"https://indico.ijs.si/event/3993/","status":"published"},
      |{"id":44,"title":"Ai act, DGA in upravljanje umetne inteligence – od strategij do prakse | AI Act, DGA and AI governance","tags":["Workshop","On-site"],"event_date":"2026-09-09","time_range":"09:00–13:00","location":"Tehnološki park Ljubljana","lecturer":"Barbi Seme, Matej Kirn","difficulty":1,"indico_url":"","status":"published"},
      |{"id":99,"title":"Osnutek | Draft","tags":[],"event_date":"2027-02-01","time_range":"10:00–12:00","location":"Online","lecturer":"","difficulty":1,"indico_url":null,"status":"draft"},
      |{"id":98,"title":"Brez datuma","tags":[],"event_date":"kmalu","time_range":"","location":"","lecturer":"","difficulty":1,"indico_url":null,"status":"published"}
      |]""".stripMargin

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        if request.url.path.toString == "/api/events" then Handler.fromResponse(Response.json(eventsJson))
        else Handler.notFound
  } *> test

  private val homepage = URL.decode("https://www.slaif.si").toOption.get

  private def events = SLAIFParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("SLAIFParserTest")(
      test("reads published events, skips drafts and items that don't decode") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set("slaif-52", "slaif-14", "slaif-44"),
            events.forall(_.source == Source.SLAIF)
          )
        }
      },
      test("an event with a time range, in Slovenian, linking to Indico") {
        route {
          for
            events <- events
            event   = events.find(_.id == "slaif-52").get
          yield assertTrue(
            event.title == "Vzpostavljanje podatkovne kulture: zbiranje, upravljanje in hramba podatkov",
            event.startDateTime.toLocalDate == LocalDate.of(2027, 1, 20),
            event.startDateTime.toLocalTime == LocalTime.of(13, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600,
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(15, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("VK"),
            event.description.contains("Izvajalci: Marko Drobnjak"),
            event.eventURL.exists(_.toString == "https://indico.ijs.si/event/3989/"),
            event.sourceURL.toString == "https://www.slaif.si/events"
          )
        }
      },
      test("no time is all-day; no Indico link points to SLAIF's events page; summer time") {
        route {
          for
            events  <- events
            noTime   = events.find(_.id == "slaif-14").get
            noIndico = events.find(_.id == "slaif-44").get
          yield assertTrue(
            noTime.title == "Scaling AI with Multi-GPU",
            !noTime.hasStartTime,
            noTime.endDateTime.isEmpty,
            noTime.locationName.exists(_.startsWith("Fakulteta za informacijske študije")),
            noIndico.eventURL.exists(_.toString == "https://www.slaif.si/events"),
            noIndico.startDateTime.getOffset == zone.getRules.getOffset(noIndico.startDateTime.toLocalDateTime),
            noIndico.startDateTime.getOffset.getTotalSeconds == 7200
          )
        }
      },
      test("titles and time ranges") {
        assertTrue(
          slovenian("Odprta koda za raziskovanje | Open source for research") == "Odprta koda za raziskovanje",
          slovenian("Hands on Machine Learning") == "Hands on Machine Learning",
          slovenian("A | B | C") == "A | B | C",
          timesOf(Some("09:55–12:00")).contains(LocalTime.of(9, 55)   -> Some(LocalTime.of(12, 0))),
          timesOf(Some("10:00 - 11:20")).contains(LocalTime.of(10, 0) -> Some(LocalTime.of(11, 20))),
          timesOf(Some("10.00")).contains(LocalTime.of(10, 0)         -> None),
          timesOf(None).isEmpty,
          timesOf(Some("po dogovoru")).isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
