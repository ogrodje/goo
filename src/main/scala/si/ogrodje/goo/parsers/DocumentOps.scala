package si.ogrodje.goo.parsers

import io.circe.{Decoder, Json}
import org.jsoup.nodes.{Document, Element}
import org.jsoup.parser.Parser
import zio.{Task, ZIO}
import zio.http.{Body, URL}

import scala.jdk.CollectionConverters.*
import io.circe.parser.parse

object DocumentOps:
  private type Query = String

  given urlDecoder: Decoder[URL] =
    Decoder.decodeString.emap(raw => URL.decode(raw).left.map(err => err.getMessage))

  extension (body: Body)
    def asDocument: Task[Document] = for
      bodyAsString <- body.asString
      document     <- ZIO.attempt(Parser.parse(bodyAsString, "UTF-8"))
    yield document

  extension (document: Document)
    def query(query: Query): Task[List[Element]]       = ZIO.attempt(document.select(query).asScala.toList)
    def queryFirst(path: Query): Task[Option[Element]] = query(path).map(_.headOption)
    def first(path: Query): Task[Element]              =
      queryFirst(path).flatMap(
        ZIO
          .fromOption(_)
          .orElseFail(new NoSuchElementException(s"No element found for $path"))
      )

  extension (element: Element)
    def textAsJson: Task[Json] = for
      readText <- ZIO.attempt(element.text())
      json     <- ZIO.fromEither(parse(readText))
    yield json

    def dataAsJson: Task[Json] = for
      readText <- ZIO.attempt(element.data())
      json     <- ZIO.fromEither(parse(readText))
    yield json
