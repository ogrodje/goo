package si.ogrodje.goo.parsers

import zio.*
import zio.test.*
import zio.test.Assertion.*
import si.ogrodje.goo.ZIP

import java.time.OffsetDateTime

object ICalReaderTest extends ZIOSpecDefault:
  val after  = OffsetDateTime.of(2025, 4, 10, 0, 0, 0, 0, OffsetDateTime.now().getOffset).minusMonths(2)
  val before = after.plusMonths(4)

  def spec = suite("ICalParserTest") {
    test("should parse") {
      for
        ics    <- ZIP.get("/ikt.ics.zip", "ikt.ics")
        events <- ICalReader.fromRaw(ics, after = after, before = Some(before))
        _       = events.foreach(println(_))
      yield assertTrue(events.size == 25)
    }
  }
