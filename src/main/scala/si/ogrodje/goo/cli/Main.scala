package si.ogrodje.goo.cli

import si.ogrodje.goo.apps.Main as MainServer
import si.ogrodje.goo.info.BuildInfo
import si.ogrodje.goo.models.EventView
import si.ogrodje.goo.notifiers.DiscordEventsNotifier
import zio.*
import zio.Runtime.{removeDefaultLoggers, setConfigProvider}
import zio.cli.*
import zio.cli.HelpDoc.Span.text
import zio.http.URL
import zio.logging.backend.SLF4J

import java.time.LocalDate

object Main extends ZIOCliDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    setConfigProvider(ConfigProvider.envProvider) >>> removeDefaultLoggers >>> SLF4J.slf4j

  private val eventViewArgs: Args[EventView]  = Args.enumeration[EventView](EventView.values.map(v => v.entryName -> v)*)
  private val start: Options[Boolean]         = Options.boolean("start")
  private val now: Options[Option[LocalDate]] = Options.localDate("now").optional

  private val discordWebhookURL =
    Args
      .text("discord-webhook-url")
      .mapOrFail(raw => URL.decode(raw).left.map(err => HelpDoc.p(s"Failed to convert ${raw} - ${err}")))

  private val server: Command[Boolean] = Command("server", options = start)
  private val discordEventsNotifier    =
    Command("discord-events-notifier", args = eventViewArgs ++ discordWebhookURL, options = now)

  private val command = Command("goo").subcommands(server, discordEventsNotifier)

  val cliApp = CliApp.make(
    name = "Ogrodje",
    version = BuildInfo.version,
    summary = text("CLI for Ogrodje Operating System"),
    command = command
  ) {
    case start: Boolean                                        => MainServer.run.when(start)
    case (now: Option[LocalDate], (view: EventView, url: URL)) => DiscordEventsNotifier.run(url, view, now)
  }
