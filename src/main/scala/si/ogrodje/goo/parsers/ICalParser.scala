package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.{Calendar, Component}
import si.ogrodje.goo.models.*
import si.ogrodje.goo.models.Source.ICal
import zio.ZIO.{logDebug, logInfo, logWarning, logWarningCause}
import zio.http.{Client, Request, URL}
import zio.{Cause, Scope, Task, UIO, URIO, ZIO}

import java.io.StringReader
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, OffsetDateTime, ZoneId, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

final case class ICalParser(meetup: Meetup) extends Parser:
  private val cetZone = ZoneId.of("Europe/Ljubljana")

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
    _      <- logInfo("parsing ical events")
    body   <- client.request(Request.get(url)).flatMap(_.body.asString)
    events <- ICalReader.fromRaw(body)
  yield events.map(eventToEvent(url, _))
