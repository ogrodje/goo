package si.ogrodje.goo.notifiers

import si.ogrodje.goo.clients.discord.{DiscordClient, Embed, Field, Payload}
import si.ogrodje.goo.db.DB
import si.ogrodje.goo.models.{Event, EventView, Events}
import zio.*
import zio.ZIO.logInfo
import zio.http.*

import java.time.{LocalDate, LocalTime, OffsetDateTime}

object DiscordEventsNotifier:

  private val utc                             = java.time.ZoneOffset.UTC
  private def currentDateTime: OffsetDateTime = OffsetDateTime.now(utc)

  private def notify(
    view: EventView,
    now: OffsetDateTime = currentDateTime
  ): ZIO[Client & DiscordClient & DB, Throwable, Unit] = for
    (from, to, events) <- Events.upcomingFor(view, now)
    message             = s"Upcoming events from ${from.toLocalDate} to ${to.toLocalDate}"
    _                  <- DiscordClient.emit(eventsToPayload(from, to, events))
    _                  <- logInfo(s"Emitted to discord server.")
  yield ()

  private def eventsToPayload(from: OffsetDateTime, to: OffsetDateTime, events: List[Event]) = Payload(
    content = s"Dogodki za obdobje ${from.toLocalDate} do ${to.toLocalDate}.",
    embeds = events.take(7).map { event =>
      Embed(
        title = event.title,
        url = event.eventURL.map(_.toString),
        fields = List(
          Field("Pričetek", event.startDateTime.toLocalDate.toString),
          Field("Konec", event.endDateTime.map(_.toLocalDate.toString).getOrElse("N/A")),
          Field("Lokacija", event.locationName.getOrElse("N/A"))
        )
      )
    }
  )

  def run(webhookURL: URL, view: EventView, maybeNow: Option[LocalDate] = None) = {
    for
      _  <- ZIO.unit
      now = maybeNow.map(_.atTime(LocalTime.MIDNIGHT).atOffset(utc)).getOrElse(currentDateTime)
      _  <- logInfo(s"Running events notifier for ${view}, using now ${now.toLocalDate}")
      _  <- notify(view, now)
    yield ()
  }.provide(Client.default, DB.transactorLayer, DiscordClient.live(webhookURL))
