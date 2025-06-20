package si.ogrodje.goo.sync

import zio.*
import zio.ZIO.logInfo

trait SyncService[-R]:
  def sync(
    before: UIO[Unit] = ZIO.unit,
    after: UIO[Unit] = ZIO.unit
  ): RIO[R, Unit]

  def runScheduled(
    beforeSync: UIO[Unit] = ZIO.unit,
    afterSync: UIO[Unit] = ZIO.unit
  ): RIO[R, Unit]

final case class SyncEngine private (
  private val meetupsSync: MeetupsSync,
  private val eventsSync: EventsSync,
  private val meetupsSyncRunning: Ref[Boolean],
  private val eventsSyncRunning: Ref[Boolean]
):

  def runMeetupsSync = for
    _         <- ZIO.logInfo("Attempting to run meetups sync")
    isRunning <- meetupsSyncRunning.get
    _         <- ZIO.unless(isRunning)(
                   ZIO.logInfo("Meetups sync is not running. Starting sync.") *>
                     meetupsSync.sync(
                       before = meetupsSyncRunning.set(true) *> logInfo("Starting sync for meetups."),
                       after = meetupsSyncRunning.set(false) *> logInfo("Completed sync for meetups.")
                     )
                 )
  yield ()

  def start = for
    _ <- ZIO.logInfo("Sync engine starting.")

    meetupsSyncFib <-
      meetupsSync
        .runScheduled(
          beforeSync = meetupsSyncRunning.set(true) *> logInfo("Starting sync for meetups."),
          afterSync = meetupsSyncRunning.set(false) *> logInfo("Completed sync for meetups.")
        )
        .fork

    eventsSyncFib <-
      eventsSync
        .runScheduled(
          beforeSync = eventsSyncRunning.set(true) *> logInfo("Starting sync for events."),
          afterSync = eventsSyncRunning.set(false) *> logInfo("Completed sync for events.")
        )
        .fork

    _ <- Scope.addFinalizer(meetupsSyncFib.interrupt <* ZIO.logInfo("Meetups fiber interrupted."))
    _ <- Scope.addFinalizer(eventsSyncFib.interrupt <* ZIO.logInfo("Events  fiber interrupted."))
    _ <- ZIO.logInfo("Sync engine started.")

    _ <- meetupsSyncFib.join
    _ <- eventsSyncFib.join
  yield ()

object SyncEngine:
  def start = ZIO.serviceWithZIO[SyncEngine](_.start)

  def live: ZLayer[MeetupsSync & EventsSync, Nothing, SyncEngine] = ZLayer.scoped:
    for
      meetupsSync        <- ZIO.service[MeetupsSync]
      eventsSync         <- ZIO.service[EventsSync]
      meetupsSyncRunning <- Ref.make(false)
      eventsSyncRunning  <- Ref.make(false)
    yield SyncEngine(meetupsSync, eventsSync, meetupsSyncRunning, eventsSyncRunning)
