import sbt.*

object Dependencies {
  type Version = String
  type Modules = Seq[ModuleID]

  object Versions {
    val circe: Version          = "0.14.15"
    val doobie: Version         = "1.0.0-RC10"
    val flyway: Version         = "11.15.0"
    val ical4j: Version         = "4.2.0"
    val jsoup: Version          = "1.21.2"
    val jwtScala: Version       = "11.0.3"
    val log4cats: Version       = "2.7.1"
    val logbackClassic: Version = "1.5.20"
    val playwright: Version     = "1.56.0"
    val postgresql: Version     = "42.7.8"
    val quartz: Version         = "2.5.1"
    val scalaTest: Version      = "3.2.19"
    val sentry: Version         = "8.25.0"
    val sentryAgent: Version    = sentry
    val sentryLogback: Version  = sentry
    val zio: Version            = "2.1.22"
    val zioCli: Version         = "0.7.4"
    val zioConfig: Version      = "4.0.5"
    val zioHttp: Version        = "3.5.1"
    val zioLogging: Version     = "2.5.1"
    val zioMetrics: Version     = "2.5.2"
    val zioPrelude: Version     = "1.0.0-RC41"
    val zioSchema: Version      = "1.7.5"
  }

  lazy val zio: Modules = Seq(
    "dev.zio" %% "zio",
    "dev.zio" %% "zio-streams"
  ).map(_ % Versions.zio) ++ Seq(
    "dev.zio" %% "zio-test",
    "dev.zio" %% "zio-test-sbt",
    "dev.zio" %% "zio-test-magnolia"
  ).map(_ % Versions.zio % Test) ++ Seq(
    "dev.zio" %% "zio-cli" % Versions.zioCli
  ) ++ Seq(
    "dev.zio" %% "zio-logging",
    "dev.zio" %% "zio-logging-slf4j2"
  ).map(_ % Versions.zioLogging) ++ Seq(
    "ch.qos.logback" % "logback-classic" % Versions.logbackClassic,
    "io.sentry"      % "sentry-logback"  % Versions.sentryLogback
  ) ++ Seq(
    "dev.zio" %% "zio-schema",
    "dev.zio" %% "zio-schema-json",
    "dev.zio" %% "zio-schema-zio-test",
    "dev.zio" %% "zio-schema-derivation"
  ).map(_ % Versions.zioSchema) ++ Seq(
    "dev.zio" %% "zio-metrics-connectors",
    "dev.zio" %% "zio-metrics-connectors-prometheus"
  ).map(_ % Versions.zioMetrics) ++ Seq(
    "eu.timepit" %% "refined" % "0.11.3"
  ) ++ Seq(
    "dev.zio" %% "zio-http"         % Versions.zioHttp,
    "dev.zio" %% "zio-http-testkit" % Versions.zioHttp % Test
  ) ++ Seq(
    "dev.zio" %% "zio-config",
    "dev.zio" %% "zio-config-magnolia",
    "dev.zio" %% "zio-config-typesafe",
    "dev.zio" %% "zio-config-refined"
  ).map(_ % Versions.zioConfig)

  lazy val db: Modules = Seq(
    "org.tpolecat" %% "doobie-core",
    "org.tpolecat" %% "doobie-hikari",
    "org.tpolecat" %% "doobie-postgres"
  ).map(_ % Versions.doobie) ++ Seq(
    "org.postgresql" % "postgresql" % Versions.postgresql
  ) ++ Seq(
    "org.flywaydb" % "flyway-core",
    "org.flywaydb" % "flyway-database-postgresql"
  ).map(_ % Versions.flyway) ++ Seq(
    "dev.zio" %% "zio-interop-cats" % "23.1.0.5"
  )

  lazy val scheduler: Modules = Seq(
    "org.quartz-scheduler" % "quartz"      % Versions.quartz,
    "org.quartz-scheduler" % "quartz-jobs" % Versions.quartz
  )

  lazy val enumeratum: Modules = Seq(
    "com.beachape" %% "enumeratum",
    "com.beachape" %% "enumeratum-circe"
  ).map(_ % "1.9.0")

  lazy val jsoup: Modules  = "org.jsoup" % "jsoup" % Versions.jsoup :: Nil
  lazy val ical4j: Modules =
    "org.mnode.ical4j" % "ical4j" % Versions.ical4j :: Nil
  lazy val playwright: Modules =
    "com.microsoft.playwright" % "playwright" % Versions.playwright :: Nil

  lazy val json: Modules = Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % Versions.circe)

  lazy val jwt: Modules = Seq(
    "com.github.jwt-scala" %% "jwt-core",
    "com.github.jwt-scala" %% "jwt-circe"
  ).map(_ % Versions.jwtScala)

  lazy val projectResolvers: Seq[MavenRepository] = Seq(
    "Maven Central" at "https://repo1.maven.org/maven2/",
    "Sonatype releases" at "https://oss.sonatype.org/content/repositories/releases",
    "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype staging" at "https://oss.sonatype.org/content/repositories/staging",
    "Java.net Maven2 Repository" at "https://download.java.net/maven/2/"
  )
}
