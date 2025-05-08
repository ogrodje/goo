package si.ogrodje.goo.models

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import zio.http.URL
import zio.schema.{DeriveSchema, Schema}

import java.time.OffsetDateTime

type MeetupID = String

final case class Meetup(
  id: MeetupID,
  name: String,
  hidden: Boolean,
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

  given schema: Schema[Meetup]       = DeriveSchema.gen
  private given Decoder[URL]         = Decoder[String].emap(raw => URL.decode(raw).left.map(_.getMessage))
  given jsonDecoder: Decoder[Meetup] = deriveDecoder[Meetup]

  def make(id: MeetupID, name: String): Meetup =
    Meetup(id, name, false, None, None, None, None, None, None, None, None, OffsetDateTime.now, OffsetDateTime.now)
