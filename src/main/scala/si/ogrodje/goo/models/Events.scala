package si.ogrodje.goo.models

import enumeratum.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import si.ogrodje.goo.db.{DB, DBOps}
import zio.RIO
import zio.http.URL
import zio.schema.{DeriveSchema, Schema}

import java.time.OffsetDateTime

sealed trait EventGrouping extends EnumEntry
object EventGrouping       extends Enum[EventGrouping] with CirceEnum[EventGrouping]:
  case object Day   extends EventGrouping
  case object Week  extends EventGrouping
  case object Month extends EventGrouping
  val values = findValues

final case class TimelineEvent(
  id: String,
  meetupID: String,
  source: Source,
  sourceURL: URL,
  title: String,
  meetupName: String,
  description: Option[String],
  eventURL: Option[URL],
  locationName: Option[String],
  locationAddress: Option[String],
  startDateTime: OffsetDateTime,
  hasStartTime: Boolean,
  endDateTime: OffsetDateTime,
  hasEndTime: Boolean
)

object TimelineEvent:
  given Schema[URL] =
    Schema.primitive[String].transform(str => URL.decode(str).toOption.get, _.encode)

  given schema: Schema[TimelineEvent] = DeriveSchema.gen

object Events:
  import DBOps.given

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

  private val baseFields: Fragment =
    fr"id, meetup_id, source, source_url, title, start_date_time, description, " ++
      fr"event_url, end_date_time, has_start_time, has_end_time, updated_at, " ++
      fr"location_name, location_address"

  def all(limit: Int, offset: Int, maybeMeetupID: Option[MeetupID] = None) =
    val baseQuery = fr"""SELECT $baseFields FROM events """

    val whereFilter = maybeMeetupID match
      case Some(meetupID) => fr"WHERE meetup_id = $meetupID"
      case None           => fr""

    val orderAndLimit = fr"ORDER BY start_date_time DESC LIMIT $limit OFFSET $offset"

    DB.transact((baseQuery ++ whereFilter ++ orderAndLimit).queryWithLabel[Event]("read-events").to[List])

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
             e.has_end_time
        FROM events e
        LEFT JOIN meetups m ON e.meetup_id = m.id
        WHERE 
          m.stage = 'PUBLISHED' AND
          e.start_date_time >= now()
        ORDER BY e.start_date_time ASC"""

    DB.transact(
      baseQuery
        .queryWithLabel[TimelineEvent]("events-timeline")
        .to[List]
    )
