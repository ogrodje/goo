import Dependencies.*
val scala3Version = "3.6.4"

ThisBuild / dynverVTagPrefix := false
ThisBuild / dynverSeparator  := "-"

lazy val root = project
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin)
  .in(file("."))
  .settings(
    version      := "0.0.1",
    name         := "goo",
    scalaVersion := scala3Version,
    libraryDependencies ++= {
      zio ++ logging ++ db ++ scheduler ++
        json ++ jsoup ++ ical4j ++ enumeratum
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
  .settings(Settings.assemblySetting: _*)
  .settings(Settings.dockerSettings: _*)
  .settings(
    assembly / assemblyJarName := "goo.jar",
    dockerEntrypoint           := Seq(
      "java",
      "-XX:+AlwaysPreTouch",
      "-Dfile.encoding=UTF-8",
      "-jar",
      s"/opt/docker/lib/${(packageJavaLauncherJar / artifactPath).value.getName}"
    )
  )
