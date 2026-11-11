package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import org.jsoup.nodes.Element
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.FRI
import si.ogrodje.goo.models.{Event, Meetup}
import si.ogrodje.goo.parsers.DocumentOps.*
import zio.ZIO.{fromOption, logWarning}
import zio.http.{Client, Request, URL}
import zio.{Clock, RIO, Scope, Task, ZIO}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.util.Try
import scala.util.matching.Regex

/** Events from FRI's calendar (https://fri.uni-lj.si/sl/koledar-dogodkov).
  *
  * Each `div.dogodek-item` card has a day and month but no year, and one of two date layouts:
  *   - a single day: `.date-single-top` ("16.") and `.date-single-bottom` ("nov");
  *   - several days: `.date-top` and `.date-bottom`, each with `span.dan` ("24.") and `span.mesec` ("nov").
  *
  * A card that can't be read is logged and skipped, so one odd card no longer drops the whole calendar (a multi-day
  * event did exactly that from 2026-11-03).
  */
final case class FRIParser(meetup: Meetup) extends Parser:
  import FRIParser.*

  private def parseOneEvent(url: URL, today: LocalDate)(e: Element): Task[Event] = for
    anchor <- fromOption(Option(e.selectFirst(".event-title a[href]")).orElse(Option(e.selectFirst("a[href]"))))
                .orElseFail(new RuntimeException("No link found"))
    link    = url.path(anchor.attr("href"))
    title  <- fromOption(Option(anchor.text).map(_.trim).filter(_.nonEmpty)).orElseFail(new RuntimeException("No title"))

    (startDay, endDay) <- fromOption(dates(e)).orElseFail(new RuntimeException(s"No date found for '$title'"))
    startDate          <- fromOption(dateOf(startDay, today))
                            .orElseFail(new RuntimeException(s"Could not read date '$startDay' for '$title'"))
    endDate             = endDay.flatMap(dateOf(_, today)).map(d => if d.isBefore(startDate) then d.plusYears(1) else d)

    startTime = Option(e.selectFirst(".dogodki-hour")).flatMap(h => timeIn(h.text))
  yield Event
    .empty(
      id = s"fri-${sha1(link.toString)}",
      meetupID = meetup.id,
      source = FRI,
      sourceURL = url,
      title = title,
      startDateTime = at(startDate, startTime.getOrElse(LocalTime.MIDNIGHT))
    )
    .copy(
      eventURL = Some(link),
      // The calendar has no end time; a multi-day event ends on its last day.
      endDateTime = endDate.filter(_.isAfter(startDate)).map(at(_, startTime.getOrElse(LocalTime.MIDNIGHT))),
      hasStartTime = startTime.isDefined,
      hasEndTime = false,
      locationName = Some("Fakulteta za računalništvo in informatiko (UL)"),
      locationAddress = Some("Večna pot 113, 1000 Ljubljana")
    )

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    document  <- client.requestMetered(Request.get(url.path("/sl/koledar-dogodkov"))).flatMap(_.body.asDocument)
    rawEvents <- document.query("div#vsi-dogodki div.dogodek-item")
    today     <- Clock.localDateTime.map(_.toLocalDate)
    events    <- ZIO.foreach(rawEvents)(raw =>
                   parseOneEvent(url, today)(raw).asSome
                     .catchAll(err => logWarning(s"Failed to parse FRI event: ${err.getMessage}").as(None))
                 )
  // No cutoff needed: dateOf picks the year closest to today, so no event lands more than ~6 months in the past.
  yield events.flatten.filterNot(_.eventURL.exists(_.path.toString.contains("javna-predstavitev")))

object FRIParser:
  private val zone = ZoneId.of("Europe/Ljubljana")

  /** A day and a month as printed on a card, e.g. ("16.", "nov"). */
  final private[parsers] case class DayMonth(day: String, month: String)

  // Slovenian month abbreviations as the calendar prints them (first three letters are unique).
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

  private val dayNumber: Regex = """(\d{1,2})""".r.unanchored
  private val clockTime: Regex = """(\d{1,2})[:.](\d{2})""".r.unanchored

  private def text(e: Element, query: String): Option[String] =
    Option(e.selectFirst(query)).map(_.text.trim).filter(_.nonEmpty)

  /** The start (and, for a multi-day event, end) day and month of a card, in either layout. */
  private[parsers] def dates(e: Element): Option[(DayMonth, Option[DayMonth])] =
    val single =
      for
        day   <- text(e, ".date-single-top")
        month <- text(e, ".date-single-bottom")
      yield DayMonth(day, month) -> None

    val range =
      for
        startDay   <- text(e, ".date-top .dan")
        startMonth <- text(e, ".date-top .mesec")
      yield DayMonth(startDay, startMonth) ->
        (for
          endDay   <- text(e, ".date-bottom .dan")
          endMonth <- text(e, ".date-bottom .mesec")
        yield DayMonth(endDay, endMonth))

    single.orElse(range)

  /** The calendar shows no year: take the year that puts the date closest to `today`, so that in December a January
    * event lands in the next year, and in January a December one in the previous year.
    */
  private[parsers] def dateOf(dayMonth: DayMonth, today: LocalDate): Option[LocalDate] =
    for
      day       <- dayNumber.findFirstMatchIn(dayMonth.day).map(_.group(1).toInt)
      month     <- months.get(dayMonth.month.trim.toLowerCase.take(3))
      candidates = List(today.getYear - 1, today.getYear, today.getYear + 1)
                     .flatMap(year => Try(LocalDate.of(year, month, day)).toOption)
      closest   <- candidates.minByOption(date => math.abs(date.toEpochDay - today.toEpochDay))
    yield closest

  /** "ob 17:15" -> 17:15. */
  private[parsers] def timeIn(raw: String): Option[LocalTime] =
    clockTime.findFirstMatchIn(raw).flatMap(m => Try(LocalTime.of(m.group(1).toInt, m.group(2).toInt)).toOption)

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private def sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8)).map("%02x".format(_)).mkString
