package si.ogrodje.goo.models
import si.ogrodje.goo.models.Source
import zio.http.URL

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
