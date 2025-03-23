package si.ogrodje.goo.sync

import si.ogrodje.goo.models.{Events, Meetup, Meetups}
import zio.*
import zio.http.{Client, URL}
import zio.stream.{Stream, ZStream}
import ZIO.logInfo
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.parsers.MeetupComParser

final class EventsSync:

  private def sourcesOf(meetup: Meetup): List[(Meetup, URL)] =
    meetup.productElementNames
      .zip(meetup.productIterator)
      .collect { case (fieldName, Some(url: URL)) => (meetup, url) }
      .toList

  private def sync: ZIO[Scope & Client & DB, Throwable, Unit] =
    ZStream
      .fromZIO(Meetups.all)
      .flatMap(meetups => ZStream.fromIterable(meetups))
      .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
      .flatMap {
        case (meetup, url) if url.host.exists(_.contains("meetup.com")) =>
          ZStream.fromZIO(MeetupComParser(meetup).parseWithClient(url))

        case (meetup, url) => ZStream.empty
      }
      .mapZIO(events => ZIO.foreachDiscard(events)(Events.upsert))
      // .tap(p => logInfo(s"Parsed ${p.size} events."))
      .runDrain

  def run = for
    _ <- logInfo("Syncing events started.")
    f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing events stopped."))
  yield ()

object EventsSync:
  def live = ZLayer.derive[EventsSync]
