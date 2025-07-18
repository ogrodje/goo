package si.ogrodje.goo.parsers
import zio.*
import si.ogrodje.goo.ZIP
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.Meetup
import zio.test.{assertCompletes, TestAspect, ZIOSpecDefault}
import zio.http.*

import zio.test.Spec
import zio.test.TestEnvironment

object TehnoloskiParkLjubljanaParserTest extends ZIOSpecDefault:
  import si.ogrodje.goo.EventOps.*

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("TehnoloskiParkLjubljanaParserTest") {

    test("should parse") {
      for
        zipMap <- ZIP.all("/tp-koledar-dogodkov.zip")
        _      <-
          TestClient.addRoute:
            Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
              val responseZIO =
                for
                  path     <- ZIO.succeed(request.url.path)
                  response <-
                    if path.toString == "/sl/koledar-dogodkov" then
                      zipMap.get("tp-koledar-dogodkov.html").map(Response.text(_))
                    else if path.toString.contains("nacionalna-financna") then
                      zipMap.get("tp-nacionalna-financna-klinika.html").map(Response.text(_))
                    else if path.toString.contains("podjetniska-akademija") then
                      zipMap.get("tp-podjetniska-akademija.html").map(Response.text(_))
                    else if path.toString.contains("arena") then
                      zipMap.get("tp-arena-tehnologij.html").map(Response.text(_))
                    else ZIO.fail(new Exception("No response for path"))
                yield response

              Handler.fromZIO(responseZIO).catchAll(_ => Handler.badRequest("Sorry. Boom."))

        homepageUrl = URL.decode("https://www.tp-lj.si").toOption.get
        meetup      = Meetup.make("tplj", "Tehnoloski park Ljubljana").copy(homepageUrl = Some(homepageUrl))
        allEvents  <- TehnoloskiParkLjubljanaParser(meetup).streamEventsFrom(homepageUrl).runCollect

        events =
          allEvents.filter(e =>
            e.title.contains("Nacionalna") || e.title.contains("Arena") || e.title.contains("akademija")
          )
        _      = events.foreach(_.prettyPrint)
      yield assertCompletes
    }
  }.provide(
    Scope.default,
    TestClient.layer,
    PWright.livePlaywright,
    PWright.liveBrowser
  ) @@ TestAspect.withLiveClock
