package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object RacunalniskiMuzejParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("racunalniski-muzej", "Računalniški muzej")
  private val host   = "https://www.racunalniski-muzej.si"

  // An archive card as the "ova-events" plugin renders it: day and month, but no year.
  private def card(slug: String, title: String): String =
    s"""<div class="content">
       |  <div class="date-event"><span class="date-month">02<span class="month">december</span></span></div>
       |  <div class="desc"><div class="event_post">
       |    <h2 class="second_font event_title"><a href="$host/event/$slug/">$title</a></h2>
       |    <div class="button_event"><a class="view_detail" href="$host/event/$slug/">Podrobnosti</a></div>
       |  </div></div>
       |</div>""".stripMargin

  private def archive(cards: String*): String =
    s"""<!DOCTYPE html><html><body class="archive post-type-archive post-type-archive-event">
       |<div class="archive_event type1">${cards.mkString("\n")}</div>
       |<a href="$host/event/page/2/">2</a>
       |</body></html>""".stripMargin

  // A detail page: the order box with "Čas:"/"Lokacija:" and the addtocalendar.com links (month-first dates).
  private def detailHtml(
    postID: Int,
    title: String,
    start: String,
    end: String,
    location: Option[String],
    calendar: Boolean = true
  ): String =
    val atc        =
      s"http://addtocalendar.com/atc/ical?utz=420&amp;uln=en-US&amp;vjs=1.5&amp;e[0][date_start]=$start" +
        s"&amp;e[0][date_end]=$end&amp;e[0][timezone]=&amp;e[0][title]=$title&amp;e[0][description]=Opis &amp; več"
    val locationLi = location.fold("")(l => s"""<li><span class="label">Lokacija:</span><span>$l</span></li>""")
    s"""<!DOCTYPE html><html><head>
       |<meta name="description" content="Opis dogodka $title." />
       |</head><body class="event-template-default single single-event postid-$postID wp-theme-muzze">
       |<div class="single_event"><div class="container">
       |  <div class="title_top"><h1 class="second_font">$title</h1>
       |    <div class="value_mid"><span class="event-date">sreda,&nbsp;02/12/2026</span></div>
       |  </div>
       |  ${
        if calendar then
          s"""<div class="calendar-sync"><span class="sync"><a href="$atc">+ Ical Export</a></span></div>"""
        else ""
      }
       |  <div class="wrapper_order"><div class="order_ticket"><ul class="info_order">
       |    <li><span class="label">Vrsta:</span> <span>Posebni dogodki</span></li>
       |    $locationLi
       |  </ul></div></div>
       |</div></div>
       |</body></html>""".stripMargin

  private val details: Map[String, String] = Map(
    "dedek-mraz"      -> detailHtml(
      18922,
      "Obisk Dedka Mraza",
      "12/02/2026 17:00",
      "12/02/2026 19:00",
      Some("Računalniški muzej")
    ),
    // Multi-day: ends four days later.
    "ars-electronica" -> detailHtml(18001, "Ars Electronica", "09/09/2026 10:00", "09/13/2026 16:00", Some("Linz")),
    // The site really has one like this: the end is two days before the start.
    "arena"           -> detailHtml(18002, "Arena tehnologij", "10/21/2026 08:00", "10/19/2026 17:00", None),
    "brez-ure"        -> detailHtml(18003, "Dogodek brez ure", "11/05/2026", "11/05/2026", None),
    "brez-koledarja"  -> detailHtml(18004, "Brez koledarja", "", "", None, calendar = false),
    "na-cetrti"       -> detailHtml(17000, "Star dogodek", "01/10/2024 10:00", "01/10/2024 12:00", None)
  )

  // Four archive pages exist; the parser reads at most three. "dedek-mraz" shows up twice.
  private val archives: Map[String, String] = Map(
    "/event/"        -> archive(card("dedek-mraz", "Obisk Dedka Mraza"), card("ars-electronica", "Ars Electronica")),
    "/event/page/2/" -> archive(card("arena", "Arena tehnologij"), card("dedek-mraz", "Obisk Dedka Mraza")),
    "/event/page/3/" -> archive(card("brez-ure", "Dogodek brez ure"), card("brez-koledarja", "Brez koledarja")),
    "/event/page/4/" -> archive(card("na-cetrti", "Star dogodek"))
  )

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val path        = request.url.path.toString
        val responseZIO =
          ZIO
            .fromOption(archives.get(path).orElse(details.get(path.stripPrefix("/event/").stripSuffix("/"))))
            .map(Response.text(_))
            .orElseFail(new Exception(s"No response for $path"))
        Handler.fromZIO(responseZIO).catchAll(_ => Handler.notFound)
  } *> test

  private val homepage = URL.decode(host).toOption.get

  private def events = RacunalniskiMuzejParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("RacunalniskiMuzejParserTest")(
      test("collects events from the three newest archive pages, once each, skipping unparsable ones") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.title).sorted == List(
              "Arena tehnologij",
              "Ars Electronica",
              "Dogodek brez ure",
              "Obisk Dedka Mraza"
            ),
            events.forall(_.source == Source.RacunalniskiMuzej)
          )
        }
      },
      test("reads the year, time range, location and description from the detail page") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Obisk Dedka Mraza").get
          yield assertTrue(
            event.startDateTime.toLocalDate == LocalDate.of(2026, 12, 2),
            event.startDateTime.toLocalTime == LocalTime.of(17, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600, // CET
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(19, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Računalniški muzej"),
            event.description.contains("Opis dogodka Obisk Dedka Mraza."),
            event.eventURL.exists(_.toString == s"$host/event/dedek-mraz/"),
            event.sourceURL.path.toString == "/event/"
          )
        }
      },
      test("keeps multi-day events with their end day, in summer time") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Ars Electronica").get
          yield assertTrue(
            event.startDateTime.toLocalDate == LocalDate.of(2026, 9, 9),
            event.startDateTime.getOffset.getTotalSeconds == 7200, // CEST
            event.endDateTime.exists(_.toLocalDate == LocalDate.of(2026, 9, 13)),
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(16, 0)),
            event.locationName.contains("Linz")
          )
        }
      },
      test("drops an end that comes before the start, and leaves a missing location empty") {
        route {
          for
            events <- events
            event   = events.find(_.title == "Arena tehnologij").get
          yield assertTrue(
            event.startDateTime.toLocalDate == LocalDate.of(2026, 10, 21),
            event.endDateTime.isEmpty,
            !event.hasEndTime,
            event.locationName.isEmpty
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
            event.startDateTime.toLocalDate == LocalDate.of(2026, 11, 5)
          )
        }
      },
      test("uses stable ids from the WordPress post id") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "racunalniski-muzej-18922",
              "racunalniski-muzej-18001",
              "racunalniski-muzej-18002",
              "racunalniski-muzej-18003"
            )
          )
        }
      },
      test("reads month-first calendar values, plain or percent-encoded") {
        import RacunalniskiMuzejParser.{calendarDateTime, calendarParamIn}
        val plain   =
          "http://addtocalendar.com/atc/ical?utz=420&e[0][date_start]=12/02/2026 17:00&e[0][date_end]=12/02/2026 19:00"
        val encoded = "http://addtocalendar.com/atc/google?e%5B0%5D%5Bdate_start%5D=09%2F09%2F2026%2010%3A00&x=1"
        assertTrue(
          calendarParamIn(plain, "date_start").contains("12/02/2026 17:00"),
          calendarParamIn(plain, "date_end").contains("12/02/2026 19:00"),
          calendarParamIn(encoded, "date_start").contains("09/09/2026 10:00"),
          calendarParamIn(plain, "timezone").isEmpty,
          calendarDateTime("12/02/2026 17:00").contains(LocalDate.of(2026, 12, 2) -> Some(LocalTime.of(17, 0))),
          calendarDateTime("06/20/2026 23:59").contains(LocalDate.of(2026, 6, 20) -> Some(LocalTime.of(23, 59))),
          calendarDateTime("11/05/2026").contains(LocalDate.of(2026, 11, 5)       -> None),
          calendarDateTime("13/40/2026 10:00").isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
