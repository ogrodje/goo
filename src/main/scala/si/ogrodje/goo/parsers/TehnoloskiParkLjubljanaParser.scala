package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{parser, Decoder, Json}
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.{attempt, fromOption}
import zio.http.{Client, Request, URL}
import zio.{Scope, ZIO}

import java.time.{LocalDate, OffsetDateTime, ZoneId}

final case class TehnoloskiParkLjubljanaParser(meetup: Meetup) extends Parser:
  import DocumentOps.{*, given}

  private val cetZone = ZoneId.of("Europe/Ljubljana")

  private def localDateToCETOffsetDateTime(localDate: LocalDate): OffsetDateTime =
    val localDateTime = localDate.atStartOfDay()
    val offset        = cetZone.getRules.getOffset(localDateTime)
    OffsetDateTime.of(localDateTime, offset)

  private def collectEventsFromJson(baseURL: URL, sourceURL: URL, json: Json) =
    val (errors, values) = json.asArray.toList
      .flatMap(_.map { r =>
        for
          id            <- r.hcursor.get[Int]("id")
          startDateTime <- r.hcursor.get[LocalDate]("start")
          endDateTime   <- r.hcursor.get[LocalDate]("end")
          title         <- r.hcursor.get[String]("title")
          eventURL      <- r.hcursor.get[String]("link").map(baseURL.addPath)
        yield Event(
          id = s"tp-$id",
          meetupID = meetup.id,
          source = Source.TehnoloskiParkLjubljana,
          sourceURL = sourceURL,
          title = title,
          description = None,
          eventURL = Some(eventURL),
          startDateTime = localDateToCETOffsetDateTime(startDateTime),
          endDateTime = Some(localDateToCETOffsetDateTime(endDateTime)),
          locationName = None,
          locationAddress = None,
          hasStartTime = false,
          hasEndTime = false
        )
      }.toList)
      .partitionMap(identity)

    if errors.nonEmpty then ZIO.fail(errors.head) else ZIO.succeed(values)

  override protected def parse(
    client: Client,
    url: URL
  ): ZIO[Scope & Browser, Throwable, List[Event]] = for
    eventsURL      <- ZIO.succeed(url.addPath("/sl/koledar-dogodkov"))
    response       <- client.request(Request.get(eventsURL))
    document       <- response.body.asDocument
    scriptTags     <- document.query("script").map(_.filter(_.data().contains("dogodkiJSON")))
    scriptTag      <-
      fromOption(scriptTags.headOption.map(_.data))
        .orElseFail(new NoSuchElementException("No script tag found in the document."))
    dogodkiJsonRaw <-
      fromOption("dogodkiJSON = (.*);\\n".r.findFirstMatchIn(scriptTag).map(_.group(1)))
        .orElseFail(new NoSuchElementException("No dogodkiJSON payload found in script."))
    dogodkiJson    <- attempt(parser.parse(dogodkiJsonRaw).getOrElse(Json.obj()))
    events         <- collectEventsFromJson(url, eventsURL, dogodkiJson)

    timeAgo        = OffsetDateTime.now(cetZone).minusMonths(2)
    filteredEvents = events.filter(_.startDateTime.isAfter(timeAgo))
  yield filteredEvents
