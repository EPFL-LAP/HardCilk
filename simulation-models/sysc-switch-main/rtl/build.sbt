// See README.md for license details.

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "jnbrq"

val chiselVersion = "6.0.0"
val chiseltestVersion = "6.0-SNAPSHOT"
val circeVersion = "0.14.1"

lazy val root = (project in file("."))
  .settings(
    name := "sysc_switch",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "hdlstuff" %% "chext" % "0.1.1",
      "hdlstuff" %% "hdlinfo" % "0.1.0",
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "edu.berkeley.cs" %% "chiseltest" % chiseltestVersion
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations"
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    resolvers ++= Resolver.sonatypeOssRepos("snapshots")
  )
