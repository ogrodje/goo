package si.ogrodje.goo.parsers

import org.jsoup.Jsoup

import java.text.Normalizer
import java.time.{LocalDate, LocalTime}
import scala.util.Try
import scala.util.matching.Regex

/** When and where an event is, read from the text of a post that announces it, in Slovenian.
  *
  * Posts on incubators' WordPress sites (LUI, Pomurski tehnološki park, Bela krajina, Rogaška Slatina) have no event
  * fields; the post's date is when it was published. They write the event's date, time and place in many ways:
  *   - `KDAJ: 27. oktober 2026, med 13:00 – 16:00 KJE: LUI – …, Vojkova cesta 63, Ljubljana`
  *   - `Datum: torek, 13. oktober 2026` / `Čas: 14.00–17.00` / `Lokacija: LUI`
  *   - `Kdaj: Ponedeljek, 31. 8. ob 9.00 (do 12.00)` (no year), `KDAJ? 21.10.26 ob 16.00`
  *   - `Termin: Torek, 26. 5. 2026, 9:00 – 11:00`
  *   - `KDAJ: petek, 25. september in sobota, 26. september 2026` (two days)
  *   - `📅 KDAJ: petek, 20. november 2026, ob 17.00 – 19.00.` / `📍 LOKACIJA: Podjetniški inkubator Bela krajina`
  *   - `📅 Datum: 19. november 2026 ⏰ Čas: 16.00 – 19.00 📍 Kraj: Pomurski tehnološki park, Plese 9a`
  *
  * and, without any marker, in a sentence (read only when `sentences` is on, for sites that write so):
  *   - `…, ki bo dne 27.8.2026 s pričetkom ob 9.30 uri v Pomurskem tehnološkem parku.`
  *   - `…, ki bo v četrtek, 26. novembra 2026, ob 12. uri, …`
  */
object EventText:

  /** What a post's text says about when and where. */
  final case class Details(
    startDate: LocalDate,
    startTime: Option[LocalTime],
    endTime: Option[LocalTime],
    endDate: Option[LocalDate],
    location: Option[String]
  )

  private val months: Map[String, Int] = Map(
    "jan" -> 1,
    "feb" -> 2,
    "mar" -> 3,
    "apr" -> 4,
    "maj" -> 5,
    "jun" -> 6,
    "jul" -> 7,
    "avg" -> 8,
    "sep" -> 9,
    "okt" -> 10,
    "nov" -> 11,
    "dec" -> 12
  )

  // A line that says when: "KDAJ:", "Kdaj in kje:", "Datum:", "Termin:", "KDAJ?", or one starting with 📅/🗓.
  private val whenMarker: Regex  = """(?iu)^\W*(?:(?:kdaj|datum|termin)\b[^:?]{0,12}:|kdaj\s*\?|📅|🗓)""".r
  private val whereMarker: Regex = """(?iu)(?:\b(?:kje|lokacija)\s*:|^\W*kje\s*\?|📍)\s*""".r
  // "📍 Kraj: …", "📍 LOKACIJA: …": the label after the pin.
  private val whereLabel: Regex  = """(?iu)^(?:kje|lokacija|kraj|naslov)\s*:\s*""".r
  // Where the "when" part ends.
  private val stopMarker: Regex  = """(?iu)\b(?:kje|lokacija|kraj|prijav\w*|udeležba|cena)\s*:|📍""".r
  // A sentence about registering, not about the event: "Prijave zbiramo do 23. novembra", "najkasneje do 8.6.2026".
  private val deadline: Regex    = """(?iu)\b(?:prijav\w*|najkasneje|rok\b|do\s+(?:\p{L}+,?\s+)?\d)""".r

  // "13. november 2026", "25. september", "17. junija 2026", "03. 09. 2026", "31. 8.", "16.5.2026"
  private val date: Regex =
    """(?iu)\b(\d{1,2})\.\s*(?:(\d{1,2})\.|(jan|feb|mar|apr|maj|jun|jul|avg|sep|okt|nov|dec)[\p{L}]*)(?:\s*,?\s*(\d{4}))?""".r
  private val year: Regex = """\b(20\d\d)\b""".r

  // "9:00 – 10:30", "ob 9.00 (do 12.00)", "od 12.00 do 24.00", "14.00–17.00", "(10.00-12.15)"; never "16.5.2026".
  private val timeRange: Regex =
    """(?iu)(\d{1,2})[:.](\d{2})(?!\d|\.\d)(?:\s*(?:uri?|h)?[\s(]*(?:–|—|-|do|in)\s*(\d{1,2})[:.](\d{2})(?!\d|\.\d))?""".r
  // "ob 12. uri", "ob 8 h": a full hour.
  private val hourOnly: Regex  = """(?iu)\bob\s+(\d{1,2})\.?\s*(?:uri|h)\b""".r

  /** The post's text as lines, split where the HTML breaks lines or paragraphs. */
  def linesOf(html: String): List[String] =
    html
      .replaceAll("""(?i)<br\s*/?>|</(?:p|li|h\d|div)>""", "\n")
      .split('\n')
      .iterator
      .map(chunk => Jsoup.parse(chunk).text.replace(' ', ' ').replaceAll("""\s+""", " ").trim)
      .filter(_.nonEmpty)
      .toList

  private def clock(h: String, m: String): Option[LocalTime] =
    (h.toIntOption, m.toIntOption) match
      case (Some(24), Some(0))                        => Some(LocalTime.of(23, 59))
      case (Some(hh), Some(mm)) if hh < 24 && mm < 60 => Some(LocalTime.of(hh, mm))
      case _                                          => None

  final private case class Found(date: LocalDate, start: Int, end: Int)

  /** Dates in `text`, in order. A missing year is the one written later in the text, else the publish year; a date that
    * would be two months before publishing is taken to be in the next year (posts announce, they don't recap — one post
    * says "3. februar 2025" for February 2026).
    */
  private def datesIn(text: String, published: LocalDate): List[Found] =
    date
      .findAllMatchIn(text)
      .flatMap { m =>
        val month   = Option(m.group(2))
          .flatMap(_.toIntOption)
          .orElse(Option(m.group(3)).flatMap(n => months.get(n.toLowerCase.take(3))))
        val yearNum = Option(m.group(4))
          .flatMap(_.toIntOption)
          .orElse(year.findFirstMatchIn(text.substring(m.end)).map(_.group(1).toInt))
          .getOrElse(published.getYear)
        for
          mm   <- month
          d    <- Try(LocalDate.of(yearNum, mm, m.group(1).toInt)).toOption
          fixed = if d.isBefore(published.minusDays(60)) then d.plusYears(1) else d
        yield Found(fixed, m.start, m.end)
      }
      .toList

  /** The start and end time after the first date: between it and a second date, else anywhere after it. */
  private def timesIn(window: String, found: List[Found]): (Option[LocalTime], Option[LocalTime]) =
    found.headOption.fold((None, None)) { first =>
      val segment = window.substring(first.end, found.lift(1).map(_.start).getOrElse(window.length))
      val rest    = window.substring(first.end)
      timeRange.findFirstMatchIn(segment).orElse(timeRange.findFirstMatchIn(rest)) match
        case Some(m) =>
          val start = clock(m.group(1), m.group(2))
          start -> Option(m.group(3)).flatMap(h => clock(h, m.group(4))).filter(_ => start.isDefined)
        case None    =>
          hourOnly
            .findFirstMatchIn(segment)
            .orElse(hourOnly.findFirstMatchIn(rest))
            .flatMap(m => clock(m.group(1), "0")) -> None
    }

  private def detailsIn(window: String, published: LocalDate, lines: IndexedSeq[String]): Option[Details] =
    val found = datesIn(window, published)
    found.headOption.map { first =>
      val endDate      = found.drop(1).map(_.date).find(d => d.isAfter(first.date) && !d.isAfter(first.date.plusDays(14)))
      val (start, end) = timesIn(window, found)
      Details(first.date, start, end, endDate, locationIn(lines))
    }

  /** When and where, from the lines of a post; `None` when it names no date.
    *
    * The first marked line ("KDAJ:", "📅", …) that names a date wins. With `sentences`, a post without one is read from
    * its first sentence that names both a date and a time and isn't about registering.
    */
  def details(lines: List[String], published: LocalDate, sentences: Boolean = false): Option[Details] =
    val indexed = lines.toIndexedSeq

    val marked = indexed.indices.iterator
      .flatMap(at =>
        whenMarker.findFirstMatchIn(indexed(at)).map { marker =>
          val raw = (indexed(at).substring(marker.end) +: indexed.slice(at + 1, at + 3)).mkString(" ").trim
          stopMarker.findFirstMatchIn(raw).filter(_.start > 0).fold(raw)(m => raw.substring(0, m.start))
        }
      )
      .flatMap(detailsIn(_, published, indexed))
      .nextOption()

    def inSentence = indexed.iterator
      .filter(line => deadline.findFirstIn(line).isEmpty)
      .flatMap(line => detailsIn(line, published, indexed).filter(_.startTime.isDefined))
      .nextOption()

    marked.orElse(if sentences then inSentence else None)

  // The place after "KJE:"/"Lokacija:"/📍 (without a "Kraj:" label after the pin), or on the next line when the marker
  // stands alone. Per-day directions ("Petek, 16. oktober 2026 – zbirno mesto …") are not a place.
  private def locationIn(lines: IndexedSeq[String]): Option[String] =
    lines.indices.iterator
      .flatMap(i =>
        whereMarker.findFirstMatchIn(lines(i)).map { m =>
          val sameLine = cut(whereLabel.replaceFirstIn(lines(i).substring(m.end), ""))
          if sameLine.nonEmpty then sameLine
          else lines.lift(i + 1).map(l => cut(whereLabel.replaceFirstIn(l, ""))).getOrElse("")
        }
      )
      .find(place => place.nonEmpty && whenMarker.findFirstIn(place).isEmpty && !startsWithDate(place))
      .map(_.stripSuffix(".").trim)
      .map(_.take(200))

  private def cut(text: String): String =
    stopMarker.findFirstMatchIn(text).filter(_.start > 0).fold(text)(m => text.substring(0, m.start)).trim

  private val weekdayThenDate: Regex                = """(?iu)^(?:[\p{L}]+,\s*)?\d{1,2}\.\s*""".r
  private def startsWithDate(text: String): Boolean = weekdayThenDate.findFirstIn(text).isDefined

  private val filler = Set("in", "na", "za", "pri", "v", "z", "s", "o", "od", "do", "the", "and", "of", "for", "to")

  private def words(title: String): Set[String] =
    Normalizer
      .normalize(title.toLowerCase, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .split("[^a-z0-9]+")
      .filter(w => w.length >= 2 && !filler.contains(w))
      .toSet

  /** Two titles of the same event: at least half the words of the shorter one are in the other, e.g. "SPREMEMBA DATUMA
    * DOGODKA: Zakaj je kmetijstvo tudi podjetništvo?" and "ZAKAJ JE KMETIJSTVO TUDI PODJETNIŠTVO?".
    */
  def similarTitles(a: String, b: String): Boolean =
    val (x, y) = (words(a), words(b))
    val shared = (x intersect y).size
    // Two shared words at least (one-word titles: that word): "Pitch trening" is not "Kako pripraviti dober pitch".
    shared >= math.min(2, math.min(x.size, y.size)) && shared > 0 && shared * 2 >= math.min(x.size, y.size)
