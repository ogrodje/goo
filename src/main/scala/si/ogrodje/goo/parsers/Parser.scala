package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.{Event, Meetup}
import zio.*
import zio.ZIO.{logErrorCause, logInfo}
import zio.http.{Client, URL, ZClientAspect}
import zio.stream.ZStream

trait Parser:
  def meetup: Meetup

  protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]]

  /** Opens a fresh Playwright page in its OWN scope and hands it to `use`.
    *
    * Browser access is serialized through a single global permit ([[Parser.browserSemaphore]]): the Playwright Browser
    * connection is single-threaded and driving it from multiple fibers at once (which flatMapPar in EventsSync does)
    * corrupts it ("Object doesn't exist: frame", "mainFrame is null"). HTTP-based parsers still run fully in parallel;
    * only the two browser-backed ones (GZS, Eventbrite) take turns.
    *
    * The page runs in its own [[com.microsoft.playwright.BrowserContext]] (a fresh, isolated session with a realistic
    * user-agent). Both the context and page are closed as soon as `use` completes — including between retry attempts —
    * instead of leaking onto the long-lived application scope until shutdown.
    */
  final protected def withPage[A](use: com.microsoft.playwright.Page => RIO[Scope, A]): RIO[Scope & Browser, A] =
    // The Playwright Browser connection is single-threaded and NOT safe for concurrent use — driving
    // it from multiple fibers at once (via flatMapPar) corrupts the connection ("Object doesn't
    // exist: frame", "mainFrame is null"). Serialize all browser work through one permit so HTTP
    // parsers still run in parallel while browser parsers take turns.
    Parser.browserSemaphore.withPermit:
      ZIO.scoped:
        for
          browser <- ZIO.service[Browser]
          context <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(browser.newContext(Parser.contextOptions)))
          page    <- ZIO.fromAutoCloseable(ZIO.attemptBlocking(context.newPage()))
          result  <- use(page)
        yield result

  private val followRedirects = ZClientAspect.followRedirects(3)((resp, message) => ZIO.logInfo(message).as(resp))

  private def parseWithClient(url: URL): RIO[Scope & Client & Browser, List[Event]] = for
    client <- ZIO.serviceWith[Client](_ @@ followRedirects)
    events <-
      parse(client, url)
        .tapError(err => logErrorCause(s"Failed to parse $url: ${err.getMessage}", Cause.fail(err)))
        .retryOrElse(
          policy = Schedule.exponential(10.seconds) && Schedule.recurs(3),
          orElse = (err, _) => logErrorCause(s"""Crash with retry: ${err.getMessage}""", Cause.fail(err)).as(Nil)
        )
    _      <- logInfo(s"Collected ${events.size} events from $url") when events.nonEmpty
  yield events

  final def streamEventsFrom(url: URL): ZStream[Scope & Client & Browser, Throwable, Event] =
    ZStream.fromZIO(parseWithClient(url)).flatMap(ZStream.fromIterable)

object Parser:
  // One global permit serializing all Playwright browser access (see withPage). Created eagerly
  // because withPage must not thread a Semaphore through every parser's signature.
  private val browserSemaphore: Semaphore =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(Semaphore.make(1)).getOrThrow())

  // A realistic browser context (desktop Chrome UA + locale) lowers the chance of being flagged as
  // a bot. Applied to every Playwright-backed parse via withPage.
  private val contextOptions: com.microsoft.playwright.Browser.NewContextOptions =
    new com.microsoft.playwright.Browser.NewContextOptions()
      .setUserAgent(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) " +
          "Chrome/120.0.0.0 Safari/537.36"
      )
      .setLocale("en-US")
