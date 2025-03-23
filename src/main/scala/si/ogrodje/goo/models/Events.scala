package si.ogrodje.goo.models

import si.ogrodje.goo.db.{DB, DBOps}
import zio.{RIO, ZIO}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

object Events:
  import DBOps.{*, given}

  def upsert(event: Event): RIO[DB, Int] = DB.transact(
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
  )
