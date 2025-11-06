package si.ogrodje.goo.clients.discord

import zio.*
import zio.http.*
import zio.test.*

object DiscordClientTest extends ZIOSpecDefault:
  val runThis = false // When developing turn this true

  def spec = suite("DiscordWebhookClientTest") {
    test("emit something") {
      for _ <- DiscordClient.emit(
                 Payload(
                   "This test has passed.",
                   Some("GOO"),
                   embeds = List(
                     Embed(
                       "Dogodek A",
                       Some("Dogodek A"),
                       Some("https://www.dogodek.si/"),
                       fields = List(
                         Field("Pričetek", "10:00"),
                         Field("Konec", "15:00")
                       )
                     )
                   )
                 )
               )
      yield assertCompletes
    }
  }.when(runThis)
    .provide(
      Client.default,
      ZLayer.fromZIO(discordWebhookURLFromENV).flatMap(urlEnv => DiscordClient.live(urlEnv.get))
    ) @@ TestAspect.withLiveSystem @@ TestAspect.withLiveClock

  private def discordWebhookURLFromENV: Task[URL] = for
    maybeValue <- System.env("DISCORD_EVENTS_WEBHOOK")
    value      <- ZIO.getOrFail(maybeValue)
    url        <- ZIO.fromEither(URL.decode(value))
  yield url
