package si.ogrodje.goo.parsers

import io.circe.Json
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.*
import zio.test.*

import java.time.{LocalDate, LocalTime}

object LUIParserTest extends ZIOSpecDefault:
  import LUIParser.{details, linesOf, Details}

  private val meetup = Meetup.make("lui", "LUI")
  private val host   = "https://lui.si"

  private def d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
  private def t(h: Int, m: Int)           = LocalTime.of(h, m)

  // Post bodies as LUI writes them (copied from live posts, trimmed).
  private val inline    =
    """<p>Aktivnosti se izvajajo v okviru operacije JR podporno okolje 2026 – 2029.</p>
      |<p><strong>KDAJ: </strong>27. oktober 2026, med 13:00 – 16:00<br><strong>KJE:</strong> LUI – Ljubljanski univerzitetni inkubator, Vojkova cesta 63, Ljubljana</p>
      |<p>PRIJAVI SE</p>""".stripMargin
  private val separate  =
    """<p>Kdaj in kje:</p><p>Datum: torek, 13. oktober 2026</p><p>Čas: 14.00–17.00</p>
      |<p>Lokacija: Ljubljanski univerzitetni inkubator (LUI), Vojkova cesta 63, Ljubljana</p>""".stripMargin
  private val noYear    =
    """<p><strong>Kdaj:</strong> Ponedeljek, 31. 8. ob 9.00 (do 12.00)<br><strong>Kje:</strong> Ljubljanski univerzitetni inkubator (LUI)</p>"""
  private val twoDays   =
    """<p>Čas za sproščen pogovor in spoznavanje novih ljudi.</p>
      |<p><strong>KDAJ:</strong> petek, 25. september in sobota, 26. september 2026<br><strong>LOKACIJA:</strong> LUI, Vojkova cesta 63, Ljubljana (2. nadstropje)</p>""".stripMargin
  private val emoji     = """<p>📅 7. oktober 2026, 10.00–13.00</p><p>📍 LUI, Vojkova cesta 63, Ljubljana</p>"""
  // A question with "kdaj" in it, then a heading with the details on the following lines.
  private val heading   =
    """<p>Kateri procesi so ozko grlo? In kdaj se investicija dejansko izplača?</p>
      |<p><strong>KDAJ:</strong></p><p>Petek, 30. oktober 2026 – od 12.00 do 24.00</p><p>Sobota, 31. oktober 2026 – od 9.00 do 16.00</p>
      |<p><strong>KJE:</strong></p><p>Petek, 16. oktober 2026 – zbirno mesto je LUI</p>""".stripMargin
  private val wrongYear = """<p>Kdaj: Sklop 1: 3. februar 2025, 8.00–16.00 Sklop 2: 4. februar 2025, 8.00–16.00</p>"""
  private val numeric   = """<p>Kdaj: 16.5.2026 ob 19.00 Kje: LUI, Vojkova cesta 63, Ljubljana</p>"""
  private val programme = """<p>Labs to Market je program za raziskovalne skupine. Prijave do 10. marca 2026.</p>"""

  private def post(id: Long, published: String, slug: String, title: String, content: String): Json =
    Json.obj(
      "id"              -> Json.fromLong(id),
      "date"            -> Json.fromString(s"${published}T09:00:00"),
      "link"            -> Json.fromString(s"$host/dogodki/$slug/"),
      "title"           -> Json.obj("rendered" -> Json.fromString(title)),
      "content"         -> Json.obj("rendered" -> Json.fromString(content)),
      "yoast_head_json" -> Json.obj("description" -> Json.fromString(s"Opis: $title"))
    )

  // Three pages exist; the parser reads two.
  private val pages: Map[String, List[Json]] = Map(
    "1" -> List(
      post(5101, "2026-10-05", "canva-masterclass-2", "Canva Masterclass", inline),
      post(5102, "2026-10-02", "labs-to-market-pitch", "Labs to Market: Kako pripraviti dober pitch", separate)
    ),
    "2" -> List(
      post(5103, "2026-02-06", "labs-to-market-2026", "Labs to Market 2026", programme),
      post(5104, "2026-09-07", "startup-vikend", "Startup vikend &#8211; Prebudi potencial", twoDays)
    ),
    "3" -> List(post(4000, "2024-01-01", "star", "Star dogodek", inline))
  )

  private def route[R](test: RIO[R, TestResult]): RIO[R & TestClient, TestResult] = {
    TestClient.addRoute:
      Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
        val path = request.url.path.toString
        if path == "/wp-json/wp/v2/categories" && request.url.queryParams.getAll("slug").contains("dogodki") then
          Handler.fromResponse(Response.text("""[{"id":27,"slug":"dogodki"}]"""))
        else if path == "/wp-json/wp/v2/posts" && request.url.queryParams.getAll("categories").contains("27") then
          val page = request.url.queryParams.getAll("page").headOption.getOrElse("1")
          pages.get(page) match
            case Some(posts) =>
              Handler.fromResponse(
                Response.text(Json.fromValues(posts).noSpaces).addHeader("X-WP-TotalPages", pages.size.toString)
              )
            case None        => Handler.fromResponse(Response.status(Status.BadRequest))
        else Handler.notFound
  } *> test

  private val homepage = URL.decode(s"$host/").toOption.get

  private def events = LUIParser(meetup).streamEventsFrom(homepage).runCollect

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("LUIParserTest")(
      test("lists the 'dogodki' category's two newest pages and skips posts without an event date") {
        route {
          for events <- events
          yield assertTrue(
            events.map(_.id).toSet == Set("lui-5101", "lui-5102", "lui-5104"),
            events.forall(_.source == Source.LUI),
            events.exists(_.title == "Startup vikend – Prebudi potencial")
          )
        }
      },
      test("an event with a time range: start, end, place, description, link") {
        route {
          for
            events <- events
            event   = events.find(_.id == "lui-5101").get
          yield assertTrue(
            event.startDateTime.toLocalDate == d(2026, 10, 27),
            event.startDateTime.toLocalTime == t(13, 0),
            event.startDateTime.getOffset.getTotalSeconds == 3600, // CET: after the change on 25 October
            event.endDateTime.exists(_.toLocalTime == t(16, 0)),
            event.hasStartTime,
            event.hasEndTime,
            event.locationName.contains("LUI – Ljubljanski univerzitetni inkubator, Vojkova cesta 63, Ljubljana"),
            event.description.contains("Opis: Canva Masterclass"),
            event.eventURL.exists(_.toString == s"$host/dogodki/canva-masterclass-2/"),
            event.sourceURL.path.toString == "/aktualno/"
          )
        }
      },
      test("a two-day event without a time is all-day and ends on its second day") {
        route {
          for
            events <- events
            event   = events.find(_.id == "lui-5104").get
          yield assertTrue(
            event.startDateTime.toLocalDate == d(2026, 9, 25),
            !event.hasStartTime,
            event.endDateTime.exists(_.toLocalDate == d(2026, 9, 26)),
            !event.hasEndTime
          )
        }
      },
      test("reads the ways LUI writes when and where") {
        def of(html: String, published: LocalDate) = details(linesOf(html), published)
        assertTrue(
          of(inline, d(2026, 10, 5)).contains(
            Details(
              d(2026, 10, 27),
              Some(t(13, 0)),
              Some(t(16, 0)),
              None,
              Some("LUI – Ljubljanski univerzitetni inkubator, Vojkova cesta 63, Ljubljana")
            )
          ),
          of(separate, d(2026, 10, 2)).contains(
            Details(
              d(2026, 10, 13),
              Some(t(14, 0)),
              Some(t(17, 0)),
              None,
              Some("Ljubljanski univerzitetni inkubator (LUI), Vojkova cesta 63, Ljubljana")
            )
          ),
          // No year: the publish year.
          of(noYear, d(2026, 8, 8)).contains(
            Details(
              d(2026, 8, 31),
              Some(t(9, 0)),
              Some(t(12, 0)),
              None,
              Some("Ljubljanski univerzitetni inkubator (LUI)")
            )
          ),
          of(twoDays, d(2026, 9, 7)).exists(x => x.startDate == d(2026, 9, 25) && x.endDate.contains(d(2026, 9, 26))),
          of(emoji, d(2026, 9, 25)).contains(
            Details(d(2026, 10, 7), Some(t(10, 0)), Some(t(13, 0)), None, Some("LUI, Vojkova cesta 63, Ljubljana"))
          ),
          // The question's "kdaj" is not a marker; "do 24.00" is the end of the day; per-day directions aren't a place.
          of(heading, d(2026, 10, 5)).contains(
            Details(d(2026, 10, 30), Some(t(12, 0)), Some(t(23, 59)), Some(d(2026, 10, 31)), None)
          ),
          // "2025" in a post published in January 2026 for February: next year.
          of(wrongYear, d(2026, 1, 12)).exists(x => x.startDate == d(2026, 2, 3) && x.startTime.contains(t(8, 0))),
          of(numeric, d(2026, 4, 30)).contains(
            Details(d(2026, 5, 16), Some(t(19, 0)), None, None, Some("LUI, Vojkova cesta 63, Ljubljana"))
          ),
          of(programme, d(2026, 2, 6)).isEmpty
        )
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
