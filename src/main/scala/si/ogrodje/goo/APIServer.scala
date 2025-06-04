package si.ogrodje.goo
import si.ogrodje.goo.server.{AuthUser, Authentication, Keycloak}
import si.ogrodje.goo.BaseError.{AppError, AuthenticationError}
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.info.BuildInfo
import si.ogrodje.goo.models.*
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
import zio.schema.*
import zio.schema.annotation.discriminatorName

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

final class APIServer private (
  private val db: DB,
  private val keycloak: Keycloak
):
  private given Schema[AppError]            = DeriveSchema.gen
  private given Schema[AuthenticationError] = DeriveSchema.gen

  private val dbLayer          = ZLayer.succeed(db)
  private val keycloakLayer    = ZLayer.succeed(keycloak)
  private val ogrodjeHome: URL = URL.decode("https://ogrodje.si?from=goo").toOption.get

  private val corsConfig: CorsConfig        = CorsConfig(allowedOrigin = _ => Some(AccessControlAllowOrigin.All))
  private val routes: Routes[Any, Response] = Routes(
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
    Events.timeline(100, 0).mapError(AppError.fromThrowable)

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Ogrodje Goo",
      version = BuildInfo.version,
      getMe,
      getMeetups,
      getMeetupEvents,
      getEvents,
      createEvent,
      updateEvent,
      getTimeline
    )

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)

  private val publicRoutes = Routes(
    meetupsRoute,
    eventsRoute,
    meetupEventsRoute,
    timelineRoute
  )

  private val authRoutes = Routes(
    getMeRoute,
    createEventRoute,
    updateEventRoute
  ) @@ Authentication.Authenticated @@ Middleware.debug

  private def run: ZIO[Any, Throwable, Nothing] = for
    port   <- AppConfig.port
    _      <- logInfo(s"Starting server on port $port")
    serving =
      (routes ++ publicRoutes ++ authRoutes) @@ cors(corsConfig) ++ swaggerRoutes
    server <-
      Server
        .serve(routes = serving)
        .provide(dbLayer, keycloakLayer, Server.defaultWith(_.port(port)))
  yield server

object APIServer:
  def run: ZIO[DB & Keycloak, Throwable, Nothing] = for
    db       <- ZIO.service[DB]
    keycloak <- ZIO.service[Keycloak]
    server   <- new APIServer(db, keycloak).run
  yield server
