package si.ogrodje.goo.parsers

import java.time.{LocalDateTime, OffsetDateTime, ZoneId}
import java.time.format.DateTimeFormatter
import scala.util.Try

object TehnoloskiParkLjubljanaDurationParser:
  private type TimePoint = (OffsetDateTime, Boolean)
  private type Duration  = (TimePoint, TimePoint)
  final private val cetZone = ZoneId.of("Europe/Ljubljana")

  extension (localDateTime: LocalDateTime) def local: OffsetDateTime = localDateTime.atZone(cetZone).toOffsetDateTime

  private def fromDateRange(raw: String): Try[Duration] = Try:
    val pattern   = raw"(\d{2}\.\s\d{2}\.\s\d{4})\s*-\s*(\d{2}\.\s\d{2}\.\s\d{4})".r
    val formatter = DateTimeFormatter.ofPattern("dd. MM. yyyy HH:mm")

    raw match
      case pattern(startDateRaw, endDateRaw) =>
        val startDate = LocalDateTime.parse(startDateRaw + " 00:00", formatter)
        val endDate   = LocalDateTime.parse(endDateRaw + " 23:59", formatter)
        (startDate.local -> false, endDate.local -> false)
      case _                                 =>
        throw new IllegalArgumentException(s"Invalid date range format: $raw")

  private def fromDateWithTime(raw: String): Try[Duration] = Try:
    val pattern   = raw"(\d{2}\.\s\d{2}\.\s\d{4})\s*ob\s*(\d{2}:\d{2})".r
    val formatter = DateTimeFormatter.ofPattern("dd. MM. yyyy HH:mm")
    raw match
      case pattern(startDateRaw, timeRaw) =>
        val startDate = LocalDateTime.parse(startDateRaw + " " + timeRaw, formatter).local
        (startDate -> true, startDate.plusHours(2L) -> false)
      case _                              =>
        throw new IllegalArgumentException(s"Invalid date range format: $raw")

  private def fromJustDate(raw: String): Try[Duration] = Try:
    val pattern   = raw"(\d{2}\.\s\d{2}\.\s\d{4})".r
    val formatter = DateTimeFormatter.ofPattern("dd. MM. yyyy HH:mm")
    raw match
      case pattern(dateRaw) =>
        val startDateTime = LocalDateTime.parse(dateRaw + " 00:00", formatter)
        val endDateTime   = LocalDateTime.parse(dateRaw + " 23:59", formatter)
        (startDateTime.local -> false, endDateTime.local -> false)
      case _                =>
        throw new IllegalArgumentException(s"Invalid date format: $raw")

  def parse(raw: String): Try[Duration] =
    fromDateRange(raw).orElse(fromDateWithTime(raw)).orElse(fromJustDate(raw))
