package si.ogrodje.goo

import si.ogrodje.goo.models.Event

import java.time.{LocalDateTime, OffsetDateTime}
import scala.Console.*

object EventOps:
  extension (offsetDateTime: OffsetDateTime) def local: LocalDateTime = offsetDateTime.toLocalDateTime

  extension (event: Event)
    def prettyPrint = println {
      s"${event.title} \nStart: ${RED}${event.startDateTime.local} (${event.hasStartTime})${RESET}, " +
        s"End: ${BLUE}${event.endDateTime.map(_.local)} (${event.hasEndTime})}${RESET}\n"
    }
