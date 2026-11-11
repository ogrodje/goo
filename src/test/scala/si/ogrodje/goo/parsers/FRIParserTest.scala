package si.ogrodje.goo.parsers

import org.jsoup.Jsoup
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object FRIParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("fri", "FRI")

  private def single(day: String, month: String, slug: String, title: String, hour: String): String =
    s"""<div class="dogodek-item" style="margin-bottom: 20px;">
       |  <div class="date" style="float:left">
       |    <div class="date-single-top"> $day </div>
       |    <div class="date-single-bottom" style="font-size: 18px;"> $month </div>
       |  </div>
       |  <div class="dogodki-opis">
       |    <div class="event-title" style="width: 100%;"><a href="/sl/dogodek/$slug" style="color:#000">$title</a></div>
       |    <div class="dogodki-hour" style="color: #7f7f7f;">$hour</div>
       |    <div class="dogodki-taxonomy"><span class="kategorija"> Obvestila </span></div>
       |  </div>
       |</div>""".stripMargin

  // The multi-day layout that broke the parser on 2026-11-03 (copied from the live calendar).
  private val multiDay: String =
    """<div class="dogodek-item" style="margin-bottom: 20px;">
      |  <div class="date" style="float:left">
      |    <div class="date-top"> <span class="dan">24. </span> <span class="mesec">nov</span> </div>
      |    <div class="date-middle">-</div>
      |    <div class="date-bottom"> <span class="dan">26. </span> <span class="mesec">nov</span> </div>
      |  </div>
      |  <div class="dogodki-opis">
      |    <div class="event-title" style="width: 100%;">
      |      <a href="/sl/dogodek/uporabna-orodja-za-slovenscino" style="color:#000">Uporabna orodja za slovenščino</a>
      |    </div>
      |    <div class="dogodki-hour" style="color: #7f7f7f;">ob 17:00</div>
      |  </div>
      |</div>""".stripMargin

  // No date at all: skipped, the rest still come through.
  private val broken: String =
    """<div class="dogodek-item"><div class="dogodki-opis">
      |  <div class="event-title"><a href="/sl/dogodek/brez-datuma">Brez datuma</a></div>
      |</div></div>""".stripMargin

  private val calendarHtml: String =
    s"""<!DOCTYPE html><html><body>
       |<div id="vsi-dogodki">
       |${single("11.", "nov", "predavanje-asef", "Predavanje ASEF: Veliki jezikovni modeli", "ob 17:15")}
       |${single("16.", "nov", "slovenski-okusi-na-fri", "Slovenski okusi na FRI", "ob 10:00")}
       |$multiDay
       |$broken
       |${single("25.", "nov", "javna-predstavitev-doktorske-disertacije", "Javna predstavitev", "ob 11:00")}
       |</div>
       |</body></html>""".stripMargin

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        if request.url.path.toString == "/sl/koledar-dogodkov" then Handler.fromResponse(Response.text(calendarHtml))
        else Handler.notFound
  } *> test

  private val homepage = URL.decode("https://fri.uni-lj.si").toOption.get

  private def events = FRIParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("FRIParserTest")(
      test("reads single and multi-day events, skips an unreadable card and doctoral defences") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.title).sorted == List(
              "Predavanje ASEF: Veliki jezikovni modeli",
              "Slovenski okusi na FRI",
              "Uporabna orodja za slovenščino"
            ),
            events.forall(_.source == Source.FRI)
          )
        }
      },
      test("a multi-day event starts on its first day and ends on its last") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Uporabna orodja za slovenščino").get
          yield assertTrue(
            event.startDateTime.getMonthValue == 11,
            event.startDateTime.getDayOfMonth == 24,
            event.startDateTime.toLocalTime == LocalTime.of(17, 0),
            event.endDateTime.exists(_.getDayOfMonth == 26),
            event.hasStartTime,
            !event.hasEndTime
          )
        }
      },
      test("times are in Ljubljana's zone, and ids stay what they were (sha1 of the link)") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Slovenski okusi na FRI").get
          yield assertTrue(
            event.startDateTime.toLocalTime == LocalTime.of(10, 0),
            event.startDateTime.getOffset == java.time.ZoneId
              .of("Europe/Ljubljana")
              .getRules
              .getOffset(event.startDateTime.toLocalDateTime),
            event.id == "fri-0dd4e911e9b6cad40f42721c65df2b9c5aedb6bc",
            event.eventURL.exists(_.toString == "https://fri.uni-lj.si/sl/dogodek/slovenski-okusi-na-fri")
          )
        }
      },
      test("picks the year closest to today, across New Year") {
        import FRIParser.{dateOf, DayMonth}
        val december = LocalDate.of(2026, 12, 20)
        val january  = LocalDate.of(2027, 1, 5)
        assertTrue(
          dateOf(DayMonth("16.", "nov"), LocalDate.of(2026, 11, 11)).contains(LocalDate.of(2026, 11, 16)),
          dateOf(DayMonth("8.", "jan"), december).contains(LocalDate.of(2027, 1, 8)),
          dateOf(DayMonth("28.", "dec"), january).contains(LocalDate.of(2026, 12, 28)),
          dateOf(DayMonth("1.", "maj"), LocalDate.of(2026, 4, 1)).contains(LocalDate.of(2026, 5, 1)),
          dateOf(DayMonth("31.", "avg"), LocalDate.of(2026, 8, 1)).contains(LocalDate.of(2026, 8, 31)),
          dateOf(DayMonth("16.", "xyz"), december).isEmpty
        )
      },
      test("reads both card layouts and the hour") {
        import FRIParser.{dates, timeIn, DayMonth}
        val singleCard = Jsoup.parse(single("16.", "nov", "x", "X", "ob 10:00")).selectFirst(".dogodek-item")
        val multiCard  = Jsoup.parse(multiDay).selectFirst(".dogodek-item")
        assertTrue(
          dates(singleCard).contains(DayMonth("16.", "nov") -> None),
          dates(multiCard).contains(DayMonth("24.", "nov")  -> Some(DayMonth("26.", "nov"))),
          dates(Jsoup.parse(broken).selectFirst(".dogodek-item")).isEmpty,
          timeIn("ob 17:15").contains(LocalTime.of(17, 15)),
          timeIn("ob 9.00").contains(LocalTime.of(9, 0)),
          timeIn("").isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
