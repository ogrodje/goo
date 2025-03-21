package si.ogrodje.goo
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.{Meetup, Meetups}
import zio.*
import zio.ZIO.logInfo
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*

final class APIServer private (
  private val db: DB
):
  private val dbLayer = ZLayer.succeed(db)

  private val routes: Routes[Any, Response] = Routes(
    Method.GET / Root -> handler(Response.text("Hello, world!"))
  )

  val getMeetups = Endpoint((RoutePattern.GET / "meetups") ?? Doc.p("List of all meetups"))
    .out[List[Meetup]](Doc.p("List of all meetups"))

  private val meetupsRoute = getMeetups.implement(_ => Meetups.all.provideSome(dbLayer).orDie).sandbox

  private val openAPI       = OpenAPIGen.fromEndpoints(title = "Ogrodje Goo", version = "1.0", getMeetups)
  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)

  private def run = for
    port   <- AppConfig.port
    _      <- logInfo(s"Starting server on port $port")
    server <-
      Server
        .serve(routes ++ meetupsRoute.toRoutes ++ swaggerRoutes)
        .provideSome(Server.defaultWith(_.port(port)))
  yield server

object APIServer:
  def run = for
    db <- ZIO.service[DB]
    r  <- new APIServer(db).run
  yield r
