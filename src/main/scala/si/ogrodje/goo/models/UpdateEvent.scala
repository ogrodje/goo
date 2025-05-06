package si.ogrodje.goo.models

import si.ogrodje.goo.{AppError, BaseError}
import si.ogrodje.goo.db.DB
import zio.ZIO
import zio.ZIO.logInfo

object UpdateEvent:

  def mutate(eventId: String, createEvent: CreateEvent): ZIO[DB, BaseError, Event] = (for
    _           <- logInfo(s"Updating event: $eventId")
    dbEvent     <- Events.find(eventId)
    updatedEvent = createEvent.toDBEvent
    event        = dbEvent.copy(
                     meetupID = updatedEvent.meetupID,
                     title = updatedEvent.title,
                     eventURL = updatedEvent.eventURL,
                     startDateTime = updatedEvent.startDateTime,
                     endDateTime = updatedEvent.endDateTime,
                     description = updatedEvent.description,
                     locationName = updatedEvent.locationName,
                     locationAddress = updatedEvent.locationAddress,
                     hiddenAt = updatedEvent.hiddenAt,
                     promotedAt = updatedEvent.promotedAt
                   )
    saved       <- Events.update(event)
    _           <- logInfo(s"Updated event: ${event.id} result: $saved")
    refreshed   <- Events.find(eventId)
  yield refreshed).mapError(AppError.fromThrowable)
