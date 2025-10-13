package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.*
import zio.ZIO.*
import zio.http.{Client, URL}
import zio.{Scope, ZIO}
import org.jsoup.Jsoup

import scala.jdk.CollectionConverters.*
import java.time.{LocalDateTime, OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter

final case class GZSParser(meetup: Meetup) extends Parser:
  private val formatter                                  = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
  private val cetZone                                    = ZoneId.of("Europe/Paris")
  private def parseDate(rawDate: String): OffsetDateTime =
    LocalDateTime.parse(rawDate, formatter).atZone(cetZone).toOffsetDateTime

  private def parseDates(raw: String): (OffsetDateTime, OffsetDateTime) =
    val Array(startDate, endDate) = raw.split("-", 2).map(_.trim)
    parseDate(startDate) -> parseDate(endDate)

  override protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]] = for
    startUrl <- ZIO.succeed(url.path("/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki"))
    page     <- ZIO.serviceWithZIO[Browser](b => ZIO.fromAutoCloseable(ZIO.attemptBlocking(b.newPage())))
    events   <-
      ZIO.attemptBlocking:
        page.navigate(startUrl.toString)
        page.locator("text=MESEČNI").click()
        page.waitForSelector(".CKDAllEvents")
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)")

        val eventsContent = page.locator(".CKDAllEvents").innerHTML()

        Jsoup.parse(eventsContent).select(".CKDMainEventBox").asScala.toList.map { element =>
          val id                           = element.select("#naslov input[type=hidden]").attr("value").trim
          val title                        = element.select("#naslov a").text()
          val eventUrl                     = element.select("#naslov a").attr("href")
          val datum                        = element.select("#datum span").text()
          val description                  = element.select("#povzetek").text().trim()
          val locationName                 = element.select("#kraj span").text().trim()
          val (startDateTime, endDateTime) = parseDates(datum)

          Event
            .empty(
              id = s"gzs-$id",
              meetupID = meetup.id,
              source = Source.GZS,
              sourceURL = startUrl,
              title = title,
              startDateTime = startDateTime
            )
            .copy(
              description = Some(description),
              eventURL = URL.decode(eventUrl).toOption,
              endDateTime = Some(endDateTime),
              locationName = Some(locationName)
            )
        }

    cutoff = OffsetDateTime.now().minusMonths(3)
  yield events.filter(_.startDateTime.isAfter(cutoff))
