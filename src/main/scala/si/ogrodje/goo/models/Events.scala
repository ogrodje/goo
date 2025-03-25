package si.ogrodje.goo.models

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import si.ogrodje.goo.db.{DB, DBOps}
import zio.RIO

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
            has_end_time
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
         ${event.hasEndTime}
        ) ON CONFLICT (id) DO UPDATE SET
          title = ${event.title},
          start_date_time = ${event.startDateTime},
          description = ${event.description},
          event_url = ${event.eventURL},
          end_date_time = ${event.endDateTime},
          has_start_time = ${event.hasStartTime},
          has_end_time = ${event.hasEndTime},
          updated_at = now()
        """.updateWithLabel("upsert-event").run

  private val baseFields: Fragment =
    fr"id, meetup_id, source, source_url, title, start_date_time, description, event_url, end_date_time, has_start_time, has_end_time, updated_at"

  def all(limit: Int, offset: Int, maybeMeetupID: Option[MeetupID] = None) =
    val baseQuery = fr"""SELECT $baseFields FROM events """

    val whereFilter = maybeMeetupID match
      case Some(meetupID) => fr"WHERE meetup_id = $meetupID"
      case None           => fr""

    val orderAndLimit = fr"ORDER BY start_date_time DESC LIMIT $limit OFFSET $offset"

    DB.transact((baseQuery ++ whereFilter ++ orderAndLimit).queryWithLabel[Event]("read-events").to[List])
