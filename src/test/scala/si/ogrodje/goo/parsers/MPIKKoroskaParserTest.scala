package si.ogrodje.goo.parsers

import org.jsoup.Jsoup
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime, ZoneId}

object MPIKKoroskaParserTest extends ZIOSpecDefault:
  import MPIKKoroskaParser.{cardsIn, daysIn, detailsIn, timesIn, Day}

  private val zone  = ZoneId.of("Europe/Ljubljana")
  private val today = LocalDate.now(zone)
  private val icon  =
    """<svg role="img" class="icon calendar" title="Datum" aria-labelledby="title"><title>Datum</title><use xlink:href="/images/sprite.134.svg#calendar"></use> </svg>"""
  private val clock =
    """<svg role="img" class="icon clock" title="Ura" aria-labelledby="title"><title>Ura</title><use xlink:href="/images/sprite.134.svg#clock"></use> </svg>"""

  // Real card markup (from the live listing), with the date list and time put in.
  private def card(slug: String, title: String, dates: String, time: Option[String]): String =
    s"""<div class="flow wAktualno inview2"> <div class="wAktualno__cover"><a href="/aktualno/dogodki/$slug"><img src="/img/x.png" alt=""></a></div>
       |<h3><a href="/aktualno/dogodki/$slug">$title </a></h3><ul class="groupList" role="list"><li class="group group--podjetnistvo">
       |<a href="/aktualno/dogodki/podjetnistvo">Podjetništvo</a> </li></ul><ul role="list" class="wDogodki__info flow">
       |<li class="wDogodki__info--date"> $icon<div>$dates</div></li>
       |${time.fold("")(t => s"""<li class="wDogodki__info--time"> $clock$t</li>""")}</ul></div>""".stripMargin

  // A date list as the site writes it: groups split by dateDivider, the year on the last one only.
  private def dates(days: (LocalDate, Option[LocalDate])*): String =
    days.zipWithIndex.map { case ((from, to), i) =>
      val day  = to.fold(s"${from.getDayOfMonth}.")(t => s"${from.getDayOfMonth}.–${t.getDayOfMonth}.")
      val last = i == days.size - 1
      s"<span>$day</span>&nbsp;<span>${from.getMonthValue}.</span>" + (if last then
                                                                         s"&nbsp;<span>${from.getYear}</span>"
                                                                       else "")
    }
      .mkString(""" <span class="dateDivider" ></span> """)

  private val soon     = today.plusDays(5)
  private val series   = List(today.minusDays(14), today.plusDays(5), today.plusDays(12))
  private val longAgo  = today.minusMonths(13)
  private val pastWeek = today.minusDays(7)

  private val listingHtml: String =
    s"""<html><body><section class="noviceList ws__A ws--pad">
       |${card(
        "mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca",
        "MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca?",
        dates(soon -> None),
        Some("16.00 - 19.00")
      )}
       |${card(
        "mpik-akademija-uspeha-2026",
        "MPIK akademija uspeha 2026",
        dates(series.map(_ -> None)*),
        Some("16.00 - 19.00")
      )}
       |${card(
        "brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike",
        "Brezplačni usposabljanji za MSP podjetja in potencialne podjetnike",
        dates(pastWeek -> None),
        None
      )}
       |${card(
        "koroski-zaposlitveni-in-karierni-sejem-2026",
        "Koroški zaposlitveni in karierni sejem 2026",
        dates(today.plusDays(20) -> Some(today.plusDays(21))),
        Some("9.00 - 16.00")
      )}
       |${card(
        "aktualni-podjetniski-dogodki-in-razpisi-november-2026",
        "Aktualni podjetniški dogodki in razpisi – november 2026",
        dates(soon -> None, today.plusDays(9) -> None),
        None
      )}
       |${card(
        "mpeak-akademija-uspeha-2024",
        "MPEAK akademija uspeha 2024",
        dates(longAgo -> None),
        Some("16.00 - 19.00")
      )}
       |</section></body></html>""".stripMargin

  // An event page (trimmed): its own info list first, then related events, and the calendar data.
  private val detailHtml: String =
    """<html><body><header><ul role="list" class="wDogodki__info flow"><li class="wDogodki__info--date"><div><span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span></div></li>
      |<li class="wDogodki__info--time"> 16.00 - 19.00</li>
      |<li class="wDogodki__info--place"> <svg role="img" class="icon location" title="Lokacija" aria-labelledby="title"><title>Lokacija</title><use xlink:href="/images/sprite.134.svg#location"></use> </svg>Stara železarna Ravne, Koroška cesta 12, 2390 Ravne na Koroškem</li></ul></header>
      |<aside><ul class="wDogodki__info flow"><li class="wDogodki__info--place"> Drugje</li></ul></aside>
      |<script>$(document).ready(function() { $.flVars = {"u":{"lang":"si"},"rra":{"aCalendar":{"title":"MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca? ","start":"November 17, 2026 16:00","end":"November 17, 2026 19:00","address":"Stara železarna Ravne","description":"Komu je namenjena?&nbsp;\r\n\r\nAmbicioznim posameznikom s podjetniško idejo.\r\n"},"aCalendar_settings":{}}}; });</script>
      |</body></html>""".stripMargin

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        request.url.path.toString match
          case "/aktualno/dogodki/podjetnistvo"                                                => Handler.fromResponse(Response.text(listingHtml))
          case "/aktualno/dogodki/mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca" =>
            Handler.fromResponse(Response.text(detailHtml))
          case _                                                                               => Handler.notFound
  } *> test

  private def events(homepage: String) =
    MPIKKoroskaParser(Meetup.make("mpik", "MPIK")).streamEventsFrom(URL.decode(homepage).toOption.get).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("MPIKKoroskaParserTest")(
      test(
        "reads the podjetništvo listing: single events, a range, a series by session; skips roundups and old events"
      ) {
        route {
          for events <- events("https://www.mpik-koroska.si/si/")
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "mpik-mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca",
              s"mpik-mpik-akademija-uspeha-2026-${series(0)}",
              s"mpik-mpik-akademija-uspeha-2026-${series(2)}",
              "mpik-brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike",
              "mpik-koroski-zaposlitveni-in-karierni-sejem-2026"
            ),
            events.forall(_.source == Source.MPIKKoroska)
          )
        }
      },
      test("an event: time range in Ljubljana's time, place and description from its page, link") {
        route {
          for
            events <- events("https://rra-koroska.si/")
            event   = events.find(_.id == "mpik-mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca").get
          yield assertTrue(
            event.title == "MPIK akademija uspeha 2026: Kako vas vidi umetna inteligenca?",
            event.startDateTime.toLocalDate == soon,
            event.startDateTime.toLocalTime == LocalTime.of(16, 0),
            event.startDateTime.getOffset == zone.getRules.getOffset(event.startDateTime.toLocalDateTime),
            event.endDateTime.exists(_.toLocalTime == LocalTime.of(19, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Stara železarna Ravne, Koroška cesta 12, 2390 Ravne na Koroškem"),
            event.description.contains("Komu je namenjena? Ambicioznim posameznikom s podjetniško idejo."),
            event.eventURL.exists(
              _.toString == "https://rra-koroska.si/aktualno/dogodki/mpik-akademija-uspeha-2026-kako-vas-vidi-umetna-inteligenca"
            ),
            event.sourceURL.toString == "https://rra-koroska.si/aktualno/dogodki/podjetnistvo"
          )
        }
      },
      test("a range ends on its last day; no time is all-day; a page that can't be read leaves no place") {
        route {
          for
            events <- events("https://rra-koroska.si/")
            fair    = events.find(_.id == "mpik-koroski-zaposlitveni-in-karierni-sejem-2026").get
            msp     = events.find(_.id == "mpik-brezplacni-usposabljanji-za-msp-podjetja-in-potencialne-podjetnike").get
            session = events.find(_.id == s"mpik-mpik-akademija-uspeha-2026-${series(2)}").get
          yield assertTrue(
            fair.startDateTime.toLocalDate == today.plusDays(20),
            fair.startDateTime.toLocalTime == LocalTime.of(9, 0),
            fair.endDateTime.exists(_.toLocalDate == today.plusDays(21)),
            !fair.hasEndTime,
            !msp.hasStartTime,
            msp.endDateTime.isEmpty,
            msp.locationName.isEmpty,
            session.title == "MPIK akademija uspeha 2026",
            session.startDateTime.toLocalTime == LocalTime.of(16, 0)
          )
        }
      },
      test("date lists as the live site writes them") {
        def div(html: String)           = Jsoup.parse(s"<div id=d>$html</div>").getElementById("d")
        def d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
        assertTrue(
          daysIn(div("<span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span>")) == List(
            Day(d(2026, 11, 17), None)
          ),
          daysIn(div("<span>12.–13.</span> <span>4.</span>&nbsp;<span>2023</span>")) ==
            List(Day(d(2023, 4, 12), Some(d(2023, 4, 13)))),
          daysIn(
            div(
              """<span>29.</span>&nbsp;<span>9.</span> <span class="dateDivider" ></span> <span>7.–8.</span> <span>10.</span> <span class="dateDivider" ></span> <span>24.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span>"""
            )
          ) == List(Day(d(2026, 9, 29), None), Day(d(2026, 10, 7), Some(d(2026, 10, 8))), Day(d(2026, 11, 24), None)),
          // Across New Year: the year is only on the last date.
          daysIn(
            div(
              """<span>16.</span>&nbsp;<span>12.</span> <span class="dateDivider" ></span> <span>13.</span>&nbsp;<span>1.</span>&nbsp;<span>2027</span>"""
            )
          ) == List(Day(d(2026, 12, 16), None), Day(d(2027, 1, 13), None)),
          daysIn(div("<span>kmalu</span>")).isEmpty
        )
      },
      test("times, cards and event pages") {
        val listing = Jsoup.parse(listingHtml)
        val cards   = cardsIn(listing)
        assertTrue(
          timesIn("16.00 - 19.00").contains(LocalTime.of(16, 0) -> Some(LocalTime.of(19, 0))),
          timesIn("8.30").contains(LocalTime.of(8, 30)          -> None),
          timesIn("").isEmpty,
          cards.size == 6,
          cards.find(_.slug == "mpik-akademija-uspeha-2026").exists(_.days.size == 3),
          cards.find(_.slug.startsWith("aktualni")).exists(_.isRoundup),
          detailsIn(Jsoup.parse(detailHtml)).place
            .contains("Stara železarna Ravne, Koroška cesta 12, 2390 Ravne na Koroškem"),
          detailsIn(Jsoup.parse("<html><body><p>Ni podatkov</p></body></html>")) == MPIKKoroskaParser.Details(
            None,
            None
          )
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
