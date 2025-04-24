package si.ogrodje.goo.models
import zio.http.URL
import zio.schema.{DeriveSchema, Schema}

import java.time.OffsetDateTime

type EventID = String

final case class Event(
  id: EventID,
  meetupID: MeetupID,
  source: Source,
  sourceURL: URL,
  title: String,
  startDateTime: OffsetDateTime,
  description: Option[String],
  eventURL: Option[URL],
  endDateTime: Option[OffsetDateTime],
  hasStartTime: Boolean,
  hasEndTime: Boolean,
  updatedAt: Option[OffsetDateTime],
  locationName: Option[String],
  locationAddress: Option[String],
  hiddenAt: Option[OffsetDateTime],
  promotedAt: Option[OffsetDateTime]
)

object Event:
  given Schema[URL] = Schema
    .primitive[String]
    .transform(
      str => URL.decode(str).toOption.get,
      _.encode
    )

  given schema: Schema[Event] = DeriveSchema.gen

  def empty(
    id: EventID,
    meetupID: MeetupID,
    source: Source,
    sourceURL: URL,
    title: String,
    startDateTime: OffsetDateTime
  ): Event =
    Event(
      id = id,
      meetupID = meetupID,
      source = source,
      sourceURL = sourceURL,
      title = title,
      startDateTime = startDateTime,
      description = None,
      eventURL = None,
      endDateTime = None,
      hasStartTime = true,
      hasEndTime = true,
      updatedAt = None,
      locationName = None,
      locationAddress = None,
      hiddenAt = None,
      promotedAt = None
    )
