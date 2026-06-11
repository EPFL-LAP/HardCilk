// See README.md for license details.

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "epfl-lap"

val chiselVersion = "6.0.0"
val chiseltestVersion = "6.0.0"
val circeVersion = "0.14.2"

lazy val root = (project in file("."))
  .settings(
    name := "hardcilk",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % chiseltestVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "com.typesafe.play" %% "play-json" % "2.9.2",
      "hdlstuff" %% "chext" % "0.1.1",
      "hdlstuff" %% "hdlinfo" % "0.1.0",
      "com.github.scopt" %% "scopt" % "4.1.0"
    ) ++ Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser",
      "io.circe" %% "circe-generic-extras" 
    ).map(_ % circeVersion),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-P:chiselplugin:genBundleElements",
      "-Ymacro-annotations",
      "-Xlint"
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    dependencyOverrides +=
  "edu.berkeley.cs" %% "chiseltest" % "6.0.0",

    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers ++= Resolver.sonatypeOssRepos("releases"),

    // chiseltest's Verilator backend shells out to `verilator`, which inherits
    // this JVM's environment -- so the test JVM must fork for us to point it at
    // a known-good Verilator. The HDLStuff verilator on PATH reports a broken
    // version string ("rev UNKNOWN.REV") and ships a misconfigured verilated.mk,
    // both of which break chiseltest; this locally-built tree is clean.
    // Override with HARDCILK_VERILATOR_ROOT if it lives elsewhere.
    Test / fork := true,
    Test / parallelExecution := true,
    Test / testForkedParallel := true,
    concurrentRestrictions := Seq(
      Tags.limit(Tags.ForkedTestGroup, 28)
    ),
    Test / javaOptions ++= Seq(
      "-Xmx8g",
      "-Xms1g",
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=200"
    ),
    Test / envVars ++= {
      val verilatorRoot =
        sys.env
          .getOrElse("HARDCILK_VERILATOR_ROOT", "/beta/bradley/verilator")
      Map(
        "VERILATOR_ROOT" -> verilatorRoot,
        "PATH" -> (verilatorRoot + "/bin:" + sys.env.getOrElse(
          "PATH",
          "/usr/bin:/bin"
        ))
      )
    }
  )
