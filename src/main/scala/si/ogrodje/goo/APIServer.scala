package si.ogrodje.goo
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.*
import zio.*
import zio.ZIO.logInfo
import zio.http.*
import zio.http.codec.*
import zio.http.codec.PathCodec.*
import zio.http.endpoint.*
import zio.http.endpoint.openapi.*
import si.ogrodje.goo.info.BuildInfo
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.{cors, CorsConfig}

final class APIServer private (
  private val db: DB
):
  private val dbLayer          = ZLayer.succeed(db)
  private val ogrodjeHome: URL = URL.decode("https://ogrodje.si?from=goo").toOption.get

  private val corsConfig: CorsConfig        = CorsConfig(allowedOrigin = _ => Some(AccessControlAllowOrigin.All))
  private val routes: Routes[Any, Response] = Routes(
    Method.GET / Root -> handler(Response.redirect(ogrodjeHome, isPermanent = true))
  )

  // Endpoints
  private val getMeetups = Endpoint(RoutePattern.GET / "meetups" ?? Doc.p("All meetups"))
    .query(HttpCodec.query[Int]("limit").optional ++ HttpCodec.query[Int]("offset").optional)
    .out[List[Meetup]]

  private val getMeetupEvents = Endpoint(
    RoutePattern.GET / "meetups" / PathCodec.string("meetup_id") / "events" ?? Doc.p("All events for a meetup")
  ).query(HttpCodec.query[Int]("limit").optional ++ HttpCodec.query[Int]("offset").optional)
    .out[List[Event]]

  private val getEvents = Endpoint(RoutePattern.GET / "events" ?? Doc.p("All Events"))
    .query(HttpCodec.query[Int]("limit").optional ++ HttpCodec.query[Int]("offset").optional)
    .out[List[Event]]

  private val createEvent = Endpoint(RoutePattern.POST / "events" ?? Doc.p("Create an event"))
    .in[CreateEvent]
    .out[Event]

  private val getTimeline = Endpoint(RoutePattern.GET / "timeline" ?? Doc.p("Events timeline"))
    .out[List[TimelineEvent]]

  // Routes
  private def meetupsRoute = getMeetups.implement: (maybeLimit, maybeOffset) =>
    Meetups
      .public(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0))
      .tapError(err => ZIO.logErrorCause("Error in events route", Cause.fail(err)))
      .orDie

  private def meetupEventsRoute = getMeetupEvents.implement: (meetupId, maybeLimit, maybeOffset) =>
    Events
      .all(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0), maybeMeetupID = Some(meetupId))
      .tapError(err => ZIO.logErrorCause("Error in events route", Cause.fail(err)))
      .orDie

  private def eventsRoute = getEvents.implement: (maybeLimit, maybeOffset) =>
    Events
      .all(maybeLimit.getOrElse(100), maybeOffset.getOrElse(0))
      .tapError(err => ZIO.logErrorCause("Error in events route", Cause.fail(err)))
      .orDie

  private def createEventRoute = createEvent.implement: createEvent =>
    logInfo(s"Creating event w/ ${createEvent}")
      .as(createEvent.toDBEvent)

  private def timelineRoute = getTimeline.implement: _ =>
    Events
      .timeline(100, 0)
      .tapError(err => ZIO.logErrorCause("Error in events route", Cause.fail(err)))
      .orDie

  private val openAPI =
    OpenAPIGen.fromEndpoints(
      title = "Ogrodje Goo",
      version = BuildInfo.version,
      getMeetups,
      getMeetupEvents,
      getEvents,
      createEvent,
      getTimeline
    )

  private val swaggerRoutes = SwaggerUI.routes("docs" / "openapi", openAPI)

  private def run: ZIO[Any, Throwable, Nothing] = for
    port   <- AppConfig.port
    _      <- logInfo(s"Starting server on port $port")
    server <-
      Server
        .serve(
          routes ++ Routes(meetupsRoute, eventsRoute, meetupEventsRoute, timelineRoute, createEventRoute) @@ cors(
            corsConfig
          ) ++ swaggerRoutes
        )
        .provide(dbLayer, Server.defaultWith(_.port(port)))
  yield server

object APIServer:
  def run: ZIO[DB, Throwable, Nothing] = for
    db <- ZIO.service[DB]
    r  <- new APIServer(db).run
  yield r
