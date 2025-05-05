package si.ogrodje.goo.models

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import enumeratum.*
import si.ogrodje.goo.db.{DB, DBOps}
import zio.http.URL
import zio.{RIO, ZIO}

import java.time.OffsetDateTime

sealed trait EventGrouping extends EnumEntry
object EventGrouping       extends Enum[EventGrouping] with CirceEnum[EventGrouping]:
  case object Day   extends EventGrouping
  case object Week  extends EventGrouping
  case object Month extends EventGrouping
  val values = findValues

object Events:
  import DBOps.{*, given}

  def upsert(event: Event): RIO[DB, Int] = DB.transact:
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
        """.updateWithLabel("upsert-event").run

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

  def public(
    limit: Int,
    offset: Int,
    maybeMeetupID: Option[MeetupID] = None,
    maybeQuery: Option[String] = None
  ): RIO[DB, List[Event]] =
    val baseQuery = maybeQuery match
      case Some(searchQuery) =>
        fr"""
            SELECT $baseFields, m.name AS meetup_name,
            (ts_rank_cd(e.title_vec, query) * 2 + ts_rank_cd(m.name_vec, query)) *
            (1 + 1.0/(extract(epoch from (e.start_date_time - CURRENT_DATE))/86400 + 1)) AS rank
            FROM events e
              LEFT JOIN meetups m ON e.meetup_id = m.id,
              websearch_to_tsquery($searchQuery) query
        """
      case None              => fr"""SELECT $baseFields FROM events e """

    val whereFilter = maybeMeetupID -> maybeQuery match
      case (Some(meetupID), _) => fr"WHERE meetup_id = $meetupID"
      case (_, Some(_))        => fr"WHERE query @@ e.title_vec OR query @@ m.name_vec"
      case _                   => fr""

    val orderAndLimit = maybeQuery match
      case Some(value) => fr"ORDER BY rank DESC LIMIT $limit OFFSET $offset"
      case None        => fr"ORDER BY start_date_time DESC LIMIT $limit OFFSET $offset"

    DB.transact((baseQuery ++ whereFilter ++ orderAndLimit).queryWithLabel[Event]("read-events").to[List])

  def find(eventID: EventID): RIO[DB, Event] =
    val baseQuery = fr"""SELECT $baseFields FROM events e WHERE e.id = $eventID LIMIT 1"""

    DB.transact(baseQuery.queryWithLabel[Event]("read-event").option)
      .map(_.getOrElse(throw new Exception("Event not found")))

  def timeline(
    limit: Int,
    offset: Int,
    groupBy: EventGrouping = EventGrouping.Day
  ): RIO[DB, List[TimelineEvent]] =
    val baseQuery =
      fr"""SELECT
             e.id,
             e.meetup_id,
             e.source,
             e.source_url,
             e.title,
             m.name as meetup_name,
             e.description,
             e.event_url,
             e.location_name,
             e.location_address,
             e.start_date_time,
             e.has_start_time,
             e.end_date_time,
             e.has_end_time,
             e.hidden_at,
             e.promoted_at
        FROM events e
        LEFT JOIN meetups m ON e.meetup_id = m.id
        WHERE
          m.stage = 'PUBLISHED'
          AND e.hidden_at IS NULL
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

    DB.transact(baseQuery.queryWithLabel[TimelineEvent]("events-timeline").to[List])
