package si.ogrodje.goo.db

import org.flywaydb.core.Flyway
import si.ogrodje.goo.AppConfig
import zio.*
import zio.ZIO.logInfo
import zio.interop.catz.*
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
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

  // Pool size for the JDBC connection pool. Kept small on purpose: the service is I/O bound
  // on scraping, and Postgres connections are a scarce resource.
  private val maxPoolSize: Int = 10

  private def pooledTransactor(appConfig: AppConfig): RIO[Scope, Transactor[Task]] =
    val url = s"jdbc:postgresql://${appConfig.postgresHost}:${appConfig.postgresPort}/${appConfig.postgresDb}"
    for
      connectEC  <- ExecutionContexts.fixedThreadPool[Task](maxPoolSize).toScopedZIO
      transactor <-
        HikariTransactor
          .newHikariTransactor[Task](
            driverClassName = "org.postgresql.Driver",
            url = url,
            user = appConfig.postgresUser,
            pass = appConfig.postgresPassword,
            connectEC = connectEC
          )
          .evalTap(_.configure(ds => ZIO.succeed(ds.setMaximumPoolSize(maxPoolSize))))
          .toScopedZIO
      _          <- logInfo(s"Initialized Hikari connection pool (maxPoolSize=$maxPoolSize) for $url")
    yield transactor

  def transactorLayer: TaskLayer[DB] = ZLayer.scoped:
    AppConfig.config.flatMap(pooledTransactor).map(DB(_))

  def transactionLayerFromAppConfig: RLayer[AppConfig, DB] = ZLayer.scoped:
    ZIO.service[AppConfig].flatMap(pooledTransactor).map(DB(_))
