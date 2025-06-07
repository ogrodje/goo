package si.ogrodje.goo.graphql

import caliban.*
import caliban.quick.*
import caliban.schema.Schema.auto.*
import caliban.schema.ArgBuilder.auto.*
import caliban.schema.SchemaDerivation
import zio.*

import java.time.OffsetDateTime
import zio.http.URL
import caliban.Value
import si.ogrodje.goo.models.{Event, EventID}
import caliban.schema.Schema

object GraphQLAPI:
  private given lowURLSchema: Schema[Any, zio.http.URL] =
    Schema.stringSchema.contramap[zio.http.URL](p => p.toString)
  private given eventSchema: Schema[Any, Event]         = Schema.gen

  object customSchema extends SchemaDerivation[Any]

  private final case class Queries(
    events: Task[List[Event]]
    // event: EventID => UIO[Option[Event]]
  ) derives customSchema.SemiAuto
  // object Queries:
  //  given schema: Schema[Queries] = DeriveSchema.gen

  private final case class Mutations(
    hideEvent: EventID => UIO[Boolean],
    promoteEvent: EventID => UIO[Boolean]
  )

  case class EventArgs(id: EventID)

  private val queries = Queries(
    events = ZIO.succeed(List.empty[Event])
    // event = id => ZIO.succeed(None)
  )

  private val mutations = Mutations(
    hideEvent = id => ZIO.succeed(true),
    promoteEvent = id => ZIO.succeed(true)
  )

  // implicit val queriesSchema: Schema[Queries] = Schema.gen
  val api: GraphQL[Any] = graphQL(RootResolver(queries /*, mutations */ ))
