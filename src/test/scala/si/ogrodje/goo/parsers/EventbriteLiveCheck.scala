package si.ogrodje.goo.parsers

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.Meetup
import zio.*
import zio.http.{Client, URL}
import zio.test.*

/** Opt-in live check: drives the real EventbriteParser (Chromium navigation) against a live organizer page and asserts
  * the payload is reachable and decodable. Skipped unless `EVENTBRITE_LIVE_CHECK=1`, because it hits the network and
  * depends on the organizer having events. Run with: `EVENTBRITE_LIVE_CHECK=1 sbt "testOnly *EventbriteLiveCheck"`.
  */
object EventbriteLiveCheck extends ZIOSpecDefault:
  // Seattle Aquarium: a large, reliably-active organizer (used to validate the schema).
  private val liveURL = URL.decode("https://www.eventbrite.com/o/seattle-aquarium-16503646468").toOption.get
  private val meetup  = Meetup.make("live", "Live")

  private val enabled = sys.env.get("EVENTBRITE_LIVE_CHECK").contains("1")

  def spec = suite("EventbriteLiveCheck")(
    test("fetches and decodes real upcoming events") {
      for
        events <- EventbriteParser(meetup).streamEventsFrom(liveURL).runCollect
        _      <- ZIO.foreachDiscard(events.take(5)) { e =>
                    Console.printLine(s"  - ${e.startDateTime} ${e.title} @ ${e.locationName.getOrElse("online")}")
                  }
      yield assertTrue(
        events.nonEmpty,
        events.forall(_.title.nonEmpty),
        events.forall(_.eventURL.isDefined)
      )
    }
  ).provide(
    Scope.default,
    Client.default,
    PWright.livePlaywright,
    PWright.liveBrowser
  ) @@ TestAspect.withLiveClock @@ (if enabled then TestAspect.identity else TestAspect.ignore)
