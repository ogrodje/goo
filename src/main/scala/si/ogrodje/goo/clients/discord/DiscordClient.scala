package si.ogrodje.goo.clients.discord

import io.circe.*
import io.circe.syntax.*
import io.circe.generic.semiauto.deriveEncoder
import zio.*
import zio.http.*
import zio.http.Header.{Accept, ContentType}

final case class Field(
  name: String,
  value: String,
  inline: Boolean = false
)
object Field:
  given encoder: Encoder[Field] = deriveEncoder

final case class Embed(
  title: String,
  description: Option[String] = None,
  url: Option[String] = None,
  color: Option[Int] = None,
  fields: List[Field] = List.empty
)
object Embed:
  given encoder: Encoder[Embed] = deriveEncoder

final case class Payload(
  content: String,
  username: Option[String],
  embeds: List[Embed] = List.empty,
  tts: Boolean = false
)
object Payload:
  given encoder: Encoder[Payload] = deriveEncoder

final case class DiscordClient(
  url: URL
):

  def emit(payload: Payload): RIO[Client, Unit] = for
    _        <- ZIO.unit
    headers   = Headers(Accept(MediaType.application.json), ContentType(MediaType.application.`json`))
    request   = Request.post(url, body = Body.fromString(payload.asJson.noSpaces)).setHeaders(headers)
    response <- Client.batched(request)
    _        <- ZIO.fail(
                  new Exception(s"Webhook failed with status: \"${response.status}\"")
                ) when (!response.status.isSuccess)
  yield ()

object DiscordClient:
  def emit(payload: Payload): RIO[Client & DiscordClient, Unit] =
    ZIO.serviceWithZIO[DiscordClient](_.emit(payload))
  def live(url: URL): ULayer[DiscordClient]                     =
    ZLayer.succeed(DiscordClient(url))
