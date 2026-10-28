package si.ogrodje.goo.parsers

import com.microsoft.playwright.{Browser, Page}
import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.models.{Event, Meetup}
import zio.*
import zio.http.{Client, URL}
import zio.test.*

import scala.jdk.CollectionConverters.*

/** Verifies `Parser.withPage` scopes the Playwright page per-parse: the page (and its owning browser context) is closed
  * as soon as the effect completes — including when it fails — rather than leaking onto the long-lived application
  * scope until shutdown. This is the mechanism behind the GZSParser fix, tested in isolation so it needs no real
  * navigation (fast + deterministic).
  */
object ParserWithPageTest extends ZIOSpecDefault:

  // Minimal Parser that exposes withPage for testing. `parse` is unused here.
  final private case class ProbeParser(meetup: Meetup) extends Parser:
    override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] =
      ZIO.succeed(Nil)
    def run[A](use: Page => RIO[Scope, A]): RIO[Scope & Browser, A]                           = withPage(use)

  private val probe = ProbeParser(Meetup.make("probe", "Probe"))

  private def openContexts = ZIO.serviceWith[Browser](_.contexts().asScala.size)

  def spec = suite("ParserWithPageTest")(
    test("closes the page after a successful use") {
      for
        before <- openContexts
        isOpen <- probe.run(page => ZIO.attemptBlocking(page.isClosed))
        after  <- openContexts
      yield assertTrue(!isOpen, after == before)
    },
    test("closes the page even when use fails") {
      for
        before <- openContexts
        result <- probe.run(_ => ZIO.fail(new RuntimeException("boom"))).either
        after  <- openContexts
      yield assertTrue(result.isLeft, after == before)
    },
    test("does not accumulate contexts across repeated calls") {
      for
        before <- openContexts
        _      <- ZIO.foreachDiscard(1 to 5)(_ => probe.run(page => ZIO.attemptBlocking(page.title())))
        after  <- openContexts
      yield assertTrue(after == before)
    }
  ).provideShared(
    Scope.default,
    PWright.livePlaywright,
    PWright.liveBrowser
  ) @@ TestAspect.sequential
