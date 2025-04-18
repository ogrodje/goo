package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.{parser, Decoder, Json}
import si.ogrodje.goo.models.{Event, Meetup, Source}
import zio.ZIO.{attempt, fromOption, logWarning}
import zio.http.{Client, Request, URL}
import zio.{durationInt, Scope, ZIO}

import java.time.{LocalDate, OffsetDateTime, ZoneId}

final case class TehnoloskiParkLjubljanaParser(meetup: Meetup) extends Parser:
  import DocumentOps.*

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

  private def fetchMoreDetailsForEvent(
    client: Client,
    event: Event
  ): ZIO[Scope, Throwable, Event] = {
    for
      document      <- client.request(Request.get(event.eventURL.get)).flatMap(_.body.asDocument)
      maybeDuration <-
        document
          .queryFirst("p.eventDate")
          .map(_.map(_.text().replace("Datum dogodka: ", "").trim))
      rawDuration   <- fromOption(maybeDuration).orElseFail(new RuntimeException("No duration found."))

      ((startDateTime, hasStartTime), (endDateTime, hasEndTime)) <-
        ZIO.fromTry(TehnoloskiParkLjubljanaDurationParser.parse(rawDuration))
    yield event.copy(
      startDateTime = startDateTime,
      hasStartTime = hasStartTime,
      endDateTime = Some(endDateTime),
      hasEndTime = hasEndTime
    )
  }.orElse(
    logWarning(s"Failed getting duration details from ${event.eventURL.get}").as(event)
  )

  override protected def parse(
    client: Client,
    url: URL
  ): ZIO[Scope & Browser, Throwable, List[Event]] = for
    now            <- zio.Clock.instant
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

    // Initial dogodkiJSON does NOT have detailed enough events additional request is needed
    previewEvents <- collectEventsFromJson(url, eventsURL, dogodkiJson)

    events <-
      ZIO
        .foreachPar(
          previewEvents.filter(_.startDateTime.isAfter(now.atZone(cetZone).minusMonths(1).toOffsetDateTime))
        )(e => fetchMoreDetailsForEvent(client, e).delay(1.second))
  yield events
