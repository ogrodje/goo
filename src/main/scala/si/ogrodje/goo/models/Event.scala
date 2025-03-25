package si.ogrodje.goo.models
import si.ogrodje.goo.models.Source
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
  description: Option[String] = None,
  eventURL: Option[URL] = None,
  endDateTime: Option[OffsetDateTime] = None,
  hasStartTime: Boolean = true,
  hasEndTime: Boolean = true,
  updatedAt: Option[OffsetDateTime] = None
)

object Event:
  given Schema[URL] = Schema
    .primitive[String]
    .transform(
      str => URL.decode(str).toOption.get,
      _.encode
    )

  given schema: Schema[Event] = DeriveSchema.gen
