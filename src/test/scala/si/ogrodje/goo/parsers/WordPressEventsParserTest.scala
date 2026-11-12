package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object WordPressEventsParserTest extends ZIOSpecDefault:
  import EventText.{details, linesOf, similarTitles}
  import WordPressEventsParser.{InkubatorBelaKrajina, InkubatorRogaskaSlatina, PomurskiTehnoloskiPark, Site}

  private val asOf                        = LocalDate.of(2026, 11, 12)
  private def d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
  private def t(h: Int, m: Int)           = LocalTime.of(h, m)

  // Post bodies as the sites write them (copied from live posts, trimmed).
  private val ptechMarked     =
    """<p><strong>Vabimo vas na delavnico, ki bo 19. novembra 2026, s pričetkom ob 16.00 uri v prostorih Pomurskega tehnološkega parka.</strong></p>
      |<p class="wp-block-paragraph"><strong>📅</strong> <strong>Datum:&nbsp;19. november 2026</strong></p>
      |<p class="wp-block-paragraph"><strong>⏰</strong> <strong>Čas:&nbsp;16.00&nbsp;– 19.00</strong></p>
      |<p class="wp-block-paragraph"><strong>📍</strong> <strong>Kraj:&nbsp;Pomurski tehnološki park, Plese 9a, 9000 Murska Sobota</strong></p>""".stripMargin
  private val ptechQuestion   =
    """<p class="wp-block-paragraph">Dogodek bo tudi priložnost za vaša vprašanja.</p>
      |<p class="wp-block-paragraph"><strong>KDAJ?</strong> 21.10.26 ob 16.00</p>
      |<p class="wp-block-paragraph"><strong>KJE?</strong> Občina Ljutomer (3. nadstropje sejna soba)</p>""".stripMargin
  private val ptechSentence   =
    """<p>Vabimo vas na info dogodek »Kako do sredstev za investicije in razvoj?«, ki bo dne 27.8.2026 s pričetkom ob 9.30 uri v Pomurskem tehnološkem parku.</p>
      |<p>9.30–9.45 : Uvodni pozdrav organizatorja dogodka</p>
      |<p>Prijave sprejemamo najkasneje do 25.8.2026.</p>""".stripMargin
  private val ptechOld        = """<p>Termin: Sreda, 20. 11. 2024, 10:00 – 12:00</p>"""
  private val belaChanged     =
    """<p style="text-align: center;"><span>📅 <strong>KDAJ:</strong> petek, 20. november 2026, ob 17.00 &#8211; 19.00.</span><span><br />
      |</span><span> 📍<strong>LOKACIJA:</strong> Podjetniški inkubator Bela krajina, Belokranjska cesta 36,&nbsp; 8340 Črnomelj.</span></p>
      |<h3 style="text-align: center;">PRIJAVA NA DOGODEK TUKAJ:</h3>""".stripMargin
  private val belaFirst       =
    """<p>📅 <strong>KDAJ:</strong> petek, 20. november 2026, ob 17.00 &#8211; 19.00.<br />📍<strong>LOKACIJA:</strong> Podjetniški inkubator Bela krajina</p>"""
  private val belaNews        =
    """<p>Na tekmovanju POPRI 2026 so se v finale uvrstile tri belokranjske ekipe. Čestitamo!</p>"""
  private val rogaskaEmoji    =
    """<p class="wp-block-paragraph">📅 Četrtek, 19.11.2026 ob 13.00<br>📍 Preko spleta</p>
      |<h3 class="wp-block-heading">O predavateljici</h3>""".stripMargin
  private val rogaskaSentence =
    """<p>Prijave zbiramo do ponedeljka, 23. novembra 2026, oziroma do zapolnitve mest.</p>
      |<p class="has-text-align-center">ki bo v četrtek<strong>, 26. novembra 2026, ob 12. uri</strong><strong>,</strong></p>
      |<p class="has-text-align-center">v sejni sobi PC Vrelec, Celjska cesta 30, Rogaška Slatina.</p>""".stripMargin

  private def post(id: Long, published: String, host: String, slug: String, title: String, content: String): Json =
    Json.obj(
      "id"      -> Json.fromLong(id),
      "date"    -> Json.fromString(s"${published}T10:00:00"),
      "link"    -> Json.fromString(s"https://$host/$slug/"),
      "title"   -> Json.obj("rendered" -> Json.fromString(title)),
      "content" -> Json.obj("rendered" -> Json.fromString(content))
    )

  private val ptechPosts   = List(
    post(2681, "2026-10-19", "www.p-tech.si", "google-ads", "Google Ads od osnov do B2B lead generationa", ptechMarked),
    post(
      2656,
      "2026-10-07",
      "www.p-tech.si",
      "ljutomer",
      "Odprt razpis za razvoj podjetij v Občini Ljutomer",
      ptechQuestion
    ),
    post(
      2517,
      "2026-06-22",
      "www.p-tech.si",
      "info-dogodek",
      "Info dogodek: Kako do sredstev za investicije in razvoj?",
      ptechSentence
    ),
    post(1800, "2024-11-05", "www.p-tech.si", "staro", "Vabilo na dogodek iz leta 2024", ptechOld)
  )
  private val belaPosts    = List(
    post(
      5692,
      "2026-11-02",
      "inkubator-belakrajina.si",
      "sprememba",
      "SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?",
      belaChanged
    ),
    post(
      5650,
      "2026-10-20",
      "inkubator-belakrajina.si",
      "kmetijstvo",
      "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?",
      belaFirst
    ),
    post(
      5600,
      "2026-03-18",
      "inkubator-belakrajina.si",
      "popri",
      "Bela krajina ima 3 finaliste tekmovanja POPRI",
      belaNews
    )
  )
  private val rogaskaPosts = List(
    post(
      1500,
      "2026-11-09",
      "www.inkubator-rs.si",
      "bolniske",
      "Zakonodajni in praktični vidiki obvladovanja ter nadzora bolniških odsotnosti",
      rogaskaSentence
    ),
    post(
      1490,
      "2026-11-05",
      "www.inkubator-rs.si",
      "sp-ali-doo",
      "S.p. ali d.o.o.? Koliko podjetniku dejansko ostane?",
      rogaskaEmoji
    )
  )

  private def route[R](posts: List[Json])(test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        request.url.path.toString match
          case "/wp-json/wp/v2/categories" if request.url.queryParams.getAll("slug").contains("dogodki") =>
            Handler.fromResponse(Response.text("""[{"id":27,"slug":"dogodki"}]"""))
          case "/wp-json/wp/v2/posts" if request.url.queryParams.getAll("categories").contains("27")     =>
            Handler.fromResponse(Response.text(Json.fromValues(posts).noSpaces).addHeader("X-WP-TotalPages", "1"))
          case _                                                                                         => Handler.notFound
  } *> test

  private def events(site: Site, home: String) =
    WordPressEventsParser(Meetup.make("m", "M"), site, asOf = Some(asOf))
      .streamEventsFrom(URL.decode(home).toOption.get)
      .runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("WordPressEventsParserTest")(
      test("Pomurski tehnološki park: marked, KDAJ?/KJE? and sentence posts; older than 12 months left out") {
        route(ptechPosts) {
          for
            events <- events(PomurskiTehnoloskiPark, "https://www.p-tech.si")
            ads     = events.find(_.id == "ptech-2681").get
            ljut    = events.find(_.id == "ptech-2656").get
            info    = events.find(_.id == "ptech-2517").get
          yield assertTrue(
            events.map(_.id).toSet == Set("ptech-2681", "ptech-2656", "ptech-2517"),
            events.forall(_.source == Source.PomurskiTehnoloskiPark),
            ads.startDateTime.toLocalDate == d(2026, 11, 19),
            ads.startDateTime.toLocalTime == t(16, 0),
            ads.startDateTime.getOffset.getTotalSeconds == 3600,
            ads.endDateTime.exists(_.toLocalTime == t(19, 0)),
            ads.locationName.contains("Pomurski tehnološki park, Plese 9a, 9000 Murska Sobota"),
            ads.eventURL.exists(_.toString == "https://www.p-tech.si/google-ads/"),
            ads.sourceURL.toString == "https://www.p-tech.si/kategorija/dogodki/",
            ljut.startDateTime.toLocalDate == d(2026, 10, 21),
            ljut.startDateTime.toLocalTime == t(16, 0),
            ljut.locationName.contains("Občina Ljutomer (3. nadstropje sejna soba)"),
            info.startDateTime.toLocalDate == d(2026, 8, 27),
            info.startDateTime.toLocalTime == t(9, 30),
            info.startDateTime.getOffset.getTotalSeconds == 7200,
            info.locationName.isEmpty
          )
        }
      },
      test("Bela krajina: one event for a post and its date-change post (the newer wins); news skipped") {
        route(belaPosts) {
          for
            events <- events(InkubatorBelaKrajina, "https://inkubator-belakrajina.si")
            event   = events.head
          yield assertTrue(
            events.map(_.id) == List("belakrajina-5692"),
            event.title == "SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?",
            event.startDateTime.toLocalDate == d(2026, 11, 20),
            event.startDateTime.toLocalTime == t(17, 0),
            event.endDateTime.exists(_.toLocalTime == t(19, 0)),
            event.locationName.contains("Podjetniški inkubator Bela krajina, Belokranjska cesta 36, 8340 Črnomelj")
          )
        }
      },
      test("Rogaška Slatina: 📅 line, and a sentence with a full hour after a registration deadline") {
        route(rogaskaPosts) {
          for
            events <- events(InkubatorRogaskaSlatina, "https://www.inkubator-rs.si")
            emoji   = events.find(_.id == "inkubatorrs-1490").get
            hour    = events.find(_.id == "inkubatorrs-1500").get
          yield assertTrue(
            emoji.startDateTime.toLocalDate == d(2026, 11, 19),
            emoji.startDateTime.toLocalTime == t(13, 0),
            emoji.locationName.contains("Preko spleta"),
            hour.startDateTime.toLocalDate == d(2026, 11, 26),
            hour.startDateTime.toLocalTime == t(12, 0),
            hour.hasStartTime,
            !hour.hasEndTime
          )
        }
      },
      test("sentences are read only where the site says so") {
        val lines = linesOf(ptechSentence)
        assertTrue(
          details(lines, d(2026, 6, 22)).isEmpty,
          details(lines, d(2026, 6, 22), sentences = true).map(_.startDate).contains(d(2026, 8, 27)),
          details(linesOf(belaNews), d(2026, 3, 18), sentences = true).isEmpty
        )
      },
      test("same event, by title") {
        assertTrue(
          similarTitles(
            "SPREMEMBA DATUMA DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?",
            "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?"
          ),
          !similarTitles("Pitch trening", "Labs to Market: Kako pripraviti dober pitch"),
          !similarTitles("Canva Masterclass", "Uvodni tečaj Canva"),
          similarTitles(
            "Izdelava spletnih strani z uporabo AI orodij",
            "Izdelava spletnih strani z uporabo AI orodij – novembrski termin"
          )
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
