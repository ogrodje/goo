package si.ogrodje.goo.apps

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.clients.HyGraph
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.scheduler.Scheduler
import si.ogrodje.goo.sync.{EventsSync, MeetupsSync}
import si.ogrodje.goo.{APIServer, AppConfig, SentryOps}
import zio.ZIO.logInfo
import zio.{ZIOAppDefault, *}
import zio.http.Client
import zio.logging.backend.SLF4J
import si.ogrodje.goo.info.BuildInfo

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    environment <- AppConfig.environment
    _           <- SentryOps.setup
    port        <- AppConfig.port
    _           <- DB.migrate
    _           <-
      logInfo(
        s"Booting on port $port, version: ${BuildInfo.version}, w/ ${BuildInfo.scalaVersion}, environment: $environment"
      )

    meetupsSyncFib <- ZIO.serviceWithZIO[MeetupsSync](_.runScheduled).fork
    eventsSyncFib  <- ZIO.serviceWithZIO[EventsSync](_.runScheduled).fork

    _ <- Scope.addFinalizer(meetupsSyncFib.interrupt)
    _ <- Scope.addFinalizer(eventsSyncFib.interrupt)

    _ <- APIServer.run.forever
  yield ()

  def run = program
    .provide(
      Scope.default,
      Scheduler.live,
      Client.default,
      HyGraph.live,
      MeetupsSync.live,
      EventsSync.live,
      DB.transactorLayer,
      PWright.livePlaywright,
      PWright.liveBrowser
    )
