package si.ogrodje.goo.apps

import si.ogrodje.goo.browser.PWright
import si.ogrodje.goo.clients.HyGraph
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.scheduler.Scheduler
import si.ogrodje.goo.sync.{EventsSync, MeetupsSync, SyncEngine}
import si.ogrodje.goo.{APIServer, AppConfig, SentryOps}
import zio.ZIO.logInfo
import zio.{ZIOAppDefault, *}
import zio.http.Client
import zio.logging.backend.SLF4J
import si.ogrodje.goo.info.BuildInfo
import si.ogrodje.goo.server.Keycloak
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.metrics.connectors.{prometheus, MetricsConfig}
import zio.metrics.jvm.DefaultJvmMetrics

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private val metricsConfig = ZLayer.succeed(MetricsConfig(5.seconds))

  private def program = for
    environment <- AppConfig.environment
    sourcesList <- AppConfig.sourcesList.map(_._1.toList)
    _           <- SentryOps.setup
    _           <- DB.migrate

    _ <-
      logInfo(
        s"Booting version: ${BuildInfo.version}, w/ ${BuildInfo.scalaVersion}, " +
          s"environment: $environment, " +
          s"sources: ${sourcesList.mkString(", ")}"
      )

    syncEngineFib <- SyncEngine.start.fork
    _             <- Scope.addFinalizer(syncEngineFib.interrupt)
    _             <- APIServer.run.forever

    _ <- syncEngineFib.join
  yield ()

  def run: ZIO[Environment & (ZIOAppArgs & Scope), Any, Any] =
    program
      .provide(
        Scope.default,
        Scheduler.live,
        Client.default,
        HyGraph.live,
        MeetupsSync.live,
        EventsSync.live,
        SyncEngine.live,
        DB.transactorLayer,
        PWright.livePlaywright,
        PWright.liveBrowser,
        Keycloak.live,

        // Metrics
        metricsConfig,
        prometheus.publisherLayer,
        prometheus.prometheusLayer,
        Runtime.enableRuntimeMetrics,
        DefaultJvmMetrics.liveV2.unit
      )
