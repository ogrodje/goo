import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
import sbtassembly.AssemblyKeys.assembly
import sbtassembly.{MergeStrategy, PathList}
val scala3Version = "3.7.4"

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
      // "-release", "25",
      "-Yretain-trees",
      "-Xmax-inlines:100",
      "-Ximplicit-search-limit:150000",
      "-language:implicitConversions",
      "-Wunused:all"
    )
  )
  .settings(javaAgents += "io.sentry" % "sentry-opentelemetry-agent" % Versions.sentryAgent)
  .settings(
    // assembly / mainClass             := Some("si.ogrodje.goo.apps.Main"),
    Compile / run / mainClass        := Some("si.ogrodje.goo.cli.Main"),
    Compile / mainClass              := Some("si.ogrodje.goo.cli.Main"),
    assembly / mainClass             := Some("si.ogrodje.goo.cli.Main"),
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
    dockerBaseImage          := "azul/zulu-openjdk:25-jre-headless-latest",
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
            s"""apt-get update -yyqq && apt-get install -yyqq ${ubuntuPackages.mkString(" ")}"""
          ),
          cmd
        )
      case other                   => List(other)
    }
  )

lazy val ubuntuPackages = Seq(
  "libasound2",
  "libatk-bridge2.0-0",
  "libatk1.0-0",
  "libatspi2.0-0",
  "libcairo-gobject2",
  "libcairo2",
  "libcups2",
  "libdbus-1-3",
  "libgbm1",
  "libgdk-pixbuf-2.0-0",
  "libglib2.0-0",
  "libgtk-3-0",
  "libgtk-3-0",
  "libgtk-4-bin",
  "libgtk-4-common",
  "libgtk-4-dev",
  "libnspr4",
  "libnss3",
  "libpango-1.0-0",
  "libpangocairo-1.0-0",
  "libx11-6",
  "libx11-xcb1",
  "libxcb1",
  "libxcomposite1",
  "libxcursor1",
  "libxdamage1",
  "libxext6",
  "libxfixes3",
  "libxkbcommon0",
  "libxrandr2",
  // added
  "libgstreamer1.0-0",
  "libgstreamer-plugins-base1.0-0",
  "libgstreamer-plugins-good1.0-0",
  "libgstreamer-plugins-bad1.0-0",
  "libgstreamer-gl1.0-0",
  "libxslt1.1",
  "libvpx7",
  "libevent-2.1-7",
  "libflite1",
  "libavif13",
  "libnghttp2-14",
  "libx264-163",
  "libopus0",
  "libopusfile0",
  "libsecret-1-0",
  "libhyphen0",
  "libmanette-0.2-0",
  "libwebpdemux2",
  "libwebpmux3",
  "libenchant-2-2",
  "libpsl5",
  "libwoff1"
).distinct.sorted

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
addCommandAlias("fix", ";scalafixAll")
