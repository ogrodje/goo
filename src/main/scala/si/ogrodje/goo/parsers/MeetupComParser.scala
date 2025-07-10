package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{Decoder, Json}
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.{fromOption, logInfo}
import zio.http.{Client, Request, URL}
import zio.{Scope, Task, ZIO}

import java.time.OffsetDateTime
import si.ogrodje.goo.ClientOps.requestMetered

final case class MeetupComParser(meetup: Meetup) extends Parser:
  import DocumentOps.{*, given}

  private def venueIDFromEvent(json: Json): Option[String] =
    json.hcursor
      .downField("venue")
      .downField("__ref")
      .as[String]
      .toOption
      .map(_.split(":").tail.head)

  private def eventToEvent(sourceURL: URL)(json: Json): Option[(Event, Option[String])] =
    val cursor = json.hcursor
    val parsed = for
      eventID       <- cursor.get[String]("id")
      title         <- cursor.get[String]("title").map(_.trim)
      description   <- cursor.get[Option[String]]("description").map(_.map(_.trim))
      eventURL      <- cursor.get[Option[URL]]("eventUrl")
      startDateTime <- cursor.get[OffsetDateTime]("dateTime")
      endDateTime   <- cursor.get[Option[OffsetDateTime]]("endTime")
      maybeVenueID   = venueIDFromEvent(json)
    yield Event
      .empty(
        id = s"meetup-$eventID",
        meetupID = meetup.id,
        source = Source.Meetup,
        sourceURL = sourceURL,
        title = title,
        startDateTime = startDateTime
      )
      .copy(
        description = description,
        eventURL = eventURL,
        endDateTime = endDateTime,
        locationName = None,
        locationAddress = None
      ) -> maybeVenueID

    parsed.toOption

  final private case class Venue(name: String, address: String)

  private def venueToVenue(json: Json): Option[(String, Venue)] =
    val parsed = for
      id         <- json.hcursor.get[String]("id").map(_.split(":").head)
      name       <- json.hcursor.get[String]("name")
      addressOne <- json.hcursor.get[Option[String]]("address").map(_.filterNot(_.isEmpty))
      city       <- json.hcursor.get[Option[String]]("city").map(_.filterNot(_.isEmpty))
      state      <- json.hcursor.get[Option[String]]("state").map(_.filterNot(_.isEmpty))
    yield (id, name, List(addressOne, city, state).map(_.getOrElse("")).filter(_.nonEmpty).mkString(", ").strip())

    parsed.toOption.map((id, name, address) => id -> Venue(name, address))

  private def readEventsFromMeta(sourceURL: URL, json: Json): Task[List[Event]] = for
    apolloState <-
      fromOption(json.hcursor.downFields("props", "pageProps", "__APOLLO_STATE__").focus)
        .orElseFail(new NoSuchElementException("No __APOLLO_STATE__ found in JSON"))
    eventKeys    = apolloState.hcursor.keys.toList.flatMap(_.filter(_.startsWith("Event")))
    venueKeys    = apolloState.hcursor.keys.toList.flatMap(_.filter(_.startsWith("Venue")))
    venues       =
      venueKeys
        .map(key => apolloState.hcursor.downField(key).focus.flatMap(venueToVenue))
        .collect { case Some(id, venue) => id -> venue }
        .toMap
    events       =
      eventKeys
        .map(key => apolloState.hcursor.downField(key).focus.flatMap(eventToEvent(sourceURL)))
        .map {
          case None                       => None
          case Some(event, Some(venueID)) =>
            Some(
              venues
                .get(venueID)
                .fold(event)(venue =>
                  event.copy(locationName = Some(venue.name), locationAddress = Some(venue.address))
                )
            )
          case Some(event, None)          => Some(event)
        }
        .collect { case Some(event) => event }
  yield events

  private def parseEventsFrom(client: Client, url: URL) = for
    _        <- logInfo(s"Parsing events from $url")
    response <- client.requestMetered(Request.get(url))
    document <- response.body.asDocument
    metaJson <-
      document
        .first("script[id='__NEXT_DATA__'][type='application/json']")
        .orElseFail(new NoSuchElementException(s"No __NEXT_DATA__ found for $url"))
    json     <- metaJson.dataAsJson.orElse(ZIO.succeed(Json.obj()))
    events   <- readEventsFromMeta(url, json)
  yield events

  override protected def parse(
    client: Client,
    url: URL
  ): ZIO[Scope & Browser, Throwable, List[Event]] = (
    parseEventsFrom(client, url.addQueryParam("type", "upcoming")) <&>
      parseEventsFrom(client, url.addQueryParam("type", "past"))
  ).map(_ ++ _)
