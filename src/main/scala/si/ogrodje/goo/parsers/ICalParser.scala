package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import si.ogrodje.goo.models.*
import zio.ZIO.logDebug
import zio.http.{Client, URL}
import zio.{Scope, ZIO}

import java.time.ZoneId

final case class ICalParser(meetup: Meetup) extends Parser:
  private val cetZone = ZoneId.of("Europe/Paris")

  override protected def parse(client: Client, url: URL): ZIO[Scope & Browser, Throwable, List[Event]] =
    logDebug(s"Parsing ${url.toString}. Not implemented.").as(List.empty)
