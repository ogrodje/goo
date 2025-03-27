package si.ogrodje.goo.sync

import si.ogrodje.goo.models.{Events, Meetup, Meetups, Source}
import zio.*
import zio.http.{Client, URL}
import zio.stream.{Stream, ZStream}
import ZIO.logInfo
import si.ogrodje.goo.AppConfig
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.parsers.*

final class EventsSync:

  private def sourcesOf(meetup: Meetup): List[(Meetup, URL)] =
    meetup.productElementNames
      .zip(meetup.productIterator)
      .collect { case (fieldName, Some(url: URL)) => (meetup, url) }
      .toList

  private def sync: ZIO[Scope & Client & DB, Throwable, Unit] = for
    sourcesList <- AppConfig.sourcesList
    out         <-
      ZStream
        .fromZIO(Meetups.all)
        .flatMap(meetups => ZStream.fromIterable(meetups))
        .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
        .flatMap {
          case (meetup, url)
              if url.host.exists(_.contains("meetup.com")) &&
                sourcesList.enabled(Source.Meetup) =>
            ZStream.fromZIO(MeetupComParser(meetup).parseWithClient(url))
          case (meetup, url)
              if url.host.exists(_.contains("eventbrite.com")) &&
                sourcesList.enabled(Source.Eventbrite) =>
            ZStream.fromZIO(EventbriteParser(meetup).parseWithClient(url))
          case (meetup, url)
              if url.host.exists(_.contains("tp-lj.si")) &&
                sourcesList.enabled(Source.TehnoloskiParkLjubljana) =>
            ZStream.fromZIO(TehnoloskiParkLjubljanaParser(meetup).parseWithClient(url))
          case (meetup, url) =>
            ZStream.empty
        }
        .mapZIO(events => ZIO.foreachDiscard(events)(Events.upsert))
        .runDrain
  yield ()

  def run = for
    _ <- logInfo("Syncing events started.")
    f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing events stopped."))
  yield ()

object EventsSync:
  def live = ZLayer.derive[EventsSync]
