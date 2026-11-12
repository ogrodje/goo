package si.ogrodje.goo.parsers

import com.microsoft.playwright.Browser
import io.circe.parser.parse as parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import si.ogrodje.goo.ClientOps.requestMetered
import si.ogrodje.goo.models.Source.MPIKKoroska
import si.ogrodje.goo.models.{Event, Meetup}
import zio.ZIO.logWarning
import zio.http.{Client, Request, URL}
import zio.{Clock, RIO, Scope, ZIO}

import java.time.{LocalDate, LocalTime, OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

/** Events of Mrežni podjetniški inkubator Koroška (MPIK), on RRA Koroška's site
  * (https://rra-koroska.si/aktualno/dogodki/podjetnistvo).
  *
  * RRA Koroška files its events under three groups: podjetništvo (entrepreneurship: MPIK, PONI, trainings), regionalni
  * razvoj and turizem; only podjetništvo is read. Each card on that listing has a title, a link, its dates and a time:
  * {{{
  * <h3><a href="/aktualno/dogodki/mpik-akademija-uspeha-2026-…">MPIK akademija uspeha 2026: …</a></h3>
  * <li class="wDogodki__info--date"><div><span>17.</span>&nbsp;<span>11.</span>&nbsp;<span>2026</span></div></li>
  * <li class="wDogodki__info--time"><svg …/>16.00 - 19.00</li>
  * }}}
  * A card may list several dates, split by `span.dateDivider`, with the year only on the last one, and a range as one
  * day ("25.–26."). Monthly roundups ("Aktualni podjetniški dogodki in razpisi …") list other organisers' events and
  * are skipped. A series (e.g. "MPIK akademija uspeha 2026") becomes one event per date, except dates that have a card
  * of their own (titled "MPIK akademija uspeha 2026: …").
  *
  * The place is only on the event's page (`li.wDogodki__info--place`), so recent and upcoming events' pages are read, a
  * bounded number of them; the description comes from the page's calendar data (`$.flVars.rra.aCalendar`).
  */
final case class MPIKKoroskaParser(meetup: Meetup) extends Parser:
  import MPIKKoroskaParser.*

  private def at(date: LocalDate, time: LocalTime): OffsetDateTime =
    val localDateTime = date.atTime(time)
    OffsetDateTime.of(localDateTime, zone.getRules.getOffset(localDateTime))

  private def page(client: Client, url: URL): RIO[Scope, Document] = for
    response <- client.requestMetered(Request.get(url))
    _        <- ZIO.unless(response.status.isSuccess)(ZIO.fail(new RuntimeException(s"$url: ${response.status}")))
    body     <- response.body.asString
  yield Jsoup.parse(body)

  private def details(client: Client, card: Card): RIO[Scope, Details] =
    page(client, site.path(card.path))
      .map(detailsIn)
      .catchAll(err => logWarning(s"MPIK: no details for ${card.path}: ${err.getMessage}").as(Details(None, None)))

  private def toEvents(card: Card, details: Details, ownCards: List[Card]): List[Event] =
    val sessions = card.days.filterNot(day => card.days.size > 1 && hasOwnCard(card, day, ownCards))
    sessions.map { day =>
      val start = at(day.from, card.start.getOrElse(LocalTime.MIDNIGHT))
      val end   = day.to match
        case Some(to) => Some(at(to, card.end.orElse(card.start).getOrElse(LocalTime.MIDNIGHT)))
        case None     => card.end.map(at(day.from, _)).filter(_.isAfter(start))

      Event
        .empty(
          id = if card.days.size > 1 then s"mpik-${card.slug}-${day.from}" else s"mpik-${card.slug}",
          meetupID = meetup.id,
          source = MPIKKoroska,
          sourceURL = listing,
          title = card.title,
          startDateTime = start
        )
        .copy(
          description = details.description,
          eventURL = Some(site.path(card.path)),
          endDateTime = end,
          hasStartTime = card.start.isDefined,
          hasEndTime = day.to.isEmpty && card.end.isDefined && end.isDefined,
          locationName = details.place
        )
    }

  override protected def parse(client: Client, url: URL): RIO[Scope & Browser, List[Event]] = for
    document <- page(client, listing)
    today    <- Clock.localDateTime.map(_.toLocalDate)
    cards     = cardsIn(document).filterNot(_.isRoundup).filter(_.lastDay.isAfter(today.minusMonths(monthsBack)))
    // Pages of recent and upcoming events only; older events keep what the listing says.
    withPages = cards.filter(_.lastDay.isAfter(today.minusDays(detailsDaysBack))).take(maxDetails).toSet
    events   <- ZIO.foreach(cards)(card =>
                  (if withPages.contains(card) then details(client, card) else ZIO.succeed(Details(None, None)))
                    .map(toEvents(card, _, cards))
                )
  yield events.flatten

object MPIKKoroskaParser:
  private val zone    = ZoneId.of("Europe/Ljubljana")
  val site: URL       = URL.decode("https://rra-koroska.si").toOption.get
  val listing: URL    = site.path("/aktualno/dogodki/podjetnistvo")
  private val dogodki = "/aktualno/dogodki/"

  private val monthsBack      = 12L
  private val detailsDaysBack = 30L
  private val maxDetails      = 30

  /** One date of a card, or a range of days ("25.–26. 11."). */
  final private[parsers] case class Day(from: LocalDate, to: Option[LocalDate])

  final private[parsers] case class Card(
    path: String,
    title: String,
    days: List[Day],
    start: Option[LocalTime],
    end: Option[LocalTime]
  ):
    def slug: String       = path.stripPrefix(dogodki).stripSuffix("/").replaceAll("[^A-Za-z0-9_-]", "-")
    def isRoundup: Boolean = slug.startsWith("aktualni-podjetniski-dogodki")
    def lastDay: LocalDate = days.map(d => d.to.getOrElse(d.from)).max

  final private[parsers] case class Details(place: Option[String], description: Option[String])

  private def text(e: Element): String = e.text.replace(' ', ' ').replaceAll("\\s+", " ").trim

  private val dayMonth: Regex = """(\d{1,2})\.(?:\s*[–—-]\s*(\d{1,2})\.)?""".r
  private val number: Regex   = """\d{1,4}""".r

  /** The groups of a date list ("19. | 5." … divided by `span.dateDivider`) as days; the year is on the last group
    * only, and goes back one for a month later than the next one's (a series across New Year).
    */
  def daysIn(dateDiv: Element): List[Day] =
    val groups = dateDiv.children.asScala
      .foldLeft(List(List.empty[String])) { (acc, span) =>
        if span.hasClass("dateDivider") then Nil :: acc else (text(span) :: acc.head) :: acc.tail
      }
      .map(_.reverse.filter(_.nonEmpty))
      .reverse
      .filter(_.nonEmpty)

    // (day, last day of a range, month) per group; the year from the last group.
    val parsed = groups.flatMap { parts =>
      for
        dm    <- parts.headOption.flatMap(dayMonth.findPrefixMatchOf)
        month <- parts.lift(1).flatMap(number.findFirstIn).flatMap(_.toIntOption)
      yield (dm.group(1).toInt, Option(dm.group(2)).map(_.toInt), month)
    }
    val year   = groups.lastOption.flatMap(_.lift(2)).flatMap(number.findFirstIn).flatMap(_.toIntOption)

    year.toList.flatMap { lastYear =>
      parsed
        .foldRight((List.empty[Day], lastYear, 13)) { case ((day, toDay, month), (days, year, nextMonth)) =>
          val y = if month > nextMonth then year - 1 else year
          val d = Try(LocalDate.of(y, month, day)).toOption.map(from =>
            Day(from, toDay.flatMap(t => Try(LocalDate.of(y, month, t)).toOption).filter(_.isAfter(from)))
          )
          (d.toList ++ days, y, month)
        }
        ._1
    }

  private val times: Regex = """(\d{1,2})[.:](\d{2})(?:\s*[–—-]\s*(\d{1,2})[.:](\d{2}))?""".r.unanchored

  private def clock(h: String, m: String): Option[LocalTime] = Try(LocalTime.of(h.toInt, m.toInt)).toOption

  /** "16.00 - 19.00" -> (16:00, Some(19:00)); "8.30" -> (08:30, None). */
  def timesIn(raw: String): Option[(LocalTime, Option[LocalTime])] =
    times.findFirstMatchIn(raw).flatMap { m =>
      clock(m.group(1), m.group(2)).map(_ -> Option(m.group(3)).flatMap(h => clock(h, m.group(4))))
    }

  def cardsIn(document: Document): List[Card] =
    document
      .select("div.wAktualno")
      .asScala
      .toList
      .flatMap { card =>
        for
          link  <- Option(card.selectFirst("h3 a[href]"))
          path   = link.attr("href")
          if path.startsWith(dogodki)
          title  = text(link)
          if title.nonEmpty
          dates <- Option(card.selectFirst("li.wDogodki__info--date div"))
          days   = daysIn(dates)
          if days.nonEmpty
          time   = Option(card.selectFirst("li.wDogodki__info--time")).flatMap { li =>
                     li.select("svg").remove(); timesIn(text(li))
                   }
        yield Card(path, title, days, time.map(_._1), time.flatMap(_._2))
      }
      .distinctBy(_.path)

  /** A series' date that has a card of its own: "MPIK akademija uspeha 2026: …" on that day. */
  private def hasOwnCard(series: Card, day: Day, cards: List[Card]): Boolean =
    cards.exists(other =>
      other.path != series.path && other.days.sizeIs == 1 && other.days.head.from == day.from &&
        other.title.toLowerCase.startsWith(series.title.toLowerCase.stripSuffix(" "))
    )

  private val calendarData: Regex = """(?s)\$\.flVars\s*=\s*(\{.*?\});\s""".r.unanchored

  /** The event page's place (the first `li.wDogodki__info--place`: the event's own, before related events) and the
    * description of its calendar data, when it has some.
    */
  def detailsIn(document: Document): Details =
    // The icon's <title> ("Lokacija") is not part of the place.
    val place       = Option(document.selectFirst("ul.wDogodki__info li.wDogodki__info--place")).map { li =>
      li.select("svg").remove(); text(li)
    }
      .filter(_.nonEmpty)
    val description = document
      .select("script")
      .asScala
      .iterator
      .map(_.data)
      .collectFirst { case calendarData(json) => json }
      .flatMap(json => parseJson(json).toOption)
      .flatMap(_.hcursor.downField("rra").downField("aCalendar").get[String]("description").toOption)
      .map(html => Jsoup.parse(html).text.replace(' ', ' ').replaceAll("\\s+", " ").trim)
      .filter(_.nonEmpty)
    Details(place, description)
