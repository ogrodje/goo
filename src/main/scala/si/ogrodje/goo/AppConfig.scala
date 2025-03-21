package si.ogrodje.goo

import zio.*
import zio.Config.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.refined.*
import zio.http.URL

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
  hygraphEndpoint: URL
)

object AppConfig:
  private given Config[URL] = Config.string.mapOrFail { raw =>
    URL.decode(raw).left.map(err => zio.Config.Error.InvalidData(message = err.getMessage))
  }

  private def configDefinition: Config[AppConfig] = deriveConfig[AppConfig]
  def config: ZIO[Any, Error, AppConfig]          = ZIO.config(configDefinition)
  def port: IO[Error, Port]                       = config.map(_.port)
  def portLayer: TaskLayer[Port]                  = ZLayer.fromZIO(port)
