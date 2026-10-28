package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.*
import zio.http.{Client, URL}
import zio.stream.ZStream
import zio.test.*

import java.time.OffsetDateTime

/** Verifies the per-parser isolation contract that keeps collection robust: a parser whose `parse` fails must NOT
  * propagate a failure out of `streamEventsFrom` — it retries, then degrades to an empty stream. This is what lets one
  * broken source (e.g. a bot-walled Eventbrite) fail without taking down the rest of the sync.
  */
object ParserRobustnessTest extends ZIOSpecDefault:
  private val meetup  = Meetup.make("robust", "Robust")
  private val someURL = URL.decode("https://example.com").toOption.get

  // Always throws inside parse.
  final private case class AlwaysFailingParser(meetup: Meetup) extends Parser:
    override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] =
      ZIO.fail(new RuntimeException("boom: source is down"))

  // Succeeds with one event.
  final private case class WorkingParser(meetup: Meetup) extends Parser:
    override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] =
      ZIO.succeed(
        List(
          Event.empty("ok-1", meetup.id, Source.Manual, url, "Works", OffsetDateTime.parse("2026-09-01T10:00:00Z"))
        )
      )

  // The parser retries with exponential(10s) x3 before giving up, so drive the TestClock forward
  // instead of waiting ~70s of wall-clock.
  private def runWithClockDrained[A](stream: ZStream[Scope & Client & Browser, Throwable, A]) =
    for
      fib     <- stream.runCollect.fork
      _       <- TestClock.adjust(90.seconds)
      results <- fib.join
    yield results

  def spec = suite("ParserRobustnessTest")(
    test("a failing parser degrades to an empty stream instead of failing") {
      for exit <- runWithClockDrained(AlwaysFailingParser(meetup).streamEventsFrom(someURL)).exit
      yield assertTrue(exit.isSuccess, exit.getOrElse(_ => Chunk.empty).isEmpty)
    },
    test("a working parser still yields its events") {
      for events <- runWithClockDrained(WorkingParser(meetup).streamEventsFrom(someURL))
      yield assertTrue(events.size == 1, events.head.title == "Works")
    },
    test("merging a failing and a working source keeps the working one (par isolation)") {
      // Mirrors EventsSync.flatMapPar: a down source must not starve/kill a healthy one.
      val merged = ZStream
        .fromIterable(List(AlwaysFailingParser(meetup), WorkingParser(meetup): Parser))
        .flatMapPar(4)(_.streamEventsFrom(someURL))
      for
        fib    <- merged.runCollect.fork
        _      <- TestClock.adjust(90.seconds)
        events <- fib.join
      yield assertTrue(events.size == 1, events.head.title == "Works")
    }
  ).provide(
    Scope.default,
    Client.default,
    PWright.livePlaywright,
    PWright.liveBrowser
  )
