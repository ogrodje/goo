package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.{Event, Meetup}
import zio.*
import zio.ZIO.{logErrorCause, logInfo}
import zio.http.{Client, URL, ZClientAspect}
import zio.stream.ZStream

trait Parser:
  def meetup: Meetup

  protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]]

  private val followRedirects = ZClientAspect.followRedirects(3)((resp, message) => ZIO.logInfo(message).as(resp))

  private def parseWithClient(url: URL): RIO[Scope & Client & Browser, List[Event]] = for
    client <- ZIO.serviceWith[Client](_ @@ followRedirects)
    events <-
      parse(client, url)
        .tapError(err => logErrorCause(s"Failed to parse $url: ${err.getMessage}", Cause.fail(err)))
        .retryOrElse(
          policy = Schedule.exponential(10.seconds) && Schedule.recurs(3),
          orElse = (err, _) => logErrorCause(s"""Crash with retry: ${err.getMessage}""", Cause.fail(err)).as(Nil)
        )
    _      <- logInfo(s"Collected ${events.size} events from $url") when events.nonEmpty
  yield events

  final def streamEventsFrom(url: URL): ZStream[Scope & Client & Browser, Throwable, Event] =
    ZStream.fromZIO(parseWithClient(url)).flatMap(ZStream.fromIterable)
