package si.ogrodje.goo.parsers

import si.ogrodje.goo.models.Event
import zio.*
import zio.ZIO.{logError, logInfo}
import zio.http.{Body, Client, URL}

trait Parser:
  protected def parse(client: Client, url: URL): ZIO[Scope, Throwable, List[Event]]

  def parseWithClient(url: URL): ZIO[Scope & Client, Throwable, List[Event]] = for
    client <- ZIO.service[Client]
    events <-
      parse(client, url)
        .tapError(err => logError(s"Failed to parse $url: ${err.getMessage}"))
        .retryOrElse(
          policy = Schedule.exponential(10.seconds) && Schedule.recurs(3),
          orElse = (err, _) => ZIO.succeed(Nil)
        )
    _      <- logInfo(s"Collected ${events.size} events from $url")
  yield events
