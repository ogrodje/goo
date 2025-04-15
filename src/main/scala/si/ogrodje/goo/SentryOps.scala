package si.ogrodje.goo

import io.sentry.{Sentry, SentryOptions}
import zio.ZIO

object SentryOps:

  def setup: ZIO[Any, Throwable, Unit] = for
    environment  <- AppConfig.environment
    sentryOptions =
      val options = new SentryOptions()
      options.setEnableExternalConfiguration(true)
      options.setEnvironment(environment.entryName.toLowerCase)
      options.setRelease(si.ogrodje.goo.info.BuildInfo.version)
      options

    _ <- ZIO.attempt(Sentry.init(sentryOptions))
  yield ()
