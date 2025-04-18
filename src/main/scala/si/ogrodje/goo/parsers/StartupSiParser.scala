package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.*
import si.ogrodje.goo.models.Source.StartupSi
import zio.{Scope, ZIO}
import zio.http.{Client, Request, URL}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId}

final case class StartupSiParser(meetup: Meetup) extends Parser:
  import DocumentOps.*

  private val formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")
  private val cetZone   = ZoneId.of("Europe/Paris")
  private val digest    = MessageDigest.getInstance("SHA-1")

  private def parseDateTime(raw: String) = LocalDateTime.parse(raw, formatter).atZone(cetZone).toOffsetDateTime

  private def parseIndividual(client: Client, rootUrl: URL, url: URL) = for
    document   <- client.request(Request.get(url)).flatMap(_.body.asDocument)
    maybeTitle <- document.queryFirst("div.addeventatc span.title").map(_.map(_.text().trim))
    title      <- ZIO.fromOption(maybeTitle).orElseFail(new NoSuchElementException("No title found"))

    maybeStart <- document
                    .queryFirst("div.addeventatc span.start")
                    .map(_.map(_.text().trim))
                    .map(_.map(parseDateTime))
    maybeEnd   <- document
                    .queryFirst("div.addeventatc span.end")
                    .map(_.map(_.text().trim))
                    .map(_.map(parseDateTime))

    locationName <- document.query("div.location-html p").map(_.map(_.text().trim)).map(_.mkString(" ").trim)

    hashBytes = digest.digest(url.toString.getBytes(StandardCharsets.UTF_8))
    id        = hashBytes.map("%02x".format(_)).mkString
  yield Event(
    id = s"startup-si-$id",
    meetupID = meetup.id,
    source = StartupSi,
    sourceURL = rootUrl,
    title = title,
    startDateTime = maybeStart.get,
    description = None,
    eventURL = Some(url),
    endDateTime = maybeEnd,
    hasStartTime = true,
    hasEndTime = true,
    locationName = Some(locationName),
    locationAddress = None
  )

  override protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]] = for
    rootUrl    <- ZIO.succeed(url.path("/sl-si/dogodki"))
    nextEvents <- client.request(Request.get(rootUrl)).flatMap(_.body.asDocument)
    eventHrefs <- nextEvents.query("div.next-six-events a.event-card").map(_.map(_.attr("href")))
    eventUrls  <- ZIO.foreach(eventHrefs)(path => ZIO.succeed(url.path(path)))
    events     <- ZIO.foreach(eventUrls)(parseIndividual(client, rootUrl, _))
  yield events
