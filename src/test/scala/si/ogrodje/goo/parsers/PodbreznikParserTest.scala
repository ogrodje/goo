package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object PodbreznikParserTest extends ZIOSpecDefault:
  import PodbreznikParser.pageOf

  private val meetup = Meetup.make("podbreznik", "Podjetniški inkubator Podbreznik")
  private val site   = "https://www.rc-nm.si"

  // The sidebar and schema.org Event of real event pages (trimmed: no image, organizer, header or footer).
  private def page(event: String, sidebar: String): String =
    s"""<!DOCTYPE html><html><head>
       |<script type="application/ld+json" class="yoast-schema-graph">{"@context":"https://schema.org","@graph":[
       |{"@type":"WebPage","@id":"$site/dogodki/x/","datePublished":"2026-11-05T14:17:56+00:00"},$event]}</script>
       |</head><body><article>…</article><div class="sidebar">$sidebar</div></body></html>""".stripMargin

  private val location = (place: String, street: String) =>
    s"""<div class="sidebar-widget-wrap">
       |<h3 class="h5 sidebar-widget-title"><span class="icon icon-location"></span> Lokacija</h3>
       |<div class="event-location">
       |$place<br/>$street<br />			8000 Novo mesto<br />
       |</div>
       |</div>""".stripMargin

  private val bilanca = page(
    """{"@type":"Event","name":"Kaj nam pove bilanca?","description":"Ali veste, kaj se v resnici skriva za podatki v bilanci stanja in izkazu poslovnega izida?","startDate":"2026-11-18T13:00:00+00:00","endDate":"2026-11-18T16:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode","location":{"@type":"Place","address":{"@type":"PostalAddress","streetAddress":"Podbreznik 15","addressLocality":"Novo mesto","postalCode":"8000"}}}""",
    """<div class="sidebar-widget-wrap">
      |<h3 class="h5 sidebar-widget-title"><span class="icon icon-calendar"></span> Datum</h3>
      |<div class="single-event-meta-schedule">
      |<div class="single-event-meta-schedule-date-start">
      |<span class="event-date">18. novembra 2026</span>
      |</div>
      |<div class="single-event-meta-schedule-time">
      |od <span class="single-event-meta-schedule-time-start">13:00</span> do <span class="single-event-meta-schedule-time-end">16:00</span>
      |</div>
      |</div>
      |</div>""".stripMargin + location("Podjetniški inkubator Podbreznik", "Podbreznik 15")
  )

  private val gladiator = page(
    """{"@type":"Event","name":"Finale Business Gladiator 2026","description":"Pridružite se zaključnemu dogodku podjetniškega programa Business Gladiator 2026.","startDate":"2026-11-26T18:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
    """<div class="sidebar-widget-wrap">
      |<h3 class="h5 sidebar-widget-title"><span class="icon icon-calendar"></span> Datum</h3>
      |<div class="single-event-meta-schedule">
      |<div class="single-event-meta-schedule-date-start">
      |<span class="event-date">26. novembra 2026</span>
      |</div>
      |<div class="single-event-meta-schedule-time">
      |ob <span class="single-event-meta-schedule-time-start">18:00</span>
      |</div>
      |</div>
      |</div>""".stripMargin + location("Knjižnica Mirana Jarca Novo mesto", "Rozmanova ulica 28")
  )

  private val kickUp = page(
    """{"@type":"Event","name":"kickUP Your STARTUP 26","description":"kickUP Your STARTUP 26 je tridnevni inkubacijski program za startup podjetja.","startDate":"2026-10-19T11:00:00+00:00","endDate":"2026-10-21T18:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
    """<div class="sidebar-widget-wrap">
      |<h3 class="h5 sidebar-widget-title"><span class="icon icon-calendar"></span> Datum</h3>
      |<div class="single-event-meta-schedule">
      |<div class="single-event-meta-schedule-date-start">
      |<span class="event-date">od 19. oktobra 2026</span>
      |<span class="single-event-meta-schedule-time-start"> ob 11:00</span>
      |</div>
      |<div class="single-event-meta-schedule-date-end">
      |<span class="event-date">do 21. oktobra 2026</span>
      |<span class="single-event-meta-schedule-time-end"> ob 18:00</span>
      |</div>
      |</div>
      |</div>""".stripMargin + location("Podjetniški inkubator Podbreznik", "Podbreznik 15")
  )

  private val online = page(
    """{"@type":"Event","name":"Američani zaupajo našim kadrom, mi pa še vedno dvomimo vase?","startDate":"2026-08-27T16:15:00+00:00","endDate":"2026-08-27T17:45:00+00:00","eventAttendanceMode":"https://schema.org/OnlineEventAttendanceMode","location":{"@type":"VirtualLocation","url":"https://www.rc-nm.si/dogodki/americani/"}}""",
    """<div class="sidebar-widget-wrap">
      |<h3 class="h5 sidebar-widget-title"><span class="icon icon-calendar"></span> Datum</h3>
      |<div class="single-event-meta-schedule">
      |<div class="single-event-meta-schedule-date-start">
      |<span class="event-date">27. avgusta 2026</span>
      |</div>
      |<div class="single-event-meta-schedule-time">
      |od <span class="single-event-meta-schedule-time-start">16:15</span> do <span class="single-event-meta-schedule-time-end">17:45</span>
      |</div>
      |</div>
      |</div>""".stripMargin
  )

  // No time on the page: "00:00" in the Event, nothing in the sidebar's time spans (as on "Evropski dan jezikov").
  private val allDay = page(
    """{"@type":"Event","name":"Dan odprtih vrat","startDate":"2026-12-02T00:00:00+00:00","endDate":"2026-12-03T00:00:00+00:00","eventAttendanceMode":"https://schema.org/OfflineEventAttendanceMode"}""",
    """<div class="sidebar-widget-wrap"><div class="single-event-meta-schedule">
      |<div class="single-event-meta-schedule-date-start"><span class="event-date">od 2. decembra 2026</span></div>
      |<div class="single-event-meta-schedule-date-end"><span class="event-date">do 3. decembra 2026</span></div>
      |</div></div>""".stripMargin
  )

  private val pages: Map[String, String] = Map(
    "kaj-nam-pove-bilanca"                    -> bilanca,
    "finale-projekta-business-gladiator-2026" -> gladiator,
    "kickup-your-startup-26"                  -> kickUp,
    "americani-zaupajo"                       -> online,
    "dan-odprtih-vrat"                        -> allDay,
    "brez-podatkov"                           -> "<html><body><h1>Brez podatkov</h1></body></html>"
  )

  private val posts: String = Json
    .fromValues(
      List(
        4174 -> ("kaj-nam-pove-bilanca", "Kaj nam pove bilanca?"),
        4123 -> ("finale-projekta-business-gladiator-2026", "Finale Business Gladiator 2026"),
        4037 -> ("kickup-your-startup-26", "kickUP Your STARTUP 26"),
        3687 -> ("americani-zaupajo", "Američani zaupajo našim kadrom, mi pa še vedno dvomimo vase?"),
        3001 -> ("dan-odprtih-vrat", "Dan odprtih vrat"),
        3000 -> ("brez-podatkov", "Brez podatkov &#8211; ni dogodka")
      ).map { case (id, (slug, title)) =>
        Json.obj(
          "id"    -> Json.fromInt(id),
          "link"  -> Json.fromString(s"$site/dogodki/$slug/"),
          "title" -> Json.obj("rendered" -> Json.fromString(title))
        )
      }
    )
    .noSpaces

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val q    = request.url.queryParams
        val path = request.url.path.toString
        if path == "/wp-json/wp/v2/oddelek" && q.getAll("slug").contains("oddelek-podjetnistvo") then
          Handler.fromResponse(Response.text("""[{"id":14,"slug":"oddelek-podjetnistvo"}]"""))
        else if path == "/wp-json/wp/v2/dogodek" && q.getAll("oddelek").contains("14") then
          Handler.fromResponse(Response.text(posts))
        else
          pages.get(path.stripPrefix("/dogodki/").stripSuffix("/")) match
            case Some(html) => Handler.fromResponse(Response.text(html))
            case None       => Handler.notFound
  } *> test

  private def events =
    PodbreznikParser(meetup).streamEventsFrom(URL.decode(site).toOption.get).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("PodbreznikParserTest")(
      test("reads the entrepreneurship department's events; skips a page without an Event") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set("rcnm-4174", "rcnm-4123", "rcnm-4037", "rcnm-3687", "rcnm-3001"),
            events.forall(_.source == Source.PodjetniskiInkubatorPodbreznik)
          )
        }
      },
      test("local time despite the wrong +00:00; place and address from the sidebar; link and lede") {
        route {
          for
            events <- events
            event   = events.find(_.id == "rcnm-4174").get
          yield assertTrue(
            event.title == "Kaj nam pove bilanca?",
            event.startDateTime.toLocalDate == LocalDate.of(2026, 11, 18),
            event.startDateTime.toLocalTime == LocalTime.of(13, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600,
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(16, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Podjetniški inkubator Podbreznik"),
            event.locationAddress.contains("Podbreznik 15, 8000 Novo mesto"),
            event.eventURL.exists(_.toString == s"$site/dogodki/kaj-nam-pove-bilanca/"),
            event.sourceURL.toString == s"$site/dogodki/",
            event.description.exists(_.startsWith("Ali veste, kaj se v resnici skriva"))
          )
        }
      },
      test("no end; several days with times; online in summer; all day over two days") {
        route {
          for
            events   <- events
            gladiator = events.find(_.id == "rcnm-4123").get
            kickUp    = events.find(_.id == "rcnm-4037").get
            online    = events.find(_.id == "rcnm-3687").get
            allDay    = events.find(_.id == "rcnm-3001").get
          yield assertTrue(
            gladiator.startDateTime.toLocalTime == LocalTime.of(18, 0),
            gladiator.endDateTime.isEmpty,
            !gladiator.hasEndTime,
            gladiator.locationName.contains("Knjižnica Mirana Jarca Novo mesto"),
            kickUp.startDateTime.toLocalDateTime.toString == "2026-10-19T11:00",
            kickUp.endDateTime.exists(_.toLocalDateTime.toString == "2026-10-21T18:00"),
            kickUp.hasEndTime,
            online.startDateTime.toLocalTime == LocalTime.of(16, 15),
            online.startDateTime.getOffset.getTotalSeconds == 7200,
            online.locationName.contains("Online"),
            online.locationAddress.isEmpty,
            !allDay.hasStartTime,
            allDay.startDateTime.toLocalTime == LocalTime.MIDNIGHT,
            allDay.endDateTime.exists(_.toLocalDate == LocalDate.of(2026, 12, 3)),
            !allDay.hasEndTime
          )
        }
      },
      test("a page's Event among Yoast's other JSON-LD; none -> an error") {
        assertTrue(
          pageOf(bilanca).exists(p => p.hasStartTime && p.place.contains("Podjetniški inkubator Podbreznik")),
          pageOf("<html><script type=\"application/ld+json\">{\"@type\":\"WebPage\"}</script></html>").isLeft,
          pageOf("<html><script type=\"application/ld+json\">not json</script></html>").isLeft
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
