package si.ogrodje.goo.parsers

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.{Calendar, Component}
import zio.ZIO.{logDebugCause, logWarningCause}
import zio.http.URL
import zio.{Cause, Scope, Task, UIO, URIO, ZIO}

import java.io.StringReader
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, OffsetDateTime, ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try

object ICalOps:
  final private val cetZone = ZoneId.of("Europe/Ljubljana")

  extension (calendar: Calendar)
    def events(kind: String = Component.VEVENT): List[VEvent] =
      calendar.getComponents(kind).asScala.toList

  extension (event: VEvent)
    def summary: Task[String] = ZIO.attempt(event.getSummary).flatMap(s => ZIO.attempt(s.getValue)).map(_.trim)
    def uid: Task[String]     = ZIO
      .attempt(event.getUid)
      .flatMap(s => ZIO.fromOption(s.toScala).orElseFail(new RuntimeException("Failed reading uid")))
      .flatMap(s => ZIO.attempt(s.getValue))

    private def parseRawDateTime(raw: String, beginning: Boolean = true): Either[Throwable, (OffsetDateTime, Boolean)] =
      if raw.length == 8 then
        Try(
          LocalDateTime
            .parse(
              // Faking time for easier parsing.
              if beginning then raw + " 00:01:00" else raw + " 23:59:00",
              DateTimeFormatter.ofPattern("yyyyMMdd[ [HH][:mm][:ss][.SSS]]")
            )
            .minusHours(
              if beginning then 0 else 24L
            )
            .atZone(cetZone)
            .toOffsetDateTime
        ).map(_ -> false).toEither
      else
        val hasZSuffix    = raw.endsWith("Z")
        val hasExplicitTz = raw.matches(""".*[+-]\d{2}:?\d{2}$""")
        // Try order: UTC(Z), explicit offset, floating (local)
        val result        =
          if hasZSuffix then
            // UTC format strictly: yyyyMMdd'T'HHmmss'Z'
            Try(
              ZonedDateTime
                .parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX"))
                .withZoneSameInstant(cetZone)
                .toOffsetDateTime
            )
          else if hasExplicitTz then
            // Offset present: yyyyMMdd'T'HHmmss±HHmm or ±HH:MM
            Try(
              OffsetDateTime
                .parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssXXXXX"))
                .atZoneSameInstant(cetZone)
                .toOffsetDateTime
            )
          else
            // Floating local time: interpret in cetZone
            Try(
              LocalDateTime
                .parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(cetZone)
                .toOffsetDateTime
            )
        result.map(_ -> true).toEither

    def dateTimeStart: ZIO[Any, Throwable, (OffsetDateTime, Boolean)] =
      ZIO
        .attempt(event.getDateTimeStart)
        .flatMap(s => ZIO.attempt(s.getValue))
        .flatMap(p => ZIO.fromEither(parseRawDateTime(p)))

    def dateTimeEnd: ZIO[Any, Throwable, (Option[OffsetDateTime], Boolean)] =
      ZIO
        .attempt(event.getEndDate(true))
        .map(s => s.toScala.map(_.getValue))
        .flatMap {
          case Some(p) => ZIO.fromEither(parseRawDateTime(p, beginning = false)).map(v => Some(v._1) -> v._2)
          case None    => ZIO.succeed((None, false))
        }

    def isSameDayEvent: ZIO[Any, Throwable, Boolean] = for
      start   <- ZIO.attempt(event.getDateTimeStart).map(_.getValue)
      endDate <- ZIO.attempt(event.getEndDate(true)).map(s => s.toScala.map(_.getValue))
    yield endDate.exists(end => start.take(8) == end.take(8))

    def url: URIO[Any, Option[URI]] =
      ZIO.fromTry(Try(event.getUrl).map(_.getUri)).option

    def details: ZIO[Any, Throwable, Option[String]] =
      ZIO.attempt(event.getDescription).map(s => Option(s.getValue).map(_.trim))

    def detailsUrls: ZIO[Any, Throwable, List[String]] = details.flatMap {
      case Some(txt) => ZIO.succeed(extractUrls(txt))
      case None      => ZIO.succeed(List.empty)
    }

    private def extractUrls(details: String): List[String] =
      val hrefRegex = """href=["']([^"']+)["']""".r
      val hrefUrls  = hrefRegex
        .findAllMatchIn(details)
        .map(_.group(1))
        .toList
        .filter(url => url.startsWith("http://") || url.startsWith("https://"))

      val urlRegex  = """(https?://[^\s"'<>()]+)""".r
      val plainUrls = urlRegex.findAllMatchIn(details).map(_.group(0)).toList

      (hrefUrls ++ plainUrls).distinct

final case class ICalEvent(
  id: String,
  title: String,
  startDateTime: OffsetDateTime,
  description: Option[String] = None,
  eventURL: Option[URL] = None,
  endDateTime: Option[OffsetDateTime] = None,
  hasStartTime: Boolean = true,
  hasEndTime: Boolean = true,
  locationName: Option[String] = None,
  locationAddress: Option[String] = None
)

object ICalReader:
  import ICalOps.*

  private val cetZone               = ZoneId.of("Europe/Paris")
  private val since: OffsetDateTime = OffsetDateTime.now(cetZone).minusMonths(2)

  private def decodeUrl(url: Option[String]): UIO[Option[URL]] = url match
    case Some(value) => ZIO.fromEither(URL.decode(value)).option
    case None        => ZIO.none

  def fromRaw(
    raw: String,
    after: OffsetDateTime = since,
    before: Option[OffsetDateTime] = None
  ): ZIO[Scope, Throwable, List[ICalEvent]] = for
    stringReader               <- ZIO.fromAutoCloseable(ZIO.attempt(new StringReader(raw)))
    calendar                   <- ZIO.attempt(new CalendarBuilder().build(stringReader))
    (parsingErrors, rawEvents) <-
      ZIO
        .foreach(calendar.events()) { event =>
          (for
            uid                           <- event.uid.map(_.replaceAll("@google.com", ""))
            summary                       <- event.summary.map(_.trim)
            (startDateTime, hasStartTime) <- event.dateTimeStart
            (endDateTime, hasEndTime)     <- event.dateTimeEnd
            details                       <- event.details.map(_.map(_.trim))
            urls                          <- event.detailsUrls
            eventUrl                      <- event.url.map(_.toList.map(_.toString))
            url                            = (eventUrl ++ urls).distinct.headOption
            encodedUrl                    <- decodeUrl(url)
          yield ICalEvent(
            id = uid,
            title = summary,
            startDateTime = startDateTime,
            description = details,
            eventURL = encodedUrl,
            endDateTime = endDateTime,
            hasStartTime = hasStartTime,
            hasEndTime = hasEndTime
            // locationName = ???
            // locationAddress = ???
          )).either
        }
        .map(_.partitionMap(identity))

    _ <- ZIO.foreachDiscard(parsingErrors)(err =>
           logDebugCause("Parsing of event has failed. Event skipped.", Cause.fail(err))
         )

    events =
      rawEvents.filter { e =>
        before.fold(
          e.startDateTime.isAfter(after)
        )(b => e.startDateTime.isAfter(after) && e.startDateTime.isBefore(b))
      }.sortBy(_.startDateTime)
  yield events
