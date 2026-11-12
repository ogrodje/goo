package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object KatapultParserTest extends ZIOSpecDefault:
  import KatapultParser.{cleanTitle, whenOf, When}

  private val meetup = Meetup.make("katapult", "Katapult")

  private def d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
  private def t(h: Int, m: Int)           = LocalTime.of(h, m)

  private def card(slug: String, title: String): String =
    s"""<div class="e-blog-list-item clean-typography w-full"> <div class="bg-white card-shadow">
       |<div class="flex min-h-[360px] flex-col justify-between pt-[10px] px-5 pb-6">
       |<div class="w-full flex flex-col justify-start items-start">
       |<span class="body-small-bold text-primary-orange capitalize"> dogodek </span>
       |<h3 class="headline3"><a href="/novice/$slug">$title</a></h3>
       |<div class="py-4 body-small-regular"><p>…</p></div></div>
       |<div class="e-button flex "><a class="btn-simple" type="novice" href="/novice/$slug"><span> Preberi več </span></a></div>
       |</div></div></div>""".stripMargin

  private def listing(cards: String*): String =
    s"""<html><body><section><div wire:id="x" wire:name="blog.blog-list"><div class="grid">${cards.mkString}</div></div>
       |</section></body></html>""".stripMargin

  // Posts as the site renders them (copied from the live pages, trimmed).
  private def post(title: String, subtitle: String, published: String, side: String): String =
    s"""<html><body><section><h1 class=""> $title </h1>
       |<div class="blog-single-info flex flex-col gap-y-[10px]">
       |<div class="blog-single-subtitle"> <p>$subtitle</p> </div>
       |<div class="blog-single-info-meta flex justify-center"> <div class="flex gap-[10px] items-center justify-center w-full">
       |<span class="w-1/2 text-right">Olga Knez</span> <span class="block custom-vertical-separator"></span>
       |<span class="w-1/2 text-left">$published</span> </div> </div></div></section>
       |<div class="content-holder"><div class="m-columns flex">
       |<div class="md:w-2/3 "><div class="e-text"><p>Mentorji Katapulta svoje znanje črpajo iz izkušenj.</p>
       |<ul><li><p><strong>Kje se najpogosteje zatakne:</strong> tipične napake startupov</p></li></ul></div></div>
       |<div class="md:w-1/3 "><div class="e-text">$side</div></div></div></div></body></html>""".stripMargin

  private val posts: Map[String, String] = Map(
    "od-ideje-do-izdelka"            -> post(
      "Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026",
      "Kako ideja postane izdelek, ki ga kupci res želijo?",
      "04.11.2026",
      """<p><strong>Kdaj?</strong> Torek, 17. november 2026, od 9.00 do&nbsp;12.00</p><p><strong>Kje?</strong> Katapult in Dewesoft,&nbsp;Trbovlje</p><p><strong>Koliko?</strong>&nbsp;Brezplačno</p>"""
    ),
    "scale-it"                       -> post(
      "SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)",
      "Inkubacijski program SCALE IT! je namenjen startupom.",
      "09.10.2026",
      """<p style="text-align: justify;"><strong>Kdaj?</strong> 23., 27. in 29. oktober 2026, vsak dan od 9.00 do&nbsp;16.00</p><p style="text-align: justify;"><strong>Kje?</strong> Katapult, Gabrsko 12, 1420&nbsp;Trbovlje</p>"""
    ),
    "za-prijaznejso-prihodnost-2026" -> post(
      "Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti. I 26.–28.11.2026",
      "Tridnevni festival znanosti, robotike in nevrotehnologij.",
      "04.11.2026",
      """<p><strong>Kdaj?</strong> Od četrtka, 26., do sobote, 28. novembra 2026</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, Trbovlje</p>"""
    ),
    "migy-delavnice"                 -> post(
      "DELAVNICA: MIGY delavnice izdelave DIY robotkov",
      "Udeleženci bodo sestavili robotka.",
      "10.06.2026",
      """<p>DVA TERMINA: 📅 20. 7. – 23. 7. 2026 in 📅 24. 8. – 27. 8. 2026</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, 1420 Trbovlje</p>"""
    ),
    "meet-up"                        -> post(
      "BREZPLAČNI DOGODEK: Katapult Podjetniški Meet-up",
      "Pridružite se nam.",
      "30.11.2025",
      """<p><strong>Kdaj?</strong> Torek, 16. 12. 2025, od 16.00 do 19.00</p><p><strong>Kje?</strong> Katapult, Gabrsko 12, 1420 Trbovlje</p>"""
    )
  )

  private val pages: Map[Option[String], String] = Map(
    None      -> listing(
      card("od-ideje-do-izdelka", "Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026"),
      card("scale-it", "SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)"),
      card("za-prijaznejso-prihodnost-2026", "Za prijaznejšo prihodnost 2026 I 26.–28.11.2026")
    ),
    Some("2") -> listing(
      card("migy-delavnice", "DELAVNICA: MIGY delavnice izdelave DIY robotkov"),
      card("meet-up", "BREZPLAČNI DOGODEK: Katapult Podjetniški Meet-up"),
      card("izbrisan", "Izbrisan dogodek")
    ),
    Some("3") -> listing(card("prestar", "Prestar dogodek"))
  )

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val path = request.url.path.toString
        if path == "/dogodki" then
          pages.get(request.url.queryParams.getAll("page").headOption) match
            case Some(html) => Handler.fromResponse(Response.text(html))
            case None       => Handler.fromResponse(Response.text(listing()))
        else
          posts.get(path.stripPrefix("/novice/")) match
            case Some(html) => Handler.fromResponse(Response.text(html))
            case None       => Handler.notFound
  } *> test

  private def events =
    KatapultParser(meetup).streamEventsFrom(URL.decode("https://katapult.si").toOption.get).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("KatapultParserTest")(
      test("reads the first two listing pages and each post; skips posts without a day and ones that fail") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set(
              "katapult-od-ideje-do-izdelka",
              "katapult-scale-it",
              "katapult-za-prijaznejso-prihodnost-2026",
              "katapult-meet-up"
            ),
            events.forall(_.source == Source.Katapult)
          )
        }
      },
      test("an event: title without the date, day and times, place from 'Kje?', lede, link") {
        route {
          for
            events <- events
            event   = events.find(_.id == "katapult-od-ideje-do-izdelka").get
          yield assertTrue(
            event.title == "Od ideje do izdelka: vpogled z mentorji Katapulta",
            event.startDateTime.toLocalDate == d(2026, 11, 17),
            event.startDateTime.toLocalTime == t(9, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600,
            event.endDateTime.exists(e => e.toLocalDate == d(2026, 11, 17) && e.toLocalTime == t(12, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("Katapult in Dewesoft, Trbovlje"),
            event.description.contains("Kako ideja postane izdelek, ki ga kupci res želijo?"),
            event.eventURL.exists(_.toString == "https://katapult.si/novice/od-ideje-do-izdelka"),
            event.sourceURL.toString == "https://katapult.si/dogodki"
          )
        }
      },
      test("several days: from the first to the last, with the daily times; a festival without times is all-day") {
        route {
          for
            events  <- events
            scaleIt  = events.find(_.id == "katapult-scale-it").get
            festival = events.find(_.id == "katapult-za-prijaznejso-prihodnost-2026").get
            meetUp   = events.find(_.id == "katapult-meet-up").get
          yield assertTrue(
            scaleIt.title == "SCALE IT! Od prvih prodaj do serijske proizvodnje",
            scaleIt.startDateTime.toLocalDate == d(2026, 10, 23),
            scaleIt.startDateTime.toLocalTime == t(9, 0),
            scaleIt.startDateTime.getOffset.getTotalSeconds == 7200,
            scaleIt.endDateTime.exists(e => e.toLocalDate == d(2026, 10, 29) && e.toLocalTime == t(16, 0)),
            festival.startDateTime.toLocalDate == d(2026, 11, 26),
            !festival.hasStartTime,
            festival.endDateTime.exists(_.toLocalDate == d(2026, 11, 28)),
            !festival.hasEndTime,
            meetUp.title == "Katapult Podjetniški Meet-up",
            meetUp.startDateTime.toLocalDate == d(2025, 12, 16)
          )
        }
      },
      test("the ways Katapult writes when") {
        val published = d(2026, 10, 1)
        assertTrue(
          whenOf("Torek, 17. november 2026, od 9.00 do 12.00", published)
            .contains(When(d(2026, 11, 17), Some(t(9, 0)), None, Some(t(12, 0)))),
          whenOf("Četrtek, 22.10.2026, 9.00 - 12.00", published)
            .contains(When(d(2026, 10, 22), Some(t(9, 0)), None, Some(t(12, 0)))),
          // No year: the publish year; a genitive month.
          whenOf("Četrtek, 22. oktobra, ob 17.30–20.30", d(2026, 9, 22))
            .contains(When(d(2026, 10, 22), Some(t(17, 30)), None, Some(t(20, 30)))),
          whenOf("sreda, 7. oktober 2026, 15.00–18.00", published)
            .contains(When(d(2026, 10, 7), Some(t(15, 0)), None, Some(t(18, 0)))),
          whenOf("Torek, 16.6. 2026 16.00 - 19.00", d(2026, 5, 26))
            .contains(When(d(2026, 6, 16), Some(t(16, 0)), None, Some(t(19, 0)))),
          whenOf("Četrtek, 22. januar, 17.30", d(2026, 1, 6))
            .contains(When(d(2026, 1, 22), Some(t(17, 30)), None, None)),
          // A day without a month takes the next one's.
          whenOf("Od četrtka, 26., do sobote, 28. novembra 2026", published)
            .contains(When(d(2026, 11, 26), None, Some(d(2026, 11, 28)), None)),
          whenOf("23., 27. in 29. oktober 2026, vsak dan od 9.00 do 16.00", published)
            .contains(When(d(2026, 10, 23), Some(t(9, 0)), Some(d(2026, 10, 29)), Some(t(16, 0)))),
          // "9.3.2025" in a post from February 2026: March 2026.
          whenOf("ponedeljek, 9.3.2025, 10.00 - 12.00", d(2026, 2, 10))
            .contains(When(d(2026, 3, 9), Some(t(10, 0)), None, Some(t(12, 0)))),
          whenOf("Torek, 16. 12. 2025, od 16.00 do 19.00", d(2025, 11, 30))
            .contains(When(d(2025, 12, 16), Some(t(16, 0)), None, Some(t(19, 0)))),
          whenOf("november 2026 – april 2027", published).isEmpty
        )
      },
      test("titles without the date and the 'Dogodek:' label") {
        assertTrue(
          cleanTitle("Od ideje do izdelka: vpogled z mentorji Katapulta I 17.11.2026") ==
            "Od ideje do izdelka: vpogled z mentorji Katapulta",
          cleanTitle("Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti. I 26.–28.11.2026") ==
            "Za prijaznejšo prihodnost 2026: Človek. Možgani. Roboti.",
          cleanTitle("Robotika v oskrbi 22.10.2026") == "Robotika v oskrbi",
          cleanTitle("SCALE IT! Od prvih prodaj do serijske proizvodnje (23., 27. in 29. oktober)") ==
            "SCALE IT! Od prvih prodaj do serijske proizvodnje",
          cleanTitle("Dogodek: Iskra podjetnosti za vsakega dijaka (Obisk Katapulta)") ==
            "Iskra podjetnosti za vsakega dijaka (Obisk Katapulta)",
          cleanTitle("Sodobne namizne igre: razvoj, testiranje in izdaja 2.0") ==
            "Sodobne namizne igre: razvoj, testiranje in izdaja 2.0",
          cleanTitle("DELAVNICA: MIGY delavnice") == "DELAVNICA: MIGY delavnice"
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
