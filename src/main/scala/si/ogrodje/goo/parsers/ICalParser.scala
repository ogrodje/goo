package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.*
import si.ogrodje.goo.models.Source.ICal
import zio.http.{Client, Request, URL}
import zio.{Scope, ZIO}

import java.time.{OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*

final case class ICalParser(meetup: Meetup) extends Parser:
  ZoneId.of("Europe/Ljubljana")

  private def eventToEvent(sourceURL: URL, event: ICalEvent): Event = Event(
    id = event.id,
    meetupID = meetup.id,
    source = ICal,
    sourceURL = sourceURL,
    title = event.title,
    startDateTime = event.startDateTime,
    description = event.description,
    eventURL = event.eventURL,
    endDateTime = event.endDateTime,
    hasStartTime = event.hasStartTime,
    hasEndTime = event.hasEndTime,
    locationName = event.locationName,
    locationAddress = None
  )

  override protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]] = for
    body   <- client.request(Request.get(url)).flatMap(_.body.asString)
    events <- ICalReader.fromRaw(body)
  yield events.map(eventToEvent(url, _))
