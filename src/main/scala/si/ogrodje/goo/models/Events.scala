package si.ogrodje.goo.models

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import enumeratum.*
import io.circe.Json
import si.ogrodje.goo.db.{DB, DBOps}
import si.ogrodje.goo.server.AuthUser
import zio.{RIO, ZIO}

sealed trait EventGrouping extends EnumEntry
object EventGrouping       extends Enum[EventGrouping] with CirceEnum[EventGrouping]:
  case object Day   extends EventGrouping
  case object Week  extends EventGrouping
  case object Month extends EventGrouping
  val values: IndexedSeq[EventGrouping] = findValues

object Events:
  import DBOps.given

  def upsert(event: Event): RIO[DB, Either[Throwable, UpsertResult]] = DB.transact:
    sql"""
          INSERT INTO events (
            id,
            meetup_id,
            source,
            source_url,
            title,
            start_date_time,
            description,
            event_url,
            end_date_time,
            has_start_time,
            has_end_time,
            location_name,
            location_address
        ) VALUES (
         ${event.id},
         ${event.meetupID},
         ${event.source.entryName},
         ${event.sourceURL},
         ${event.title},
         ${event.startDateTime},
         ${event.description},
         ${event.eventURL},
         ${event.endDateTime},
         ${event.hasStartTime},
         ${event.hasEndTime},
         ${event.locationName},
         ${event.locationAddress}
        ) ON CONFLICT (id) DO UPDATE SET
          title = ${event.title},
          source = ${event.source.entryName},
          source_url = ${event.sourceURL},
          start_date_time = ${event.startDateTime},
          description = ${event.description},
          event_url = ${event.eventURL},
          end_date_time = ${event.endDateTime},
          has_start_time = ${event.hasStartTime},
          has_end_time = ${event.hasEndTime},
          location_name = ${event.locationName},
          location_address = ${event.locationAddress},
          updated_at = now()
        RETURNING
          CASE WHEN xmax = 0 THEN 'INSERTED' ELSE 'UPDATED' END as operation,
          id as event_id
        """.queryWithLabel[(String, String)]("upsert-event").unique.map {
      case ("INSERTED", eventID) => Right(UpsertResult.Inserted(eventID))
      case ("UPDATED", eventID)  => Right(UpsertResult.Updated(eventID))
      case _                     => Left(new RuntimeException("Unknown upsert result"))
    }

  def create(event: Event): RIO[DB, Int] = for
    _      <- ZIO.fromOption(event.eventURL).orElseFail(new Exception("Event URL is required"))
    result <- DB.transact:
                sql"""
            INSERT INTO events (
              id,
              meetup_id,
              source,
              source_url,
              event_url,
              title,
              start_date_time,
              end_date_time,
              description,
              location_name,
              location_address,
              updated_at
            ) VALUES (
              ${event.id},
              ${event.meetupID},
              ${event.source.entryName},
              ${event.sourceURL},
              ${event.eventURL},
              ${event.title},
              ${event.startDateTime},
              ${event.endDateTime},
              ${event.description},
              ${event.locationName},
              ${event.locationAddress},
              now()
            )
      """.updateWithLabel("create-event").run
  yield result

  def authedCreate(createEvent: CreateEvent)(authUser: AuthUser): RIO[DB, Event] = for
    _               <- ZIO.logInfo(s"Creating event: ${createEvent.title} by ${authUser.`preferred_username`}")
    (event, result) <- ZIO
                         .attempt(createEvent.toDBEvent)
                         .flatMap(e => Events.create(e).map(r => e -> r))
    _               <- ZIO.logInfo(s"Created event: ${event.id} result: $result")
  yield event

  def authedUpdate(eventId: String, createEvent: CreateEvent)(authUser: AuthUser): RIO[DB, Event] = for
    _           <- ZIO.logInfo(s"Updating event: ${createEvent.title} by ${authUser.`preferred_username`}")
    dbEvent     <- Events.find(eventId)
    _           <- Meetups.find(dbEvent.meetupID)
    updatedEvent = createEvent.toDBEvent
    event        = dbEvent.copy(
                     meetupID = updatedEvent.meetupID,
                     title = updatedEvent.title,
                     eventURL = updatedEvent.eventURL,
                     startDateTime = updatedEvent.startDateTime,
                     endDateTime = updatedEvent.endDateTime,
                     description = updatedEvent.description,
                     locationName = updatedEvent.locationName,
                     locationAddress = updatedEvent.locationAddress,
                     hiddenAt = updatedEvent.hiddenAt,
                     promotedAt = updatedEvent.promotedAt
                   )
    saved       <- Events.update(event)
    _           <- ZIO.logInfo(s"Updated event: ${event.id} result: $saved")
    refreshed   <- Events.find(eventId)
  yield refreshed

  def update(event: Event): RIO[DB, Int] = DB.transact:
    sql"""
          UPDATE events SET
            meetup_id = ${event.meetupID},
            title = ${event.title},
            event_url = ${event.eventURL},
            start_date_time = ${event.startDateTime},
            end_date_time = ${event.endDateTime},
            description = ${event.description},
            location_name = ${event.locationName},
            location_address = ${event.locationAddress},
            hidden_at = ${event.hiddenAt},
            promoted_at = ${event.promotedAt},
            updated_at = now()
          WHERE events.id = ${event.id}
    """.stripMargin.updateWithLabel("update-event").run

  private val baseFields: Fragment =
    fr"e.id, meetup_id, source, source_url, title, start_date_time, description, " ++
      fr"event_url, end_date_time, has_start_time, has_end_time, e.updated_at, " ++
      fr"location_name, location_address, hidden_at, promoted_at"

  private val meetupToJson: Fragment =
    fr"json_build_object('id', m.id,  'name', m.name, 'hidden', m.hidden, " ++
      fr"'createdAt', m.created_at, 'updatedAt', m.updated_at, " ++
      fr"'logoImage', m.logo_image, 'mainColor', m.main_color, 'backgroundColor', m.background_color ) as meetup"

  // TODO: Remove this if it is not needed.
  private given Meta[Meetup] = Meta[Json].tiemap(json => json.as[Meetup].left.map(_.getMessage))(pom =>
    Json.obj("id" -> Json.fromString("x"), "name" -> Json.fromString("x"), "stage" -> Json.fromString("x"))
  )

  def public(
    limit: Int,
    offset: Int,
    maybeEventID: Option[EventID] = None,
    maybeMeetupID: Option[MeetupID] = None,
    maybeQuery: Option[String] = None
  ): RIO[DB, List[Event]] =
    val baseQuery   = maybeQuery match
      case Some(searchQuery) =>
        fr"""
            SELECT $baseFields,
            (ts_rank_cd(e.title_vec, query) * 2 + ts_rank_cd(m.name_vec, query)) *
            (1 + 1.0/(extract(epoch from (e.start_date_time - CURRENT_DATE))/86400 + 1)) AS rank,
            m.name AS meetup_name,
            $meetupToJson
            FROM events e
              LEFT JOIN meetups m ON e.meetup_id = m.id,
              websearch_to_tsquery($searchQuery) query
        """
      case None              =>
        fr"""SELECT $baseFields,
          -1 as rank,
          m.name AS meetup_name,
          $meetupToJson
        FROM events e
          LEFT JOIN meetups m ON e.meetup_id = m.id
        """
    val whereFilter = (maybeEventID, maybeMeetupID, maybeQuery) match
      case (Some(eventID), Some(meetupID), _) => fr"WHERE e.id = $eventID AND meetup_id = $meetupID"
      case (Some(eventID), None, _)           => fr"WHERE e.id = $eventID"
      case (_, Some(meetupID), Some(_))       =>
        fr"WHERE meetup_id = $meetupID AND query @@ e.title_vec OR query @@ m.name_vec"
      case (None, Some(meetupID), _)          => fr"WHERE meetup_id = $meetupID"
      case (_, _, Some(_))                    => fr"WHERE query @@ e.title_vec OR query @@ m.name_vec"
      case _                                  => fr""

    val orderAndLimit = maybeQuery match
      case Some(value) => fr"ORDER BY rank DESC LIMIT $limit OFFSET $offset"
      case None        => fr"ORDER BY start_date_time DESC LIMIT $limit OFFSET $offset"

    DB.transact((baseQuery ++ whereFilter ++ orderAndLimit).queryWithLabel[Event]("read-events").to[List])

  def find(eventID: EventID): RIO[DB, Event] =
    public(1, 0, Some(eventID), None, None)
      .map(_.headOption)
      .flatMap(ZIO.fromOption(_).orElseFail(new Exception("Event not found")))

  def timeline(
    limit: Int,
    offset: Int,
    groupBy: EventGrouping = EventGrouping.Day
  ): RIO[DB, List[Event]] =
    val baseQuery =
      fr"""SELECT
             $baseFields,
             -1 as rank,
             m.name AS meetup_name,
             $meetupToJson
        FROM events e
        LEFT JOIN meetups m ON e.meetup_id = m.id
        WHERE
          m.stage = 'PUBLISHED' AND
          m.hidden IS false AND
          e.hidden_at IS NULL
          AND (
            start_date_time >= now() OR
            (
                date_trunc('week', now())::date <= start_date_time::date
                  AND start_date_time::date < (date_trunc('week', now()) + interval '7 days')::date
                )
                OR
            (
                date_trunc('week', now())::date <= end_date_time::date
                  AND end_date_time::date < (date_trunc('week', now()) + interval '7 days')::date
                )
            )
        ORDER BY e.start_date_time"""

    DB.transact(baseQuery.queryWithLabel[Event]("events-timeline").to[List])
