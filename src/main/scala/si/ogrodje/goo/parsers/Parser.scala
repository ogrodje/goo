package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.{Event, Meetup}
import zio.*
import zio.ZIO.{logErrorCause, logInfo}
import zio.http.{Client, URL}

trait Parser:
  def meetup: Meetup

  protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]]

  def parseWithClient(url: URL): ZIO[Scope & Client & Browser, Throwable, List[Event]] = for
    client <- ZIO.service[Client]
    events <-
      parse(client, url)
        .tapError(err => logErrorCause(s"Failed to parse $url: ${err.getMessage}", Cause.fail(err)))
        .retryOrElse(
          policy = Schedule.exponential(10.seconds) && Schedule.recurs(3),
          orElse = (err, _) => logErrorCause(s"""Crash with retry: ${err.getMessage}""", Cause.fail(err)).as(Nil)
        )
    _      <- logInfo(s"Collected ${events.size} events from $url")
  yield events
