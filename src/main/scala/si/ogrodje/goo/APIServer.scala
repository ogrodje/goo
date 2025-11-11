package si.ogrodje.goo
import si.ogrodje.goo.server.{AuthUser, Authentication, Keycloak}
import si.ogrodje.goo.BaseError.{AppError, AuthenticationError}
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.info.BuildInfo
import si.ogrodje.goo.models.*
import si.ogrodje.goo.sync.SyncEngine
import zio.*
import zio.ZIO.logInfo
import zio.http.*
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.{cors, CorsConfig}
import zio.http.codec.*
import zio.http.codec.HttpCodec.query
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import zio.json.{jsonDiscriminator, jsonHintNames, SnakeCase}
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.schema.*
import zio.schema.annotation.discriminatorName

import java.nio.charset.{Charset, StandardCharsets}

@jsonHintNames(SnakeCase)
@jsonDiscriminator("type")
@discriminatorName("type")
enum BaseError(error: String) extends Throwable(error):
  case AppError(error: String)            extends BaseError(error)
  case AuthenticationError(error: String) extends BaseError(error)

object BaseError:
  given schema: Schema[BaseError] = DeriveSchema.gen

object AppError:
  given schema: Schema[AppError]            = DeriveSchema.gen
  def fromThrowable(t: Throwable): AppError = BaseError.AppError(t.getMessage)

final class APIServer:
  private given Schema[AppError]            = DeriveSchema.gen
  private given Schema[AuthenticationError] = DeriveSchema.gen

  private val ogrodjeHome: URL = URL.decode("https://ogrodje.si?from=goo").toOption.get

  private val corsConfig: CorsConfig = CorsConfig(
    allowedOrigin = _ => Some(AccessControlAllowOrigin.All),
    allowedMethods = Header.AccessControlAllowMethods.All
  )

  private def routes: Routes[Any, Response] = Routes(
    Method.GET / Root -> handler(Response.redirect(ogrodjeHome, isPermanent = true))
  )

  // Endpoints
  private val getMe = Endpoint(RoutePattern.GET / "me" ?? Doc.p("Me"))
    .auth(AuthType.Bearer)
    .out[AuthUser]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val syncAllMeetups = Endpoint(RoutePattern.POST / "meetups" / "sync" ?? Doc.p("Sync meetups"))
    .auth(AuthType.Bearer)
    .out[String]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val getMeetups = Endpoint(RoutePattern.GET / "meetups" ?? Doc.p("All meetups"))
    .query(
      query[Int]("limit").optional ++ query[Int]("offset").optional ++ query[String]("query").optional
    )
    .out[List[Meetup]]
    .outError[AppError](Status.BadRequest)

  private val getMeetupEvents = Endpoint(
    RoutePattern.GET / "meetups" / PathCodec.string("meetup_id") / "events" ?? Doc.p("All events for a meetup")
  ).query(
    query[Int]("limit").optional ++ query[Int]("offset").optional
  ).out[List[Event]]
    .outError[AppError](Status.BadRequest)

  private val getEvents = Endpoint(RoutePattern.GET / "events" ?? Doc.p("All Events"))
    .query(
      query[Int]("limit").optional ++ query[Int]("offset").optional ++ query[String]("query").optional
    )
    .out[List[Event]]
    .outError[AppError](Status.BadRequest)

  private val createEvent = Endpoint(RoutePattern.POST / "events" ?? Doc.p("Create an event"))
    .auth(AuthType.Bearer)
    .in[CreateEvent]
    .out[Event]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val updateEvent = Endpoint(
    RoutePattern.PUT / "events" / PathCodec.string("event_id") ?? Doc.p("Update an event")
  )
    .auth(AuthType.Bearer)
    .in[CreateEvent]
    .out[Event]
    .outErrors[BaseError](
      HttpCodec.error[AppError](Status.BadRequest),
      HttpCodec.error[AuthenticationError](Status.Unauthorized)
    )

  private val getTimeline = Endpoint(RoutePattern.GET / "timeline" ?? Doc.p("Events timeline"))
    .out[List[Event]]
    .outError[AppError](Status.BadRequest)

  // Routes
  private def getMeRoute = getMe.implement: (_: Unit) =>
    withContext((authUser: AuthUser) => authUser)

  private def meetupsRoute = getMeetups.implement: (maybeLimit, maybeOffset, maybeQuery) =>
    Meetups
      .public(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0), maybeQuery.filterNot(_.isEmpty))
      .mapError(AppError.fromThrowable)

  private def meetupEventsRoute = getMeetupEvents.implement: (meetupId, maybeLimit, maybeOffset) =>
    Events
      .public(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0), maybeMeetupID = Some(meetupId), maybeQuery = None)
      .mapError(AppError.fromThrowable)

  private def eventsRoute = getEvents.implement: (maybeLimit, maybeOffset, maybeQuery) =>
    Events
      .public(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0), maybeQuery = maybeQuery.filterNot(_.isEmpty))
      .mapError(AppError.fromThrowable)

  private def createEventRoute = createEvent.implement: event =>
    withContext((u: AuthUser) => Events.authedCreate(event)(u))
      .mapError(AppError.fromThrowable)

  private def updateEventRoute = updateEvent.implement: (id, event) =>
    withContext((u: AuthUser) => Events.authedUpdate(id, event)(u))
      .mapError(AppError.fromThrowable)

  private def timelineRoute = getTimeline.implement: _ =>
    Events.timeline().mapError(AppError.fromThrowable)

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Ogrodje Goo",
      version = BuildInfo.version,
      getMe,
      syncAllMeetups,
      getMeetups,
      getMeetupEvents,
      getEvents,
      createEvent,
      updateEvent,
      getTimeline
    )

  private def swaggerRoutes: Routes[Any, Response] = SwaggerUI.routes("docs" / "openapi", openAPI)

  private def publicRoutes: Routes[DB, Nothing] = Routes(
    meetupsRoute,
    eventsRoute,
    meetupEventsRoute,
    timelineRoute
  )

  private def syncMeetups(authUser: AuthUser): RIO[Scope & DB & scheduler.Scheduler & SyncEngine, String] = for
    _ <- logInfo(s"Sync started by ${authUser.name} (${authUser.email})")
    _ <- ZIO.serviceWithZIO[SyncEngine](_.runMeetupsSync)
  yield "Ok"

  private def syncAllMeetupsRoute(scope: Scope) = syncAllMeetups.implement: (_: Unit) =>
    withContext((authUser: AuthUser) => syncMeetups(authUser).provideSomeLayer(ZLayer.succeed(scope)))
      .mapError(AppError.fromThrowable)

  private def authedRoutes(scope: Scope) = Routes(
    syncAllMeetupsRoute(scope),
    getMeRoute,
    createEventRoute,
    updateEventRoute
  ) @@ Authentication.Authenticated @@ Middleware.debug

  private val prometheusRoute: Routes[PrometheusPublisher, Response] = Routes(
    Method.GET / "metrics" -> handler {
      ZIO
        .serviceWithZIO[PrometheusPublisher](_.get)
        .map(response =>
          Response(
            status = Status.Ok,
            // headers = Headers(Header.Custom(Header.ContentType.name, "text/plain; version=0.0.4")),
            headers = Headers(Header.ContentType(MediaType.text.plain, charset = Some(Charset.forName("UTF-8")))),
            body = Body.fromString(response, StandardCharsets.UTF_8)
          )
        )
    }
  )

  private def metricServer = for
    internalPort <- AppConfig.port.map(_ + 1)
    _            <- logInfo(s"Starting internal metrics server on port $internalPort")
    server       <- Server
                      .serve(routes = prometheusRoute)
                      .provideSomeLayer(Server.defaultWith(_.port(internalPort)))
  yield server

  private def run = for
    scope <- ZIO.service[Scope] // This needs to be explicit or bad things happen.
    _     <- AppConfig.port.tap(port => logInfo(s"Starting server on port $port"))

    _ <- metricServer.fork

    serving =
      (
        routes ++ (publicRoutes ++ authedRoutes(scope)) @@ Middleware.metrics() ++ swaggerRoutes
      ) @@ cors(corsConfig)
    server <- Server.serve(routes = serving)
  yield server

object APIServer:
  def run = for
    port   <- AppConfig.port
    server <- new APIServer().run.mapError { err =>
                new RuntimeException(s"Boom ${err.getMessage()}")
              }.provideSomeLayer(Server.defaultWith(_.port(port)))
  yield server
