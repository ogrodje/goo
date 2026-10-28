package si.ogrodje.goo.parsers

import si.ogrodje.goo.models.{Meetup, Source}
import zio.*
import zio.http.URL
import zio.test.*

/** Verifies EventbriteParser against a fixture mirroring Eventbrite's current organizer-page shape: events
  * server-rendered into `__NEXT_DATA__` at props.pageProps.upcomingEvents, with split start_date / start_time /
  * timezone fields (captured from a live organizer page).
  *
  * These exercise the pure decode seam (`eventsFromRawNextData`); the browser navigation itself is covered by
  * [[EventbriteLiveCheck]].
  */
object EventbriteParserTest extends ZIOSpecDefault:
  private val parser    = EventbriteParser(Meetup.make("ogrodje", "Ogrodje"))
  private val sourceURL = URL.decode("https://www.eventbrite.com/o/ogrodje-103424018581").toOption.get

  private val itemWithVenue =
    """{
      |  "id": "1986276123354",
      |  "name": "After Hours Premier: Pride Celebration",
      |  "url": "https://www.eventbrite.com/e/after-hours-premier-pride-celebration-tickets-1986276123354",
      |  "start_date": "2026-07-17", "start_time": "19:00:00",
      |  "end_date": "2026-07-17", "end_time": "22:30:00",
      |  "timezone": "Europe/Ljubljana",
      |  "is_online_event": false, "hide_start_date": false, "hide_end_date": false,
      |  "summary": "An evening celebration.",
      |  "primary_venue": { "name": "Kino Šiška",
      |    "address": { "localized_address_display": "Trg prekomorskih brigad 3, Ljubljana" } }
      |}""".stripMargin

  private val itemOnline =
    """{
      |  "id": "2000000000001",
      |  "name": "Online Webinar",
      |  "url": "https://www.eventbrite.com/e/online-webinar-tickets-2000000000001",
      |  "start_date": "2026-08-01", "start_time": "10:00:00",
      |  "end_date": "2026-08-01", "end_time": "11:00:00",
      |  "timezone": "Europe/Ljubljana",
      |  "is_online_event": true, "hide_start_date": false, "hide_end_date": true,
      |  "summary": "",
      |  "primary_venue": null
      |}""".stripMargin

  private def nextData(items: String): String =
    s"""{"props":{"pageProps":{"upcomingEventsTotal":2,"upcomingEvents":[$items]}}}"""

  private def collect(items: String): List[si.ogrodje.goo.models.Event] =
    parser.eventsFromRawNextData(sourceURL, nextData(items)).toOption.getOrElse(Nil)

  def spec = suite("EventbriteParserTest")(
    test("parses events from upcomingEvents in __NEXT_DATA__") {
      val events = collect(s"$itemWithVenue,$itemOnline")
      assertTrue(
        events.size == 2,
        events.exists(_.title == "After Hours Premier: Pride Celebration"),
        events.forall(_.source == Source.Eventbrite),
        events.forall(_.id.startsWith("eventbrite-"))
      )
    },
    test("parses split date/time/timezone into an OffsetDateTime") {
      val e = collect(itemWithVenue).head
      assertTrue(
        e.startDateTime.getYear == 2026,
        e.startDateTime.getMonthValue == 7,
        e.startDateTime.getDayOfMonth == 17,
        e.startDateTime.getHour == 19,
        e.endDateTime.exists(_.getHour == 22)
      )
    },
    test("extracts venue name and address for in-person events") {
      val e = collect(itemWithVenue).head
      assertTrue(
        e.locationName.contains("Kino Šiška"),
        e.locationAddress.exists(_.contains("Ljubljana")),
        e.eventURL.exists(_.toString.contains("/e/"))
      )
    },
    test("online events carry no location") {
      val e = collect(itemOnline).head
      assertTrue(e.locationName.isEmpty, e.locationAddress.isEmpty, !e.hasEndTime)
    },
    test("empty upcomingEvents yields no events") {
      assertTrue(collect("").isEmpty)
    },
    test("a malformed event item is skipped, valid ones kept") {
      val broken = """{"id":"x","name":"No dates"}"""
      val events = collect(s"$broken,$itemWithVenue")
      assertTrue(events.size == 1, events.head.title == "After Hours Premier: Pride Celebration")
    },
    test("invalid JSON payload returns a MalformedPayload failure") {
      val result = parser.eventsFromRawNextData(sourceURL, "not json")
      assertTrue(result.left.toOption.exists(_.isInstanceOf[EventbriteParser.MalformedPayload]))
    },
    test("a genuinely empty organizer (has organizer + empty upcomingEvents) is NOT flagged as captcha") {
      val emptyOrg =
        """{"props":{"pageProps":{"organizer":{"id":"1","name":"Ogrodje"},
          |"upcomingEvents":[],"upcomingEventsTotal":0}}}""".stripMargin
      assertTrue(parser.eventsFromRawNextData(sourceURL, emptyOrg) == Right(Nil))
    },
    test("a bot-challenge page (shell pageProps, no organizer/events) is detected as captcha") {
      // Mirrors the real interstitial: only shell keys, none of the organizer/events keys.
      val challenge =
        """{"props":{"pageProps":{"locale":"en-US","messages":{"Captcha":"x"},
          |"globalHeaderProps":{},"footerProps":{},"serverUrl":"x"}}}""".stripMargin
      val result    = parser.eventsFromRawNextData(sourceURL, challenge)
      assertTrue(result.left.toOption.exists(_.isInstanceOf[EventbriteParser.CaptchaDetected]))
    }
  )
