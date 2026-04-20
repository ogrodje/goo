package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.Meetup
import zio.*
import zio.http.*
import zio.test.*
import zio.test.TestAspect

object MeetupComParserTest extends ZIOSpecDefault:
  private val meetup = Meetup.make("test-group", "Test Group")

  private def mockHtml(extraEvents: String = "", extraState: String = "") =
    s"""<!DOCTYPE html><html><body>
       |<script id="__NEXT_DATA__" type="application/json">
       |{
       |  "props": {
       |    "pageProps": {
       |      "__APOLLO_STATE__": {
       |        "Event:123": {
       |          "__typename": "Event",
       |          "id": "123",
       |          "title": "Test Meetup Event",
       |          "description": "A test event description",
       |          "eventUrl": "https://www.meetup.com/test-group/events/123/",
       |          "dateTime": "2026-06-01T18:00:00+02:00",
       |          "endTime": "2026-06-01T20:00:00+02:00",
       |          "venue": null
       |        },
       |        "Event:456": {
       |          "__typename": "Event",
       |          "id": "456",
       |          "title": "Event With Venue",
       |          "description": "Event at a physical venue",
       |          "eventUrl": "https://www.meetup.com/test-group/events/456/",
       |          "dateTime": "2026-07-15T18:00:00+02:00",
       |          "endTime": "2026-07-15T20:00:00+02:00",
       |          "venue": {"__ref": "Venue:789"}
       |        },
       |        "Venue:789": {
       |          "__typename": "Venue",
       |          "id": "789",
       |          "name": "Test Venue Ljubljana",
       |          "address": "Main Street 1",
       |          "city": "Ljubljana",
       |          "state": "Slovenia"
       |        },
       |        "Event:old": {
       |          "__typename": "Event",
       |          "id": "old",
       |          "title": "Old Past Event",
       |          "description": "This event is too old",
       |          "eventUrl": "https://www.meetup.com/test-group/events/old/",
       |          "dateTime": "2024-01-01T18:00:00+01:00",
       |          "endTime": "2024-01-01T20:00:00+01:00",
       |          "venue": null
       |        }
       |        ${if extraEvents.nonEmpty then "," + extraEvents else ""}
       |        ${if extraState.nonEmpty then "," + extraState else ""}
       |      }
       |    }
       |  }
       |}
       |</script>
       |</body></html>""".stripMargin

  private def withEventsRoute[R](html: String)(
    test: RIO[R, TestResult]
  ): RIO[R & TestClient, TestResult] = {
    TestClient
      .addRoute:
        Method.GET / trailing -> Handler.fromFunctionHandler: (request: Request) =>
          Handler.fromZIO(
            ZIO.succeed(Response.text(html))
          )
  } *> test

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] =
    suite("MeetupComParserTest")(
      test("parses events from group root URL") {
        withEventsRoute(mockHtml()) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
          yield assertTrue(
            events.size == 2,
            events.exists(_.title == "Test Meetup Event"),
            events.exists(_.title == "Event With Venue")
          )
        }
      },
      test("normalizes specific event URL to group events listing") {
        withEventsRoute(mockHtml()) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/events/314159976/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
          yield assertTrue(events.size == 2)
        }
      },
      test("enriches event with venue location") {
        withEventsRoute(mockHtml()) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
            venue   = events.find(_.title == "Event With Venue").get
          yield assertTrue(
            venue.locationName.contains("Test Venue Ljubljana"),
            venue.locationAddress.exists(_.contains("Ljubljana"))
          )
        }
      },
      test("event without venue has no location data") {
        withEventsRoute(mockHtml()) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
            noVenue = events.find(_.title == "Test Meetup Event").get
          yield assertTrue(noVenue.locationName.isEmpty, noVenue.locationAddress.isEmpty)
        }
      },
      test("filters out events older than 6 months") {
        withEventsRoute(mockHtml()) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
          yield assertTrue(events.forall(_.title != "Old Past Event"))
        }
      },
      test("returns empty list when __APOLLO_STATE__ is missing") {
        val noStateHtml =
          """<!DOCTYPE html><html><body>
            |<script id="__NEXT_DATA__" type="application/json">
            |{"props":{"pageProps":{}}}
            |</script></body></html>""".stripMargin
        withEventsRoute(noStateHtml) {
          for
            url    <- ZIO.fromEither(URL.decode("https://www.meetup.com/test-group/"))
            events <- MeetupComParser(meetup).streamEventsFrom(url).runCollect
          yield assertTrue(events.isEmpty)
        }
      }
    ).provide(
      Scope.default,
      TestClient.layer,
      PWright.livePlaywright,
      PWright.liveBrowser
    ) @@ TestAspect.withLiveClock
