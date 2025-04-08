package si.ogrodje.goo.browser

import zio.{Scope, ZIO, ZLayer}
import com.microsoft.playwright.*
import zio.ZIO.logInfo

object PWright:
  def livePlaywright: ZLayer[Scope, Throwable, Playwright] =
    ZLayer.fromZIO(
      ZIO
        .fromAutoCloseable(
          ZIO.attemptBlocking(
            Playwright.create()
          )
        )
        .tap(pw => logInfo("Playwright started"))
    )

  def liveBrowser: ZLayer[Scope & Playwright, Throwable, Browser] = ZLayer.fromZIO:
    for
      playwright <- ZIO.service[Playwright]
      browser    <-
        ZIO
          .fromAutoCloseable(
            ZIO.attemptBlocking(
              playwright.chromium.launch(
                new BrowserType.LaunchOptions()
                  .setHeadless(true)
                  .setSlowMo(50)
              )
            )
          )
          .tap(browser => logInfo("Browser started"))
    yield browser
