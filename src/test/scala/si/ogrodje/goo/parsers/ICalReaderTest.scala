package si.ogrodje.goo.parsers

import zio.*
import zio.test.*
import zio.test.Assertion.*
import si.ogrodje.goo.ZIP

import java.time.{LocalDateTime, OffsetDateTime, ZoneId}
import scala.io.AnsiColor.*

object ICalReaderTest extends ZIOSpecDefault:
  private val after  = OffsetDateTime.of(2025, 4, 10, 0, 0, 0, 0, OffsetDateTime.now().getOffset).minusMonths(2)
  private val before = after.plusMonths(4)

  extension (offsetDateTime: OffsetDateTime) def local: LocalDateTime = offsetDateTime.toLocalDateTime

  def spec = suite("ICalParserTest") {
    test("should parse") {
      for
        ics    <- ZIP.get("/ikt.ics.zip", "ikt.ics")
        events <- ICalReader.fromRaw(ics, after = after, before = Some(before))
        _       =
          events
            .filter(e =>
              e.title.contains("NIS-2") || e.title.contains("Dnevi slovenske") ||
                e.title.contains("SRC Tech day 2025")
            )
            .foreach { event =>
              println(
                s"${event.title} \nStart: ${RED}${event.startDateTime.local} (${event.hasStartTime})${RESET}, " +
                  s"End: ${BLUE}${event.endDateTime.map(_.local)} (${event.hasEndTime})}${RESET}\n"
              )
            }
      yield assertTrue(events.size == 25)
    }
  }
