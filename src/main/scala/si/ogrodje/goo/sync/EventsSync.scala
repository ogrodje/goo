package si.ogrodje.goo.sync

import si.ogrodje.goo.models.*
import zio.*
import zio.metrics.*
import zio.http.*
import zio.stream.{Take, ZStream}
import ZIO.logInfo
import com.microsoft.playwright.Browser
import si.ogrodje.goo.{AppConfig, SourcesList}
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.Source.{
  Eventbrite,
  FRI,
  GZS,
  ICal,
  PrimorskiTehnoloskiPark,
  StartupSi,
  TehnoloskiParkLjubljana
}
import si.ogrodje.goo.parsers.*
import si.ogrodje.goo.scheduler.ScheduleOps.*
import si.ogrodje.goo.scheduler.Scheduler

final class EventsSync extends SyncService[Scope & DB & Scheduler & Client & Browser]:
  private type FieldName = String

  private def sourcesOf(meetup: Meetup): List[(FieldName, Meetup, URL)] =
    meetup.productElementNames
      .zip(meetup.productIterator)
      .collect { case (fieldName, Some(url: URL)) => (fieldName, meetup, url) }
      .toList

  private def runParser(
    sourcesList: SourcesList,
    fieldName: FieldName,
    meetup: Meetup,
    url: URL
  ): ZStream[Scope & Client & Browser, Throwable, Event] = for event <-
      if sourcesList.enabled(Source.Meetup) && url.host.exists(_.contains("meetup.com")) then
        MeetupComParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(Eventbrite) && url.host.exists(_.contains("eventbrite.com")) then
        EventbriteParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(TehnoloskiParkLjubljana) && url.host.exists(_.contains("tp-lj.si")) then
        TehnoloskiParkLjubljanaParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(GZS) && url.host.exists(_.contains("gzs.si")) then
        GZSParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(PrimorskiTehnoloskiPark) && url.host.exists(_.contains("primorski-tp.si")) then
        PrimorskiTehnoloskiParkParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(StartupSi) && url.host.exists(_.contains("startup.si")) then
        StartupSiParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(ICal) && fieldName == "icalUrl" then ICalParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(FRI) && url.host.exists(_.contains("fri.uni-lj.si")) then
        FRIParser(meetup).streamEventsFrom(url)
      else ZStream.empty
  yield event

  private val syncEventsUpdated  = Metric.counter("sync_events_updated")
  private val syncEventsInserted = Metric.counter("sync_events_inserted")

  def sync(before: UIO[Unit] = ZIO.unit, after: UIO[Unit] = ZIO.unit) = for
    _           <- before
    sourcesList <- AppConfig.sourcesList
    eventsQ     <- Queue.unbounded[Take[Throwable, (Event, String)]]

    collectionFib <-
      ZStream
        .fromZIO(Meetups.all)
        .flatMap(meetups => ZStream.fromIterable(meetups))
        .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
        .flatMap { case (filedName, meetup, url) =>
          runParser(sourcesList, filedName, meetup, url).map(_ -> meetup.name)
        }
        .runIntoQueue(eventsQ)
        .fork

    writingFib <-
      ZStream
        .fromQueue(eventsQ)
        .flattenTake
        .runForeach((e, meetupName) =>
          Events.upsert(e).tapSome {
            case Right(UpsertResult.Updated(eventID))  =>
              syncEventsUpdated.tagged("meetup", meetupName).tagged("event_id", eventID).increment
            case Right(UpsertResult.Inserted(eventID)) =>
              syncEventsInserted.tagged("meetup", meetupName).tagged("event_id", eventID).increment
          }
        )
        .timed
        .fork

    result <- (collectionFib.join *> writingFib.join).either
    _      <-
      result match
        case Right((duration, _)) => logInfo(s"Sync completed successfully in ${duration.getSeconds}s")
        case Left(error)          => logInfo(s"Sync failed: ${error.getMessage}") *> ZIO.fail(error)
    _      <- after
  yield ()

  def runScheduled(
    beforeSync: UIO[Unit] = ZIO.unit,
    afterSync: UIO[Unit] = ZIO.unit
  ) = for
    _  <- logInfo("Syncing events started.")
    _  <- sync(beforeSync, afterSync)
    f1 <-
      sync(beforeSync, afterSync)
        .scheduleTo(Scheduler.simple.withIntervalInMinutes(66).repeatForever())
        .fork
    _  <- Scope.addFinalizer(f1.interrupt <* logInfo("Syncing events stopped."))
    _  <- ZIO.never
  yield ()

object EventsSync:
  def live: ZLayer[Any, Nothing, EventsSync] = ZLayer.derive[EventsSync]
