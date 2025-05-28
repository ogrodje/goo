package si.ogrodje.goo.browser

import com.microsoft.playwright.*
import zio.ZIO.{attemptBlocking, fromAutoCloseable, logInfo}
import zio.{Scope, ZIO, ZLayer}

object PWright:
  def livePlaywright: ZLayer[Scope, Throwable, Playwright] =
    ZLayer.fromZIO(
      fromAutoCloseable(ZIO.attemptBlocking(Playwright.create())).zipLeft(logInfo(s"Playwright started."))
    )

  def liveBrowser: ZLayer[Scope & Playwright, Throwable, Browser] = ZLayer.fromZIO:
    for
      playwright <- ZIO.service[Playwright]
      browser    <-
        fromAutoCloseable(
          attemptBlocking(
            playwright.chromium.launch(
              new BrowserType.LaunchOptions()
                .setHeadless(true)
                .setSlowMo(50)
            )
          )
        ).tap(browser => logInfo(s"Playwright ${browser.version()} started."))
    yield browser
