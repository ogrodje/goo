package si.ogrodje.goo.models

import enumeratum.*

sealed trait Source extends EnumEntry

object Source extends Enum[Source] with CirceEnum[Source]:
  case object Meetup                   extends Source
  case object Eventbrite               extends Source
  case object TehnoloskiParkLjubljana  extends Source
  case object PrimorskiTehnoloskiPark  extends Source
  case object GZS                      extends Source
  case object StartupSi                extends Source
  case object ICal                     extends Source
  case object Manual                   extends Source
  case object FERI                     extends Source
  case object FRI                      extends Source
  case object KCUI                     extends Source
  case object InkubatorSavinjskeRegije extends Source
  case object RacunalniskiMuzej        extends Source
  case object LUI                      extends Source
  case object SpletneUrice             extends Source
  case object SLAIF                    extends Source

  val values: IndexedSeq[Source] = findValues
