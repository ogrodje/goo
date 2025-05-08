package si.ogrodje.goo.sync

import si.ogrodje.goo.models.*
import zio.*
import zio.http.*
import zio.stream.ZStream
import ZIO.logInfo
import com.microsoft.playwright.Browser
import si.ogrodje.goo.AppConfig
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.Source.{Eventbrite, GZS, ICal, PrimorskiTehnoloskiPark, StartupSi, TehnoloskiParkLjubljana}
import si.ogrodje.goo.parsers.*
import si.ogrodje.goo.scheduler.ScheduleOps.*
import si.ogrodje.goo.scheduler.Scheduler

final class EventsSync:
  type FieldName = String

  private def sourcesOf(meetup: Meetup): List[(FieldName, Meetup, URL)] =
    meetup.productElementNames
      .zip(meetup.productIterator)
      .collect { case (fieldName, Some(url: URL)) => (fieldName, meetup, url) }
      .toList

  private def runParser(
    fieldName: FieldName,
    meetup: Meetup,
    url: URL
  ): ZStream[Scope & Client & Browser, Throwable, List[Event]] = for
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
      else if sourcesList.enabled(StartupSi) && url.host.exists(_.contains("startup.si")) then
        ZStream.fromZIO(StartupSiParser(meetup).parseWithClient(url))
      else if sourcesList.enabled(ICal) && fieldName == "icalUrl" && url.host.exists(_.contains("google.com")) then
        ZStream.fromZIO(ICalParser(meetup).parseWithClient(url))
      else ZStream.empty
  yield events

  private def sync: RIO[Scope & DB & Client & Browser, Unit] =
    ZStream
      .fromZIO(Meetups.all)
      .flatMap(meetups => ZStream.fromIterable(meetups))
      .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
      .flatMap(runParser)
      .mapZIO(events => ZIO.foreachDiscard(events)(Events.upsert))
      .runDrain

  def run: ZIO[Scope & DB & (Client & Browser), Nothing, Unit] = for
    _ <- logInfo("Syncing events started.")
    // f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    f <- sync.repeat(Schedule.fixed(10.seconds)).forever.fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing events stopped."))
  yield ()

  def runScheduled: RIO[Scope & DB & Scheduler & Client & Browser, Unit] = for
    _  <- logInfo("Syncing events started.")
    _  <- sync
    f1 <-
      sync.scheduleTo(Scheduler.simple.withIntervalInMinutes(66).repeatForever()).fork
    _  <- Scope.addFinalizer(f1.interrupt <* logInfo("Syncing events stopped."))
    _  <- ZIO.never
  yield ()

object EventsSync:
  def live: ZLayer[Any, Nothing, EventsSync] = ZLayer.derive[EventsSync]
