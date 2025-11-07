package si.ogrodje.goo.db

import org.flywaydb.core.Flyway
import si.ogrodje.goo.AppConfig
import zio.*
import zio.ZIO.logInfo
import zio.interop.catz.*
import doobie.*
import doobie.implicits.*
import io.circe.Json
import si.ogrodje.goo.models.Source
import zio.http.URL
import io.circe.parser.parse
import org.flywaydb.core.api.output.MigrateResult
final class DB private (private val transactor: Transactor[Task]):
  def call: Transactor[Task] = transactor

object DBOps:
  given urlMeta: Meta[URL]   = Meta[String].imap(URL.decode(_).toTry.get)(_.toString)
  given source: Meta[Source] = Meta[String].imap(Source.withName)(_.entryName)
  given json: Meta[Json]     = Meta[String].tiemap(parse(_).left.map(_.getMessage))(_.noSpaces)

object DB:

  private def flyway(appConfig: AppConfig): Task[Flyway] = for
    _       <- ZIO.unit
    db       = appConfig.postgresDb
    host     = appConfig.postgresHost
    port     = appConfig.postgresPort
    user     = appConfig.postgresUser
    password = appConfig.postgresPassword

    configuredFlyway =
      Flyway
        .configure()
        .locations("migrations")
        .dataSource(s"jdbc:postgresql://$host:$port/$db", user, password)
        .load()
  yield configuredFlyway

  def migrate: Task[MigrateResult] = for
    appConfig     <- AppConfig.config
    migrateResult <- migrate(appConfig)
  yield migrateResult

  def migrate(appConfig: AppConfig): Task[MigrateResult] = for
    _             <- logInfo(s"Migrating database ${appConfig.postgresDb} with user ${appConfig.postgresUser}")
    migrateResult <- flyway(appConfig).map(_.migrate())
  yield migrateResult

  def transact[Out](in: ConnectionIO[Out]): RIO[DB, Out] =
    ZIO.serviceWithZIO[DB](d => in.transact(d.call))

  def transactorLayer: TaskLayer[DB] = ZLayer.fromZIO:
    for
      db       <- AppConfig.config.map(_.postgresDb)
      host     <- AppConfig.config.map(_.postgresHost)
      port     <- AppConfig.config.map(_.postgresPort)
      user     <- AppConfig.config.map(_.postgresUser)
      password <- AppConfig.config.map(_.postgresPassword)

      tx =
        Transactor.fromDriverManager[Task](
          driver = "org.postgresql.Driver",
          url = s"jdbc:postgresql://$host:$port/$db",
          user = user,
          password = password,
          logHandler = None
        )
    yield DB(tx)

  def transactionLayerFromAppConfig: URLayer[AppConfig, DB] = ZLayer.fromZIO:
    for
      appConfig <- ZIO.service[AppConfig]
      db         = appConfig.postgresDb
      host       = appConfig.postgresHost
      port       = appConfig.postgresPort
      user       = appConfig.postgresUser
      password   = appConfig.postgresPassword

      tx =
        Transactor.fromDriverManager[Task](
          driver = "org.postgresql.Driver",
          url = s"jdbc:postgresql://$host:$port/$db",
          user = user,
          password = password,
          logHandler = None
        )
    yield DB(tx)
