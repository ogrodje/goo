package si.ogrodje.goo.parsers

import si.ogrodje.goo.models.Meetup

import java.time.LocalDate

/** Events of Ljubljanski univerzitetni inkubator (https://lui.si/aktualno/): posts in the "dogodki" category of its
  * WordPress site, read by [[WordPressEventsParser]]; when and where come from the posts' text ([[EventText]]).
  */
object LUIParser:
  def apply(meetup: Meetup): WordPressEventsParser = WordPressEventsParser(meetup, WordPressEventsParser.LUI)

  export EventText.{linesOf, Details}

  def details(lines: List[String], published: LocalDate): Option[Details] = EventText.details(lines, published)
