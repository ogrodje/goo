package si.ogrodje.goo.parsers

import io.circe.{Decoder, DecodingFailure, Json}
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.fromOption
import zio.http.{Client, Request, URL}
import zio.{Scope, Task, ZIO}

import java.time.OffsetDateTime

final case class MeetupComParser(meetup: Meetup) extends Parser:
  import DocumentOps.{*, given}

  private given Decoder[URL] = Decoder.decodeString.emap(raw => URL.decode(raw).left.map(err => err.getMessage))

  private def eventToEvent(sourceURL: URL)(json: Json): Option[Event] =
    val cursor = json.hcursor
    val parsed = for
      eventID       <- cursor.get[String]("id")
      title         <- cursor.get[String]("title").map(_.trim)
      description   <- cursor.get[Option[String]]("description").map(_.map(_.trim))
      eventURL      <- cursor.get[Option[URL]]("eventUrl")
      startDateTime <- cursor.get[OffsetDateTime]("dateTime")
      endDateTime   <- cursor.get[Option[OffsetDateTime]]("endTime")
    // _ = println(json)
    yield Event(
      id = s"meetup.com/$eventID",
      meetupID = meetup.id,
      source = Source.Meetup,
      sourceURL = sourceURL,
      title = title,
      description = description,
      eventURL = eventURL,
      startDateTime = startDateTime,
      endDateTime = endDateTime
    )
    parsed.toOption

  private def readEventsFromMeta(sourceURL: URL, json: Json): Task[List[Event]] = for
    apolloState <-
      fromOption(
        json.hcursor.downField("props").downField("pageProps").downField("__APOLLO_STATE__").focus
      )
        .orElseFail(new NoSuchElementException("No __APOLLO_STATE__ found in JSON"))
    eventKeys    = apolloState.hcursor.keys.toList.flatMap(_.filter(_.startsWith("Event")))
    venueKeys    = apolloState.hcursor.keys.toList.flatMap(_.filter(_.startsWith("Venue")))

    // TODO: Add venue
    events =
      eventKeys
        .map(key => apolloState.hcursor.downField(key).focus.flatMap(eventToEvent(sourceURL)))
        .collect { case Some(event) => event }
  yield events

  private def parseEventsFrom(client: Client, url: URL) = for
    response <- client.request(Request.get(url))
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
  ): ZIO[Scope, Throwable, List[Event]] = (
    parseEventsFrom(client, url.addQueryParam("type", "upcoming")) <&>
      parseEventsFrom(client, url.addQueryParam("type", "past"))
  ).map(_ ++ _)
