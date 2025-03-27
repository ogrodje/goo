package si.ogrodje.goo.models

import enumeratum.*

sealed trait Source extends EnumEntry

object Source extends Enum[Source] with CirceEnum[Source]:
  case object Meetup                  extends Source
  case object Eventbrite              extends Source
  case object TehnoloskiParkLjubljana extends Source

  val values = findValues
