package si.ogrodje.goo.models

import scala.annotation.nowarn

enum UpsertResult(
  eventID: EventID
):
  case Inserted(eventID: EventID) extends UpsertResult(eventID)
  case Updated(eventID: EventID)  extends UpsertResult(eventID)
