import Dependencies.*
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
import sbtassembly.AssemblyKeys.assembly
import sbtassembly.{MergeStrategy, PathList}
val scala3Version = "3.6.4"

ThisBuild / dynverVTagPrefix := false
ThisBuild / dynverSeparator  := "-"
ThisBuild / scalaVersion     := scala3Version

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
      zio ++ logging ++ db ++ scheduler ++
        json ++ jsoup ++ ical4j ++ enumeratum ++ playwright
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
      "-language:implicitConversions"
    )
  )
  .settings(
    javaAgents += "io.sentry" % "sentry-opentelemetry-agent" % Versions.sentryAgent
  )
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
    // dockerAlias              := dockerAlias.value.withTag(Some(version.value + "-lzo")),
    dockerExposedPorts ++= Seq(7778),
    dockerExposedUdpPorts    := Seq.empty[Int],
    dockerUsername           := Some("ogrodje"),
    dockerUpdateLatest       := true,
    dockerRepository         := Some("ghcr.io"),
    // dockerBaseImage          := "gcr.io/distroless/java21-debian12",
    // dockerBaseImage          := "azul/zulu-openjdk-alpine:21-latest",
    dockerBaseImage          := "azul/zulu-openjdk:21-jre-headless-latest",
    Docker / daemonUserUid   := None,
    Docker / daemonUser      := "root",
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    packageName              := "goo",
    dockerCommands           := dockerCommands.value.flatMap {
      case cmd @ Cmd("WORKDIR", _) =>
        List(
          Cmd("LABEL", "maintainer=\"Oto Brglez <otobrglez@gmail.com>\""),
          Cmd(
            "LABEL",
            "org.opencontainers.image.url=https://github.com/ogrodje/goo"
          ),
          Cmd(
            "LABEL",
            "org.opencontainers.image.source=https://github.com/ogrodje/goo"
          ),
          Cmd("ENV", "PORT=7777"),
          Cmd("ENV", s"GOO_VERSION=${version.value}"),
          Cmd(
            "RUN",
            """apt-get update -yyq && apt-get install libglib2.0-0\
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
              | libgdk-pixbuf-2.0-0 \
              | -yyq""".stripMargin
          ),
          cmd
        )
      case other                   => List(other)
    }
    /*
    dockerEntrypoint         := Seq(
      "java",
      "-XX:+AlwaysPreTouch",
      "-Dfile.encoding=UTF-8",
      "-jar",
      s"/opt/docker/lib/${(packageJavaLauncherJar / artifactPath).value.getName}"
    ) */
  )

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt")
