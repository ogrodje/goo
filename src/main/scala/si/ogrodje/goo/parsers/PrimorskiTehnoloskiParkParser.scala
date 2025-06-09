package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.{Event, Meetup}
import zio.{Scope, ZIO}
import zio.http.{Client, Request, URL}
import io.circe.{Decoder, Json}
import si.ogrodje.goo.models.Source.PrimorskiTehnoloskiPark
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import java.time.{OffsetDateTime, ZoneId}
final case class PrimorskiTehnoloskiParkParser(meetup: Meetup) extends Parser:
  import DocumentOps.{*, given}
  private val cetZone = ZoneId.of("Europe/Ljubljana")
  private val digest  = MessageDigest.getInstance("SHA-1")

  private given eventDecoder: Decoder[Event] = Decoder.decodeJson.emap { json =>
    val cursor = json.hcursor
    val parsed = for
      title <- cursor.get[String]("name")
      url   <- cursor.get[URL]("url")

      // This is not optimal, but it works.
      hashBytes = digest.digest(url.toString.getBytes(StandardCharsets.UTF_8))
      id        = hashBytes.map("%02x".format(_)).mkString

      startDateTime <- cursor.get[OffsetDateTime]("startDate")
      endDate       <- cursor.get[Option[OffsetDateTime]]("endDate")
      description   <- cursor.get[Option[String]]("description")
    yield Event
      .empty(
        id = s"ptp-$id",
        meetupID = meetup.id,
        source = PrimorskiTehnoloskiPark,
        sourceURL = url,
        title = title,
        startDateTime = startDateTime
      )
      .copy(
        description = description,
        eventURL = Some(url),
        endDateTime = endDate,
        hasStartTime = true,
        hasEndTime = true
      )

    parsed.left.map(_.getMessage)
  }

  override protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]] = for
    eventsUrl <- ZIO.succeed(url.path("vsi-dogodki/"))
    document  <- client.request(Request.get(eventsUrl)).flatMap(_.body.asDocument)
    jsonLDs   <- document.query("script[type=application/ld+json]").map(_.filter(_.data().contains("\"Event\"")))
    json      <-
      ZIO
        .foreach(jsonLDs)(_.dataAsJson)
        .map(lds => Json.fromValues(lds.flatMap(_.asArray.getOrElse(Vector.empty))))

    events        <- ZIO.fromEither(json.as[List[Event]])
    timeAgo        = OffsetDateTime.now(cetZone).minusMonths(2)
    filteredEvents = events.filter(_.startDateTime.isAfter(timeAgo))
  yield filteredEvents
