package si.ogrodje.goo.sync

import si.ogrodje.goo.clients.HyGraph
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.{Meetup, Meetups}
import zio.*
import zio.ZIO.{logInfo, logWarning}

final class MeetupsSync private (private val hyGraph: HyGraph):
  private val syncSchedule = Schedule.fixed(10.minutes)

  private def partitionMeetups(
    dbMeetups: List[Meetup],
    graphMeetups: List[Meetup]
  ): (List[Meetup], List[Meetup], List[Meetup]) =
    val dbMeetupsMap = dbMeetups.map(m => m.id -> m).toMap
    graphMeetups.foldLeft((List.empty, List.empty, List.empty)):
      case ((newAcc, updateAcc, unchangedAcc), graphMeetup) =>
        dbMeetupsMap.get(graphMeetup.id) match
          case None                                                                =>
            (graphMeetup :: newAcc, updateAcc, unchangedAcc)
          case Some(dbMeetup) if graphMeetup.updatedAt.isAfter(dbMeetup.updatedAt) =>
            (newAcc, graphMeetup :: updateAcc, unchangedAcc)
          case Some(dbMeetup)                                                      =>
            (newAcc, updateAcc, dbMeetup :: unchangedAcc)

  private def sync = for
    (graphMeetups, dbMeetups)   <- hyGraph.allMeetups <&> Meetups.all
    (toAdd, toUpdate, unchanged) = partitionMeetups(dbMeetups, graphMeetups)

    _ <- logInfo(s"New: ${toAdd.size}, Updated: ${toUpdate.size}, Unchanged: ${unchanged.size}")
    _ <- Meetups.insert(toAdd*)
    _ <- Meetups.update(toUpdate*)
  yield ()

  def run: ZIO[Scope & DB, Throwable, Unit] = for
    f <-
      sync
        .retryOrElse(
          policy = Schedule.exponential(10.seconds) && Schedule.recurs(3),
          orElse = (err, _) => logWarning(s"Retry failed with ${err.getMessage}")
        )
        .repeat(syncSchedule)
        .forever
        .fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing meetups stopped."))
  yield ()

object MeetupsSync:
  def live = ZLayer.derive[MeetupsSync]
