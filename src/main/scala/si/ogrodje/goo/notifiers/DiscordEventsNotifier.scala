package si.ogrodje.goo.notifiers

import si.ogrodje.goo.clients.discord.{DiscordClient, Embed, Field, Payload}
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.{Event, EventView, Events}
import zio.*
import zio.ZIO.logInfo
import zio.http.*
import zio.Console.printLine
import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneOffset}
import ZoneOffset.UTC

object DiscordEventsNotifier:
  private def current: OffsetDateTime = OffsetDateTime.now(UTC)

  private def notify(
    view: EventView,
    now: OffsetDateTime = current
  ): ZIO[Client & DiscordClient & DB, Throwable, Unit] = for
    (from, to, events) <- Events.upcomingFor(view, now)
    payload             = eventsToPayload(from, to, events)
    _                  <- printLine(s"Sending: ${payload}")
    _                  <- DiscordClient.emit(payload)
    _                  <- logInfo(s"Emitted to discord server.")
  yield ()

  private def fieldsFrom(event: Event): List[Field] =
    List(
      "Pričetek / Konec" -> {
        val startLocalDT = event.startDateTime.toLocalDateTime
        val startDate    = startLocalDT.toLocalDate
        val startStr     = if event.hasStartTime then startLocalDT.toString else startDate.toString

        val maybeEndLocalDT = event.endDateTime.map(_.toLocalDateTime)
        val value           =
          maybeEndLocalDT match
            case Some(endLocalDT) =>
              val endDate = endLocalDT.toLocalDate
              if startDate == endDate then
                val startTimeStr = if event.hasStartTime then startLocalDT.toLocalTime.toString else ""
                val endTimeStr   = if event.hasEndTime then endLocalDT.toLocalTime.toString else ""
                val dateStr      = startDate.toString
                (startTimeStr, endTimeStr) match
                  case "" -> "" => dateStr
                  case s -> ""  => s"$dateStr $s".trim
                  case "" -> e  => s"$dateStr $e".trim
                  case s -> e   => s"$dateStr $s – $e".trim
              else
                val endStr = if event.hasEndTime then endLocalDT.toString else endDate.toString
                s"$startStr – $endStr"
            case None             =>
              startStr
        Some(value)
      },
      "Lokacija"         -> event.locationName
    ).collect { case (name, Some(value)) => Field(name, value) }

  private def humanTitle(event: Event): String = {
    event.promotedAt.map(_ => ":star2: ").getOrElse("") +
      event.meetupName.fold(event.title)(name => s"$name / ${event.title}")
  }.trim

  private def eventsToPayload(from: OffsetDateTime, to: OffsetDateTime, events: List[Event]) = Payload(
    content = s"Dogodki za obdobje **${from.toLocalDate}** do **${to.toLocalDate}**.",
    embeds = events.map { event =>
      Embed(
        title = humanTitle(event),
        url = event.eventURL.map(_.toString),
        fields = fieldsFrom(event)
      )
    }
  )

  def run(webhookURL: URL, view: EventView, maybeNow: Option[LocalDate] = None) = {
    for
      _  <- ZIO.unit
      now = maybeNow.map(_.atTime(LocalTime.MIDNIGHT).atOffset(UTC)).getOrElse(current)
      _  <- logInfo(s"Running events notifier for $view, using now ${now.toLocalDate}")
      _  <- notify(view, now)
    yield ()
  }.provide(Client.default, DB.transactorLayer, DiscordClient.live(webhookURL))
