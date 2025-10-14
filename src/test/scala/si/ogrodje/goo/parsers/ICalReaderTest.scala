package si.ogrodje.goo.parsers

import si.ogrodje.goo.ZIP
import zio.*
import zio.test.*

import java.time.OffsetDateTime

object ICalReaderTest extends ZIOSpecDefault:

  import si.ogrodje.goo.EventOps.*

  def spec: Spec[Environment & (TestEnvironment & Scope), Any] = suite("ICalParserTest")(
    test("should parse Finance IKT") {
      for
        ics    <- ZIP.get("/ikt.ics.zip", "ikt.ics")
        after   = OffsetDateTime.of(2025, 4, 10, 0, 0, 0, 0, OffsetDateTime.now().getOffset).minusMonths(2)
        before  = after.plusMonths(4)
        events <- ICalReader.fromRaw(ics, after = after, before = Some(before))
      yield assertTrue(
        events.size == 25,
        Set("SRC Tech day 2025", "Dnevi slovenske informatike 2025").subsetOf(events.map(_.title).toSet)
      )
    },
    test("should parse SASA") {
      for
        ics   <- ZIP.get("/sasa.ical.zip", "sasa.ical")
        after  = OffsetDateTime.of(2025, 8, 8, 0, 0, 0, 0, OffsetDateTime.now().getOffset).minusMonths(2)
        before = after.plusMonths(6)

        events <- ICalReader.fromRaw(ics, after = after, before = Some(before))
        results = events.map(e => (e.title, e.startDateTime.local, e.endDateTime.map(_.local)))
      yield assertTrue(
        results.size == 2,
        results
          .map(_._1)
          .toSet
          .subsetOf(
            Set(
              "STARTUP CHALLENGE 2025",
              "SAŠA za mlade"
            )
          )
      )
    }
  )
