package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import org.jsoup.nodes.Document
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.KCUI
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, Request, URL}
import zio.{RIO, Scope, Task, ZIO}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{LocalDate, OffsetDateTime, ZoneId}
import scala.util.matching.Regex

final case class KCUIParser(meetup: Meetup) extends Parser:
  import DocumentOps.*

  private val cetZone = ZoneId.of("Europe/Ljubljana")
  private val digest  = MessageDigest.getInstance("SHA-1")

  // Only detail pages, e.g. "/izobrazevanja/agentna-ui-od-pricakovanj-do-resnicnih-ucinkov".
  // This intentionally matches both current and past events which live on the same listing.
  private val eventHref: Regex = "^/izobrazevanja/[a-z0-9-]+$".r

  // Slovenian month names in the "Kdaj" field, e.g. "Petek, 17. april 2026 ob 10:00".
  // Matched on the first three characters which are unique across all forms (nominative and genitive).
  private val months: Map[String, Int] = Map(
    "jan" -> 1,
    "feb" -> 2,
    "mar" -> 3,
    "apr" -> 4,
    "maj" -> 5,
    "jun" -> 6,
    "jul" -> 7,
    "avg" -> 8,
    "sep" -> 9,
    "okt" -> 10,
    "nov" -> 11,
    "dec" -> 12
  )

  private val dateTime: Regex = """(\d{1,2})\.\s+(\p{L}+)\s+(\d{4})(?:\s+ob\s+(\d{1,2})[:.](\d{2}))?""".r

  private def parseDateTime(raw: String): Task[(OffsetDateTime, Boolean)] = for
    m            <- fromOption(dateTime.findFirstMatchIn(raw))
                      .orElseFail(new RuntimeException(s"Could not parse date from '$raw'"))
    month        <- fromOption(months.get(m.group(2).toLowerCase.take(3)))
                      .orElseFail(new RuntimeException(s"Unknown month in '$raw'"))
    day           = m.group(1).toInt
    year          = m.group(3).toInt
    hasTime       = m.group(4) != null
    localDate     = LocalDate.of(year, month, day)
    localDateTime =
      if hasTime then localDate.atTime(m.group(4).toInt, m.group(5).toInt)
      else localDate.atStartOfDay()
    offset        = cetZone.getRules.getOffset(localDateTime)
  yield OffsetDateTime.of(localDateTime, offset) -> hasTime

  // Reads the value that follows a labelled field (e.g. "Kdaj", "Kje") on the detail page.
  private def fieldValue(document: Document, label: String): Task[Option[String]] =
    document
      .query("span")
      .map(
        _.find(_.text.trim.equalsIgnoreCase(label))
          .flatMap(span => Option(span.nextElementSibling()))
          .map(_.text.trim)
          .filter(_.nonEmpty)
      )

  private def parseDetail(client: Client, sourceURL: URL, eventURL: URL): RIO[Scope, Event] = for
    document <- client.requestMetered(Request.get(eventURL)).flatMap(_.body.asDocument)

    title <- document.first("h1").map(_.text.trim)

    rawWhen                       <- fieldValue(document, "Kdaj")
                                       .flatMap(fromOption(_).orElseFail(new RuntimeException("No 'Kdaj' field found")))
    (startDateTime, hasStartTime) <- parseDateTime(rawWhen)

    location    <- fieldValue(document, "Kje")
    description <- document.queryFirst("h1 + p").map(_.map(_.text.trim).filter(_.nonEmpty))

    hashBytes = digest.digest(eventURL.toString.getBytes(StandardCharsets.UTF_8))
    id        = hashBytes.map("%02x".format(_)).mkString
  yield Event
    .empty(
      id = s"kcui-$id",
      meetupID = meetup.id,
      source = KCUI,
      sourceURL = sourceURL,
      title = title,
      startDateTime = startDateTime
    )
    .copy(
      description = description,
      eventURL = Some(eventURL),
      endDateTime = None,
      hasStartTime = hasStartTime,
      hasEndTime = false,
      locationName = location
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    listingURL <- ZIO.succeed(url.path("/izobrazevanja"))
    document   <- client.requestMetered(Request.get(listingURL)).flatMap(_.body.asDocument)
    hrefs      <- document
                    .query("a[href]")
                    .map(_.map(_.attr("href")).filter(eventHref.matches).distinct)
    eventURLs   = hrefs.map(url.path)
    events     <- ZIO.foreach(eventURLs)(eventURL =>
                    parseDetail(client, listingURL, eventURL).asSome
                      .catchAll(err => logWarning(s"Failed to parse KCUI event $eventURL: ${err.getMessage}").as(None))
                  )
  yield events.flatten
