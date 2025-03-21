// SBT
import sbt.*
import Keys.*
// import sbtbuildinfo.BuildInfoPlugin.autoImport._

// sbt-assembly
import sbtassembly.*
import sbtassembly.AssemblyKeys.*

// sbt-native-packager
import com.typesafe.sbt.SbtNativePackager.autoImport.*
import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin.autoImport.packageJavaLauncherJar
import com.typesafe.sbt.packager.docker.*
import com.typesafe.sbt.packager.Keys.{daemonUser, maintainer}
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.*
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.*
import com.typesafe.sbt.packager.docker.DockerVersion

object Settings {
  val assemblySetting = Seq[Def.SettingsDefinition](
    assembly / mainClass             := Some("si.ogrodje.ogg.apps.Main"),
    assembly / assemblyJarName       := "goo.jar",
    // assembly / assemblyJarName       := { s"${name.value}-${version.value}.jar" },
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class")                        => MergeStrategy.discard
      case PathList("META-INF", "jpms.args")                    => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties") =>
        MergeStrategy.first
      case PathList("deriving.conf")                            => MergeStrategy.last
      case PathList(ps @ _*) if ps.last endsWith ".class"       => MergeStrategy.last
      case x                                                    =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )

  val dockerSettings = Seq[Def.SettingsDefinition](
    dockerAlias              := dockerAlias.value.withTag(Some(version.value + "-lzo")),
    dockerExposedPorts ++= Seq(7778),
    dockerExposedUdpPorts    := Seq.empty[Int],
    dockerUsername           := Some("ogrodje"),
    dockerUpdateLatest       := true,
    dockerRepository         := Some("ghcr.io"),
    dockerBaseImage          := "gcr.io/distroless/java21-debian12",
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
          cmd
        )
      case other                   => List(other)
    }

    /*
    Docker / dockerPackageMappings += (
      baseDirectory.value / "players.yml"
    )                     -> "/opt/docker/players.yml",
     */

    /*
    dockerCommands := dockerCommands.value.flatMap {
      case add @ Cmd("RUN", args @ _*) if args.contains("id") =>
        List(
          Cmd("LABEL", "maintainer Oto Brglez <otobrglez@gmail.com>"),
          Cmd(
            "LABEL",
            "org.opencontainers.image.url https://github.com/pinkstack/fuzzija"
          ),
          Cmd(
            "LABEL",
            "org.opencontainers.image.source https://github.com/pinkstack/fuzzija"
          ),
          // Cmd("RUN", "apk add --no-cache bash"),
          Cmd("ENV", "SBT_VERSION", sbtVersion.value),
          Cmd("ENV", "SCALA_VERSION", scalaVersion.value),
          Cmd("ENV", "GOO_VERSION", version.value),
          add
        )
      case other                                              => List(other)
    } */
    /*
    dockerEntrypoint := Seq(
      "java",
      "-XX:+AlwaysPreTouch",
      "-Dfile.encoding=UTF-8",
      "-jar",
      s"/opt/docker/lib/${(Compile / packageBin / artifactPath).value.getName.replace(".jar", ".pom")}"
    ), */
    // dockerCmd        := Seq.empty
  )
}
