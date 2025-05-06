package si.ogrodje.goo.models

import zio.http.URL
import zio.schema.{DeriveSchema, Schema}
import zio.ZIO
import ZIO.logInfo
import si.ogrodje.goo.{AppError, BaseError}
import si.ogrodje.goo.db.DB

import java.time.OffsetDateTime
import zio.schema.annotation.optionalField

import java.util.UUID

final case class CreateEvent(
  meetupID: String,
  title: String,
  startDateTime: OffsetDateTime,
  endDateTime: OffsetDateTime,
  @optionalField
  description: Option[String],
  @optionalField
  eventURL: Option[URL],
  @optionalField
  locationName: Option[String],
  @optionalField
  locationAddress: Option[String]
):

  def toDBEvent: Event = Event(
    id = UUID.randomUUID().toString,
    meetupID = meetupID,
    source = Source.Manual,
    sourceURL = URL.decode("https://ogrodje.si").toOption.get,
    title = title,
    startDateTime = startDateTime,
    description = description,
    eventURL = eventURL,
    endDateTime = Some(endDateTime),
    hasStartTime = true,
    hasEndTime = true,
    updatedAt = None,
    locationName = locationName,
    locationAddress = locationAddress,
    hiddenAt = None,
    promotedAt = None
  )

object CreateEvent:
  given Schema[URL]                 = Schema.primitive[String].transform(str => URL.decode(str).toOption.get, _.encode)
  given schema: Schema[CreateEvent] = DeriveSchema.gen

  def mutate(createEvent: CreateEvent): ZIO[DB, BaseError, Event] = for
    (event, result) <- ZIO
                         .attempt(createEvent.toDBEvent)
                         .flatMap(e => Events.create(e).map(r => e -> r))
                         .mapError(AppError.fromThrowable)
    _               <- logInfo(s"Created event: ${event.id} result: $result")
  yield event
