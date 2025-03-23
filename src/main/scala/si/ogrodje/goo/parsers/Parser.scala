package si.ogrodje.goo.parsers

import org.jsoup.nodes.{Document, Element}
import org.jsoup.parser.Parser
import si.ogrodje.goo.models.Event
import DocumentOps.asDocument
import io.circe.Json
import zio.http.{Body, Client, Request, URL}
import zio.*
import zio.ZIO.{logError, logInfo}

trait Parser:
  import DocumentOps.{*, given}
  protected def parse(client: Client, url: URL): ZIO[Scope, Throwable, List[Event]]

  def parseWithClient(url: URL): ZIO[Scope & Client, Throwable, List[Event]] = for
    client <- ZIO.service[Client]
    out    <- parse(client, url)
  yield out
