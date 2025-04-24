package si.ogrodje.goo.models

import zio.http.URL
import zio.schema.{DeriveSchema, Schema}

import java.time.OffsetDateTime

final case class TimelineEvent(
  id: String,
  meetupID: String,
  source: Source,
  sourceURL: URL,
  title: String,
  meetupName: String,
  description: Option[String],
  eventURL: Option[URL],
  locationName: Option[String],
  locationAddress: Option[String],
  startDateTime: OffsetDateTime,
  hasStartTime: Boolean,
  endDateTime: OffsetDateTime,
  hasEndTime: Boolean,
  hiddenAt: Option[OffsetDateTime],
  promotedAt: Option[OffsetDateTime]
)

object TimelineEvent:
  given Schema[URL] =
    Schema.primitive[String].transform(str => URL.decode(str).toOption.get, _.encode)

  given schema: Schema[TimelineEvent] = DeriveSchema.gen
