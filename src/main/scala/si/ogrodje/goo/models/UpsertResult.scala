package si.ogrodje.goo.models

enum UpsertResult(eventID: EventID):
  case Inserted(eventID: EventID) extends UpsertResult(eventID)
  case Updated(eventID: EventID)  extends UpsertResult(eventID)
