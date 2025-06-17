import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
import sbtassembly.AssemblyKeys.assembly
import sbtassembly.{MergeStrategy, PathList}
val scala3Version = "3.7.1"

ThisBuild / dynverVTagPrefix  := false
ThisBuild / dynverSeparator   := "-"
ThisBuild / scalaVersion      := scala3Version
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / resolvers ++= Dependencies.projectResolvers

lazy val root = project
  .enablePlugins(BuildInfoPlugin, JavaAgent, JavaAppPackaging, LauncherJarPlugin, DockerPlugin)
  .in(file("."))
  .settings(
    buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "si.ogrodje.goo.info"
  )
  .settings(
    name         := "goo",
    scalaVersion := scala3Version,
    libraryDependencies ++= {
      zio ++ db ++ scheduler ++
        json ++ jwt ++ jsoup ++ ical4j ++ enumeratum ++ playwright
    },
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-explain",
      "-Yretain-trees",
      "-Xmax-inlines:100",
      "-Ximplicit-search-limit:150000",
      "-language:implicitConversions",
      "-Wunused:all"
    )
  )
  .settings(javaAgents += "io.sentry" % "sentry-opentelemetry-agent" % Versions.sentryAgent)
  .settings(
    assembly / mainClass             := Some("si.ogrodje.goo.apps.Main"),
    assembly / assemblyJarName       := "goo.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class")                        =>
        MergeStrategy.discard
      case PathList("META-INF", "jpms.args")                    =>
        MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") =>
        MergeStrategy.first
      case PathList("deriving.conf")                            =>
        MergeStrategy.last
      case PathList(ps @ _*) if ps.last endsWith ".class"       => MergeStrategy.last
      case x                                                    =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )
  .settings(
    dockerExposedPorts ++= Seq(7778),
    dockerExposedUdpPorts    := Seq.empty[Int],
    dockerUsername           := Some("ogrodje"),
    dockerUpdateLatest       := true,
    dockerRepository         := Some("ghcr.io"),
    dockerBaseImage          := "azul/zulu-openjdk:21-jre-headless-latest",
    Docker / daemonUserUid   := None,
    Docker / daemonUser      := "root",
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    packageName              := "goo",
    dockerCommands           := dockerCommands.value.flatMap {
      case cmd @ Cmd("WORKDIR", _) =>
        List(
          Cmd("LABEL", "maintainer=\"Oto Brglez <otobrglez@gmail.com>\""),
          Cmd("LABEL", "org.opencontainers.image.url=https://github.com/ogrodje/goo"),
          Cmd("LABEL", "org.opencontainers.image.source=https://github.com/ogrodje/goo"),
          Cmd("ENV", "PORT=7777"),
          Cmd("ENV", s"GOO_VERSION=${version.value}"),
          Cmd(
            "RUN",
            """apt-get update -yyqq && apt-get install -yyqq libglib2.0-0\
              |	libnss3\
              |	libnspr4\
              |	libdbus-1-3\
              |	libatk1.0-0\
              |	libatspi2.0-0\
              |	libx11-6\
              |	libxcomposite1\
              |	libxdamage1\
              |	libxext6\
              |	libxfixes3\
              |	libxrandr2\
              |	libgbm1\
              |	libxcb1\
              |	libxkbcommon0\
              |	libasound2\
              | libatk-bridge2.0-0\
              | libcups2\
              | libpango-1.0-0\
              | libcairo2\
              | libx11-xcb1\
              | libxcursor1\
              | libgtk-3-0\
              | libpangocairo-1.0-0\
              | libcairo-gobject2\
              | libgdk-pixbuf-2.0-0""".stripMargin
          ),
          cmd
        )
      case other                   => List(other)
    }
  )

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
addCommandAlias("fix", ";scalafixAll")
