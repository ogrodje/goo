package si.ogrodje.goo

import si.ogrodje.goo.models.Source
import zio.*
import zio.Config.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.refined.*
import zio.http.URL
import zio.prelude.NonEmptyList

final case class SourcesList private (private val sources: NonEmptyList[Source]):
  def enabled(source: Source): Boolean = sources.exists(_ == source)

object SourcesList:
  private val All = "All"

  def fromString(str: String): SourcesList =
    val items   = str.split(",").map(_.strip)
    val sources =
      if items.exists(_.toLowerCase == All.toLowerCase) then
        NonEmptyList
          .fromIterableOption(Source.values)
          .getOrElse(throw new RuntimeException("Failed loading list"))
      else
        NonEmptyList
          .fromIterableOption(items.map(Source.withName))
          .getOrElse(throw new RuntimeException("Failed loading list"))

    SourcesList(sources = sources)

type Port = Int
final case class AppConfig(
  @name("port")
  port: Port,
  @name("postgres_user")
  postgresUser: String,
  @name("postgres_password")
  postgresPassword: String,
  @name("postgres_host")
  postgresHost: String,
  @name("postgres_port")
  postgresPort: Int,
  @name("postgres_db")
  postgresDb: String,
  @name("hygraph_endpoint")
  hygraphEndpoint: URL,
  @name("sources")
  sourcesList: SourcesList
)

object AppConfig:
  private given Config[URL] = Config.string.mapOrFail: raw =>
    URL.decode(raw).left.map(err => zio.Config.Error.InvalidData(message = err.getMessage))

  private given Config[SourcesList] = Config.string.mapAttempt(SourcesList.fromString)

  private def configDefinition: Config[AppConfig] = deriveConfig[AppConfig]
  def config: ZIO[Any, Error, AppConfig]          = ZIO.config(configDefinition)
  def port: IO[Error, Port]                       = config.map(_.port)
  def portLayer: TaskLayer[Port]                  = ZLayer.fromZIO(port)
  def sourcesList: IO[Error, SourcesList]         = config.map(_.sourcesList)
