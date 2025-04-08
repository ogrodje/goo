package si.ogrodje.goo.sync

import si.ogrodje.goo.models.*
import zio.*
import zio.http.*
import zio.stream.{Stream, ZStream}
import ZIO.logInfo
import com.microsoft.playwright.Browser
import si.ogrodje.goo.AppConfig
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.Source.{Eventbrite, GZS, PrimorskiTehnoloskiPark, TehnoloskiParkLjubljana}
import si.ogrodje.goo.parsers.*
import si.ogrodje.goo.scheduler.ScheduleOps.*
import si.ogrodje.goo.scheduler.Scheduler

final class EventsSync:

  private def sourcesOf(meetup: Meetup): List[(Meetup, URL)] =
    meetup.productElementNames
      .zip(meetup.productIterator)
      .collect { case (fieldName, Some(url: URL)) => (meetup, url) }
      .toList

  private def runParser(meetup: Meetup, url: URL): ZStream[Scope & Client & Browser, Throwable, List[Event]] = for
    sourcesList <- ZStream.fromZIO(AppConfig.sourcesList)
    events      <-
      if sourcesList.enabled(Source.Meetup) && url.host.exists(_.contains("meetup.com")) then
        ZStream.fromZIO(MeetupComParser(meetup).parseWithClient(url))
      else if sourcesList.enabled(Eventbrite) && url.host.exists(_.contains("eventbrite.com")) then
        ZStream.fromZIO(EventbriteParser(meetup).parseWithClient(url))
      else if sourcesList.enabled(TehnoloskiParkLjubljana) && url.host.exists(_.contains("tp-lj.si")) then
        ZStream.fromZIO(TehnoloskiParkLjubljanaParser(meetup).parseWithClient(url))
      else if sourcesList.enabled(GZS) && url.host.exists(_.contains("gzs.si")) then
        ZStream.fromZIO(GZSParser(meetup).parseWithClient(url))
      else if sourcesList.enabled(PrimorskiTehnoloskiPark) && url.host.exists(_.contains("primorski-tp.si")) then
        ZStream.fromZIO(PrimorskiTehnoloskiParkParser(meetup).parseWithClient(url))
      else ZStream.empty
  yield events

  private def sync: ZIO[Scope & DB & Client & Browser, Throwable, Unit] =
    ZStream
      .fromZIO(Meetups.all)
      .flatMap(meetups => ZStream.fromIterable(meetups))
      .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
      .flatMap(runParser)
      .mapZIO(events => ZIO.foreachDiscard(events)(Events.upsert))
      .runDrain

  def run = for
    _ <- logInfo("Syncing events started.")
    // f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing events stopped."))
  yield ()

  def runScheduled: ZIO[Scope & DB & Scheduler & Client & Browser, Throwable, Unit] = for
    _  <- logInfo("Syncing events started.")
    _  <- sync
    f1 <-
      sync.scheduleTo(Scheduler.simple.withIntervalInMinutes(66).repeatForever()).fork
    _  <- Scope.addFinalizer(f1.interrupt <* logInfo("Syncing events stopped."))
    _  <- ZIO.never
  yield ()

object EventsSync:
  def live = ZLayer.derive[EventsSync]
