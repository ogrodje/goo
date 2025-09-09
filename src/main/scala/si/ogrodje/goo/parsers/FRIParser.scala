package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import org.jsoup.nodes.Element
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.FRI
import si.ogrodje.goo.models.{Event, Meetup}
import si.ogrodje.goo.parsers.DocumentOps.*
import zio.ZIO.{foreach, fromOption}
import zio.http.{Client, Request, URL}
import zio.{RIO, Scope}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import java.util.Locale
import scala.jdk.CollectionConverters.*

final case class FRIParser(meetup: Meetup) extends Parser:
  private val digest    = MessageDigest.getInstance("SHA-1")
  private val formatter = DateTimeFormatter.ofPattern("d. MMM yyyy, HH:mm", Locale.ENGLISH)

  private val replaceMonths: String => String =
    _.replace("jan", "Jan")
      .replace("feb", "Feb")
      .replace("mar", "Mar")
      .replace("apr", "Apr")
      .replace("maj", "May")
      .replace("jun", "Jun")
      .replace("jul", "Jul")
      .replace("avg", "Aug")
      .replace("sep", "Sep")
      .replace("okt", "Oct")
      .replace("nov", "Nov")
      .replace("dec", "Dec")

  private def parseOneEvent(url: URL)(e: Element) = for
    link <-
      fromOption(e.getElementsByTag("a").asList().asScala.map(_.attr("href")).headOption)
        .mapBoth(_ => new RuntimeException("No href found"), url.path(_))

    title <-
      fromOption(
        e.getElementsByTag("a")
          .asList()
          .asScala
          .map(_.text())
          .headOption
          .map(_.trim)
      )
        .orElseFail(new RuntimeException("No title"))

    dateDay <- fromOption(e.getElementsByClass("date-single-top").asScala.headOption.map(_.text()))
                 .orElseFail(new RuntimeException("Failed getting date-single-top"))

    dateMonth <- fromOption(e.getElementsByClass("date-single-bottom").asScala.headOption.map(_.text()))
                   .orElseFail(new RuntimeException("Failed getting date-single-bottom"))

    dateHour <- fromOption(e.getElementsByClass("dogodki-hour").asScala.headOption.map(_.text()))
                  .mapBoth(_ => new RuntimeException("Failed getting dogodki-hour"), _.replace("ob ", "").trim)

    // Date-time parsing. (There is no year in the calendar!)
    year     <- zio.Clock.localDateTime.map(_.getYear)
    start     = replaceMonths(s"${dateDay} ${dateMonth} ${year}, ${dateHour}")
    date      = LocalDateTime.parse(start, formatter).atOffset(ZoneOffset.of("+01:00"))

    // Hashing for ID
    hashBytes = digest.digest(link.toString.getBytes(StandardCharsets.UTF_8))
    id        = hashBytes.map("%02x".format(_)).mkString
  yield Event
    .empty(
      id = s"fri-${id}",
      meetupID = meetup.id,
      source = FRI,
      sourceURL = url,
      title = title,
      startDateTime = date
    )
    .copy(
      eventURL = Some(link),
      endDateTime = None, // There is no end date in the calendar. :(
      locationName = Some("Fakulteta za računalništvo in informatiko (UL)"),
      locationAddress = Some("Večna pot 113, 1000 Ljubljana")
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    document  <- client.requestMetered(Request.get(url.path("/sl/koledar-dogodkov"))).flatMap(_.body.asDocument)
    rawEvents <- document.query("div#vsi-dogodki div.dogodek-item")
    events    <-
      foreach(rawEvents)(parseOneEvent(url)).map(
        _.filterNot(_.eventURL.exists(_.path.toString.contains("javna-predstavitev")))
      )
  yield events
