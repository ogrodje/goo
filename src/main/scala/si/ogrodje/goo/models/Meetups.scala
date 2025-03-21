package si.ogrodje.goo.models

import si.ogrodje.goo.db.{DB, DBOps}
import zio.{RIO, ZIO}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

object Meetups:
  import DBOps.*
  import DBOps.given

  def all: RIO[DB, List[Meetup]] = DB.transact(
    sql"""
         |SELECT 
         |id, 
         |name, 
         |homepage_url,
         |meetup_url,
         |discord_url,
         |linkedin_url,
         |kompot_url,
         |ical_url,
         |eventbrite_url,
         |created_at,
         |updated_at
         |FROM meetups""".stripMargin.queryWithLabel[Meetup]("all-meetups").to[List]
  )

  def insert(meetups: Meetup*): RIO[DB, Unit] = ZIO.foreachDiscard(meetups.toSeq): meetup =>
    DB.transact:
      sql"""
           |INSERT INTO meetups (
           |id,
           |name,
           |homepage_url,
           |meetup_url,
           |discord_url,
           |linkedin_url,
           |kompot_url,
           |ical_url,
           |eventbrite_url,
           |created_at,
           |updated_at
           |) VALUES (
           |${meetup.id}, ${meetup.name}, ${meetup.homepageUrl},
           |${meetup.meetupUrl}, ${meetup.discordUrl}, ${meetup.linkedinUrl}, ${meetup.kompotUrl},
           |${meetup.icalUrl}, ${meetup.eventbriteUrl}, ${meetup.createdAt}, ${meetup.updatedAt}
           |)
           |ON CONFLICT (id) DO UPDATE SET
           |name = ${meetup.name},
           |homepage_url = ${meetup.homepageUrl},
           |meetup_url = ${meetup.meetupUrl},
           |discord_url = ${meetup.discordUrl},
           |linkedin_url = ${meetup.linkedinUrl},
           |kompot_url = ${meetup.kompotUrl},
           |ical_url = ${meetup.icalUrl},
           |eventbrite_url = ${meetup.eventbriteUrl}""".stripMargin.update.run

  def update(meetups: Meetup*): RIO[DB, Unit] = ZIO.foreachDiscard(meetups.toSeq): meetup =>
    DB.transact:
      sql"""
           UPDATE meetups SET (
            name, homepage_url, meetup_url, discord_url, linkedin_url, kompot_url, ical_url, eventbrite_url, updated_at
           ) = (
            ${meetup.name}, ${meetup.homepageUrl}, ${meetup.meetupUrl}, ${meetup.discordUrl}, ${meetup.linkedinUrl}, 
            ${meetup.kompotUrl}, ${meetup.icalUrl}, ${meetup.eventbriteUrl}, ${meetup.updatedAt}
         ) WHERE id = ${meetup.id}""".update.run
