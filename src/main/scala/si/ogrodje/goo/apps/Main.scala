package si.ogrodje.goo.apps

import si.ogrodje.goo.clients.HyGraph
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.sync.MeetupsSync
import si.ogrodje.goo.{APIServer, AppConfig}
import zio.ZIOAppDefault
import zio.*
import zio.logging.backend.SLF4J
import zio.ZIO.logInfo
import zio.http.Client

object Main extends ZIOAppDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(ConfigProvider.envProvider) >>>
      Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private def program = for
    port <- AppConfig.port
    _    <- logInfo(s"Booting on port $port")
    _    <- DB.migrate
    // out  <- ZIO.serviceWithZIO[HyGraph](_.allMeetups)
    // _     = println(out)

    _ <- ZIO.serviceWithZIO[MeetupsSync](_.run)
    _ <- APIServer.run.forever
  yield ()

  def run = program
    .provide(
      Scope.default,
      Client.default,
      HyGraph.live,
      MeetupsSync.live,
      DB.transactorLayer
    )
    .as(ExitCode.success)
