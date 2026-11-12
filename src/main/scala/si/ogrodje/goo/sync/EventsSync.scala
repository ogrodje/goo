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
  DataScience,
  Eventbrite,
  FRI,
  GZS,
  ICal,
  InkubatorBelaKrajina,
  InkubatorRogaskaSlatina,
  InkubatorSavinjskeRegije,
  KCUI,
  Katapult,
  LUI,
  Luma,
  MPIKKoroska,
  PodjetniskiInkubatorPodbreznik,
  PomurskiTehnoloskiPark,
  PrimorskiTehnoloskiPark,
  RacunalniskiMuzej,
  SLAIF,
  SpletneUrice,
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
      else if sourcesList.enabled(Luma) && fieldName == "lumaUrl" then LumaParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(DataScience) && url.host.contains("datascience.fri.uni-lj.si") then
        DataScienceParser(meetup).streamEventsFrom(url)
      // Exactly FRI: its subdomains (e.g. datascience.fri.uni-lj.si) are other sites.
      else if sourcesList.enabled(FRI) && url.host.exists(h => h == "fri.uni-lj.si" || h == "www.fri.uni-lj.si") then
        FRIParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(KCUI) && url.host.exists(_.contains("kcui.si")) then
        KCUIParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(InkubatorSavinjskeRegije) && url.host.exists(_.contains("inkubatorsr.si")) then
        InkubatorSavinjskeRegijeParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(RacunalniskiMuzej) && url.host.exists(_.contains("racunalniski-muzej.si")) then
        RacunalniskiMuzejParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(LUI) && url.host.exists(h => h == "lui.si" || h.endsWith(".lui.si")) then
        LUIParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(SpletneUrice) && url.host.exists(_.contains("wwwhmb.si")) then
        SpletneUriceParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(PomurskiTehnoloskiPark) && url.host.exists(h =>
          h == "p-tech.si" || h == "www.p-tech.si"
        )
      then WordPressEventsParser(meetup, WordPressEventsParser.PomurskiTehnoloskiPark).streamEventsFrom(url)
      else if sourcesList.enabled(InkubatorBelaKrajina) && url.host.exists(h =>
          h == "inkubator-belakrajina.si" || h == "www.inkubator-belakrajina.si"
        )
      then WordPressEventsParser(meetup, WordPressEventsParser.InkubatorBelaKrajina).streamEventsFrom(url)
      else if sourcesList.enabled(InkubatorRogaskaSlatina) && url.host.exists(h =>
          h == "inkubator-rs.si" || h == "www.inkubator-rs.si"
        )
      then WordPressEventsParser(meetup, WordPressEventsParser.InkubatorRogaskaSlatina).streamEventsFrom(url)
      else if sourcesList.enabled(SLAIF) && url.host.exists(h => h == "slaif.si" || h.endsWith(".slaif.si")) then
        SLAIFParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(Katapult) && url.host.exists(h => h == "katapult.si" || h == "www.katapult.si") then
        KatapultParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(PodjetniskiInkubatorPodbreznik) &&
        url.host.exists(h => h == "rc-nm.si" || h == "www.rc-nm.si")
      then PodbreznikParser(meetup).streamEventsFrom(url)
      else if sourcesList.enabled(MPIKKoroska) &&
        url.host.exists(h => Set("rra-koroska.si", "www.rra-koroska.si", "mpik-koroska.si", "www.mpik-koroska.si")(h))
      then MPIKKoroskaParser(meetup).streamEventsFrom(url)
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
        .flatMap(ZStream.fromIterable)
        .flatMap(meetup => ZStream.fromIterable(sourcesOf(meetup)))
        // flatMapPar so a slow or bot-walled source (e.g. Eventbrite burning ~70s of retries) cannot
        // starve every other scraper the way sequential flatMap did. Each parser is already isolated
        // (failures degrade to an empty list in Parser.parseWithClient), so bounded concurrency here
        // only affects throughput, never correctness.
        .flatMapPar(EventsSync.collectionConcurrency) { case (filedName, meetup, url) =>
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

    // zipPar (not *>) so that a failure in either fiber interrupts the sibling instead of leaving
    // the writer draining an abandoned queue forever.
    result     <- collectionFib.join.zipPar(writingFib.join).either
    _          <-
      result match
        case Right((duration, _)) => logInfo(s"Sync completed successfully in ${duration.getSeconds}s")
        case Left(error)          => logInfo(s"Sync failed: ${error.getMessage}") *> ZIO.fail(error)
    _          <- after
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
  // How many sources to scrape concurrently. Bounded to avoid opening too many Playwright pages /
  // HTTP connections at once, while ensuring one stuck source cannot block the rest.
  private val collectionConcurrency: Int = 6

  def live: ZLayer[Any, Nothing, EventsSync] = ZLayer.derive[EventsSync]
