import com.typesafe.sbt.SbtNativePackager.autoImport._
import com.typesafe.sbt.packager.docker.Cmd
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import Dependencies.*
import Setting.*
import NativePackagerHelper._
val scala3Version = "3.6.4"

lazy val root = project
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin)
  .in(file("."))
  .settings(
    name         := "goo",
    version      := "0.0.1",
    scalaVersion := scala3Version,
    libraryDependencies ++= zio ++ logging ++ db,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    scalacOptions ++= Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-Yretain-trees",
      "-Xmax-inlines:100",
      "-language:implicitConversions"
    )
  )
  .settings(Setting.assemblySetting: _*)
  .settings(Setting.dockerSettings: _*)
