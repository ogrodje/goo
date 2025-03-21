package si.ogrodje.goo.sync

import si.ogrodje.goo.clients.HyGraph
import si.ogrodje.goo.db.{DB, DBOps}
import si.ogrodje.goo.models.{Meetup, Meetups}
import zio.*
import zio.ZIO.logInfo
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

final class MeetupsSync private (private val hyGraph: HyGraph):
  import DBOps.*
  import DBOps.given
  private val syncSchedule = Schedule.fixed(10.seconds)

  private def partitionMeetups(
    dbMeetups: List[Meetup],
    graphMeetups: List[Meetup]
  ): (List[Meetup], List[Meetup], List[Meetup]) =
    val dbMeetupsMap = dbMeetups.map(m => m.id -> m).toMap
    graphMeetups.foldLeft((List.empty[Meetup], List.empty[Meetup], List.empty[Meetup])):
      case ((newAcc, updateAcc, unchangedAcc), graphMeetup) =>
        dbMeetupsMap.get(graphMeetup.id) match
          case None           =>
            (graphMeetup :: newAcc, updateAcc, unchangedAcc)
          case Some(dbMeetup) =>
            if graphMeetup.updatedAt.isAfter(dbMeetup.updatedAt) then (newAcc, graphMeetup :: updateAcc, unchangedAcc)
            else (newAcc, updateAcc, dbMeetup :: unchangedAcc)

  private def sync = for
    (graphMeetups, dbMeetups)   <- hyGraph.allMeetups <&> Meetups.all
    (toAdd, toUpdate, unchanged) = partitionMeetups(dbMeetups, graphMeetups)

    _ <- logInfo(s"New: ${toAdd.size}, Updated: ${toUpdate.size}, Unchanged: ${unchanged.size}")
    _ <- Meetups.insert(toAdd*)
    _ <- Meetups.update(toUpdate*)
  yield ()

  def run: ZIO[Scope & DB, Throwable, Unit] = for
    f <- sync.repeat(syncSchedule).forever.fork
    _ <- Scope.addFinalizer(f.interrupt <* logInfo("Syncing meetups stopped."))
  yield ()

object MeetupsSync:

  def live = ZLayer.fromZIO:
    for hyGraph <- ZIO.service[HyGraph]
    yield new MeetupsSync(hyGraph)
