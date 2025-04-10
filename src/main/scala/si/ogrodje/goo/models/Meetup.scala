package si.ogrodje.goo.models

import zio.http.URL
import zio.schema.{DeriveSchema, Schema}
import zio.http.codec.PathCodec.*
import zio.http.codec.*
import java.time.OffsetDateTime

type MeetupID = String

final case class Meetup(
  id: MeetupID,
  name: String,
  stage: Option[String],
  homepageUrl: Option[URL],
  meetupUrl: Option[URL],
  discordUrl: Option[URL],
  linkedinUrl: Option[URL],
  kompotUrl: Option[URL],
  eventbriteUrl: Option[URL],
  icalUrl: Option[URL],
  createdAt: OffsetDateTime,
  updatedAt: OffsetDateTime
)

object Meetup:
  given Schema[URL] = Schema
    .primitive[String]
    .transform(
      str => URL.decode(str).toOption.get,
      _.encode
    )

  given schema: Schema[Meetup] = DeriveSchema.gen
