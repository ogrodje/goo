package si.ogrodje.goo.scheduler

import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.JobBuilder.newJob
import org.quartz.SimpleScheduleBuilder.simpleSchedule
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory
import org.quartz.{
  CronScheduleBuilder,
  JobDataMap,
  JobExecutionContext,
  ScheduleBuilder,
  Scheduler as QScheduler,
  SimpleScheduleBuilder,
  Trigger
}
import zio.*
import zio.ZIO.logInfo
import zio.stream.ZStream

import java.time.ZoneId
import java.util.{TimeZone, UUID}
import scala.jdk.CollectionConverters.*

object ScheduleOps:
  extension [R, E <: Throwable, A](io: ZIO[R, E, A])
    def scheduleTo[SBT <: Trigger](
      builder: => ScheduleBuilder[SBT],
      maybeTriggerName: Option[String] = None
    ): ZIO[R & Scheduler, Throwable, Unit] =
      for
        scheduler  <- ZIO.service[Scheduler]
        triggerName = maybeTriggerName.getOrElse(UUID.randomUUID().toString)
        trigger     =
          newTrigger()
            .withIdentity(triggerName)
            .startNow()
            .withSchedule(builder)
            .build()
        firstDate  <- scheduler.schedule(CallbackJob(), triggerName, trigger)
        _          <- logInfo(s"Scheduled with first start at ${firstDate} w/ ${triggerName}")
        _          <- scheduler.waitFor(triggerName)(io)
      yield ()

final private class CallbackJob extends org.quartz.Job:
  override def execute(context: JobExecutionContext): Unit =
    context.getMergedJobDataMap
      .get("callback")
      .asInstanceOf[String => Unit](context.getMergedJobDataMap.getString("id"))

final class Scheduler(
  private val scheduler: QScheduler,
  private val hub: Hub[String]
):

  private def mkJMap(id: String): JobDataMap =
    val jMap: java.util.Map[String, String => Unit] = Map("callback" -> { (_: String) =>
      val runtime = Runtime.default
      Unsafe.unsafe:
        implicit unsafe => runtime.unsafe.run(hub.publish(id).unit).getOrThrowFiberFailure()
    }).asJava

    JobDataMap(jMap)

  def schedule(
    callbackJob: CallbackJob,
    name: String,
    trigger: Trigger
  ): Task[java.util.Date] =
    ZIO.attemptBlocking:
      val classOfT: Class[? <: org.quartz.Job] = callbackJob.getClass
      val job                                  =
        newJob(classOfT).usingJobData("id", name).usingJobData(mkJMap(name)).build()
      scheduler.scheduleJob(job, trigger)

  def waitFor[R, E <: Throwable, A](id: String)(callback: ZIO[R, E, A]): ZIO[R, E, Unit] =
    ZStream
      .fromHub(hub)
      .filter(_.contains(id))
      .tap(_ => callback)
      .runDrain

object Scheduler:
  private val timeZone                             = TimeZone.getTimeZone(ZoneId.of("Europe/Ljubljana"))
  final def simple: SimpleScheduleBuilder          = simpleSchedule()
  final def cron(raw: String): CronScheduleBuilder = cronSchedule(raw).inTimeZone(timeZone)

  def live: RLayer[Scope, Scheduler] = ZLayer.fromZIO:
    for
      hub       <- Hub.unbounded[String]
      scheduler <-
        ZIO.acquireRelease(
          ZIO
            .attempt(StdSchedulerFactory.getDefaultScheduler)
            .tap(scheduler => ZIO.attempt(scheduler.start()))
        )(scheduler => ZIO.attemptBlocking(scheduler.shutdown(true)).orDie)

      _ <- Scope.addFinalizer(hub.shutdown)
    yield new Scheduler(scheduler, hub)
