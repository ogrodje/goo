package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import net.fortuna.ical4j.data.ParserException
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.*
import si.ogrodje.goo.models.Source.ICal
import zio.http.{Client, Request, URL}
import zio.{Cause, RIO, Scope, ZIO}
final case class ICalParser(meetup: Meetup) extends Parser:

  private def eventToEvent(sourceURL: URL, event: ICalEvent): Event = Event
    .empty(
      id = event.id,
      meetupID = meetup.id,
      source = ICal,
      sourceURL = sourceURL,
      title = event.title,
      startDateTime = event.startDateTime
    )
    .copy(
      description = event.description,
      eventURL = event.eventURL,
      endDateTime = event.endDateTime,
      hasStartTime = event.hasStartTime,
      hasEndTime = event.hasEndTime,
      locationName = event.locationName,
      locationAddress = None
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    body   <- client.requestMetered(Request.get(url)).flatMap(_.body.asString)
    events <-
      ICalReader.fromRaw(body).catchSome { case parserException: ParserException =>
        ZIO.logWarningCause("Parsing has failed.", Cause.fail(parserException)).as(List.empty[ICalEvent])
      }
  yield events.map(eventToEvent(url, _))
