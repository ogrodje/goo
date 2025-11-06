package si.ogrodje.goo.models

import si.ogrodje.goo.{AppConfig, Environment, SourcesList}
import si.ogrodje.goo.db.DB
import zio.*
import zio.test.*
import zio.http.*

object TimelineTest extends ZIOSpecDefault:

  private val mockConfigProvider: ZLayer[Any, Nothing, Unit] =
    Runtime.setConfigProvider(
      ConfigProvider.fromMap(
        Map(
          "SOURCES"           -> "All",
          "KEYCLOAK_ENDPOINT" -> "http://localhost:8080",
          "GOO_ENVIRONMENT"   -> "Development"
        )
      )
    )

  def spec = suite("TimelineTest") {
    test("general timeline") {
      for
        _        <- ZIO.serviceWithZIO[AppConfig](c => DB.migrate(c))
        timeline <- Events.timeline
        _         = println(timeline.size)
      yield assertCompletes
    }
  }.provideShared(
    mkConfigLayer,
    DB.transactionLayerFromAppConfig
  )
    @@ TestAspect.withLiveClock @@ TestAspect.withLiveSystem

  private def mkConfigLayer: TaskLayer[AppConfig] = ZLayer.fromZIO:
    for
      db         <- zio.System.env("POSTGRES_DB").flatMap(ZIO.getOrFail)
      dbPort     <- zio.System.env("POSTGRES_PORT").flatMap(ZIO.getOrFail)
      dbHost     <- zio.System.env("POSTGRES_HOST").flatMap(ZIO.getOrFail)
      dbPassword <- zio.System.env("POSTGRES_PASSWORD").flatMap(ZIO.getOrFail)
      dbUser     <- zio.System.env("POSTGRES_USER").flatMap(ZIO.getOrFail)
    yield AppConfig(
      port = 7771,
      postgresUser = dbUser,
      postgresPassword = dbPassword,
      postgresHost = dbHost,
      postgresPort = dbPort.toInt,
      postgresDb = db,
      hygraphEndpoint = URL.decode("http://xx").toTry.get,
      sourcesList = SourcesList.fromString("All"),
      environment = Environment.Test,
      keycloakRealm = "x",
      keycloakEndpoint = URL.decode("http://keycloak").toTry.get
    )
