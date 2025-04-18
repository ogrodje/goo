package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{Decoder, DecodingFailure, Json}
import si.ogrodje.goo.models.Source.Eventbrite
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.fromOption
import zio.http.{Client, Request, URL}
import zio.{Scope, ZIO}

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try

final case class EventbriteParser(meetup: Meetup) extends Parser:
  import DocumentOps.{*, given}

  private given Decoder[OffsetDateTime] =
    Decoder.decodeString.emap { raw =>
      val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ") // Z handles timezone like +0100
      Try(OffsetDateTime.parse(raw, formatter)).toEither.left.map(err => err.getMessage)
    }

  private def locationFromEvent(json: Json): (Option[String], Option[String]) =
    val cursor            = json.hcursor
    val maybeLocationName = cursor.downFields("location", "name").as[String].toOption
    val maybeAddress      = for
      streetAddress   <- cursor.downFields("location", "address").get[String]("streetAddress")
      postalCode      <- cursor.downFields("location", "address").get[String]("postalCode")
      addressLocality <- cursor.downFields("location", "address").get[String]("addressLocality")
    yield s"$streetAddress, $postalCode $addressLocality".strip

    maybeLocationName -> maybeAddress.toOption

  private def eventsFromLD(sourceURL: URL, json: Json): List[Event] =
    val parsed = for
      itemListElement     <- json.hcursor.get[Json]("itemListElement")
      itemListElementArray = itemListElement.asArray.getOrElse(Vector.empty)
      events               =
        itemListElementArray
          .map(item => item.hcursor.get[Json]("item"))
          .collect { case Right(json) => json }
          .map { json =>
            val cursor = json.hcursor
            for
              kind                                     <- cursor.get[String]("@type")
              name                                     <- cursor.get[String]("name")
              startDate                                <- cursor.get[OffsetDateTime]("startDate")
              endDate                                  <- cursor.get[Option[OffsetDateTime]]("endDate")
              url                                      <- cursor.get[URL]("url")
              description                              <- cursor.get[Option[String]]("description")
              (maybeLocationName, maybeLocationAddress) = locationFromEvent(json)
            yield Event(
              id = url.path.toString.split("-").lastOption.map(r => s"eventbrite-$r").get,
              meetupID = meetup.id,
              source = Eventbrite,
              sourceURL = sourceURL,
              title = name,
              description = description,
              eventURL = Some(url),
              startDateTime = startDate,
              endDateTime = endDate,
              locationName = maybeLocationName,
              locationAddress = maybeLocationAddress
            )
          }
          .collect { case Right(event) => event }
    yield events

    parsed.toOption.toList.flatten

  override protected def parse(
    client: Client,
    url: URL
  ): ZIO[Scope & Browser, Throwable, List[Event]] = for
    response <- client.request(Request.get(url))
    document <- response.body.asDocument
    lds      <- document.query("script[type='application/ld+json']").map(_.filter(_.data.contains("itemListElement")))
    ldJson   <- fromOption(lds.headOption)
                  .orElseFail(new NoSuchElementException("No itemListElement found"))
                  .flatMap(_.dataAsJson)
    events    = eventsFromLD(url, ldJson)
  yield events
