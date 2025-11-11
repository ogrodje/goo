package si.ogrodje.goo.models

import si.ogrodje.goo.*
import si.ogrodje.goo.db.DB
import zio.*
import zio.test.*
import zio.http.*
import zio.Console.printLine
import java.time.OffsetDateTime

object TimelineTest extends ZIOSpecDefault:
  def spec = suite("TimelineTest") {
    test("general timeline") {
      for
        _  <- ZIO.serviceWithZIO[AppConfig](DB.migrate)
        now = OffsetDateTime.of(2025, 11, 9, 0, 0, 0, 0, OffsetDateTime.now().getOffset)
        _  <- printLine(s"Now is ${now.toLocalDate}")

        (from, to, timeline) <- Events.upcomingFor(EventView.Weekly, now)
        _                    <- printLine(s"Upcoming events from ${from.toLocalDate} to ${to.toLocalDate}")
        _                     =
          timeline.foreach { event =>
            println(
              s"${event.startDateTime.toLocalDate} - ${event.endDateTime.map(_.toLocalDate).getOrElse("None")} " +
                s"${event.title}"
            )
          }
      yield assertCompletes
    }
  }.provideShared(
    mkAppConfig,
    DB.transactionLayerFromAppConfig
  )
    @@ TestAspect.withLiveClock @@ TestAspect.withLiveSystem

  private def mkAppConfig: TaskLayer[AppConfig] = ZLayer.fromZIO:
    for
      db         <- zio.System.env("POSTGRES_DB").flatMap(ZIO.getOrFail)
      dbPort     <- zio.System.env("POSTGRES_PORT").flatMap(ZIO.getOrFail).map(_.toInt)
      dbHost     <- zio.System.env("POSTGRES_HOST").flatMap(ZIO.getOrFail)
      dbPassword <- zio.System.env("POSTGRES_PASSWORD").flatMap(ZIO.getOrFail)
      dbUser     <- zio.System.env("POSTGRES_USER").flatMap(ZIO.getOrFail)
    yield AppConfig(
      port = 7771,
      postgresUser = dbUser,
      postgresPassword = dbPassword,
      postgresHost = dbHost,
      postgresPort = dbPort,
      postgresDb = db,
      hygraphEndpoint = URL.decode("http://xx").toTry.get,
      sourcesList = SourcesList.fromString("All"),
      environment = Environment.Test,
      keycloakRealm = "x",
      keycloakEndpoint = URL.decode("http://keycloak").toTry.get
    )
