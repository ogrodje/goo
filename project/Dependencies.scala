import sbt.*

object Dependencies {
  type Version = String
  type Modules = Seq[ModuleID]

  object Versions {
    val log4cats: Version      = "2.7.0"
    val scalaTest: Version     = "3.2.19"
    val doobie: Version        = "1.0.0-RC8"
    val sentry: Version        = "8.8.0"
    val sentryLogback: Version = sentry
    val sentryAgent: Version   = sentry
    val ical4j: Version        = "4.1.1"
    val quartz: Version        = "2.5.0"
    val zio: Version           = "2.1.17"
    val zioLogging: Version    = "2.5.0"
    val zioHttp: Version       = "3.2.0"
    val zioConfig: Version     = "4.0.4"
    val zioMetrics: Version    = "2.3.1"
    val postgresql: Version    = "42.7.5"
    val flyway: Version        = "11.7.2"
    val circe: Version         = "0.14.12"
  }

  lazy val zio: Modules = Seq(
    "dev.zio" %% "zio",
    "dev.zio" %% "zio-streams"
  ).map(_ % Versions.zio) ++ Seq(
    "dev.zio" %% "zio-test",
    "dev.zio" %% "zio-test-sbt",
    "dev.zio" %% "zio-test-magnolia"
  ).map(_ % Versions.zio % Test) ++ Seq(
    "dev.zio" %% "zio-prelude" % "1.0.0-RC39",
    "dev.zio" %% "zio-cli"     % "0.7.1"
  ) ++ Seq(
    "dev.zio" %% "zio-logging",
    "dev.zio" %% "zio-logging-slf4j2"
  ).map(_ % Versions.zioLogging) ++ Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.18"
  ) ++ Seq(
    "dev.zio" %% "zio-schema",
    "dev.zio" %% "zio-schema-json",
    "dev.zio" %% "zio-schema-zio-test",
    "dev.zio" %% "zio-schema-derivation"
    // "org.scala-lang" % "scala-reflect"  % scalaVersion.value % "provided" // Needed ?
  ).map(_ % "1.6.6") ++ Seq(
    "dev.zio" %% "zio-metrics-connectors",
    "dev.zio" %% "zio-metrics-connectors-prometheus"
  ).map(_ % Versions.zioMetrics) ++ Seq(
    "dev.zio" %% "zio-json-yaml" % "0.7.42"
  ) ++ Seq(
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

  lazy val logging: Modules = Seq(
    "ch.qos.logback" % "logback-classic" % "1.5.18"
  ) ++ Seq(
    "org.typelevel" %% "log4cats-core",
    "org.typelevel" %% "log4cats-slf4j"
  ).map(_ % Versions.log4cats) ++ Seq(
    "io.sentry" % "sentry-logback" % Versions.sentryLogback
  )

  lazy val db: Modules = Seq(
    "org.tpolecat" %% "doobie-core",
    "org.tpolecat" %% "doobie-hikari",  // HikariCP transactor.
    "org.tpolecat" %% "doobie-postgres" // Postgres driver
    // "org.tpolecat" %% "doobie-postgres-circe"
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
  ).map(_ % "1.7.5")

  lazy val jsoup: Modules = Seq(
    "org.jsoup" % "jsoup" % "1.19.1"
  )

  lazy val ical4j: Modules = Seq(
    "org.mnode.ical4j" % "ical4j" % Versions.ical4j
  )

  lazy val playwright: Modules = Seq(
    "com.microsoft.playwright" % "playwright" % "1.51.0"
  )

  lazy val json: Modules = Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser"
  ).map(_ % Versions.circe)

  lazy val projectResolvers: Seq[MavenRepository] = Seq(
    // Resolver.sonatypeOssRepos("snapshots"),
    "Sonatype releases" at "https://oss.sonatype.org/content/repositories/releases",
    "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    "Sonatype staging" at "https://oss.sonatype.org/content/repositories/staging",
    "Java.net Maven2 Repository" at "https://download.java.net/maven/2/"
  )
}
