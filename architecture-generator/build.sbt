// See README.md for license details.

ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0"
ThisBuild / organization     := "%ORGANIZATION%"

val chiselVersion = "6.0.0"
val chiseltestVersion = "6.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "hardcilk",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % chiselVersion,
      "edu.berkeley.cs" %% "chiseltest" % chiseltestVersion,
      "com.typesafe.play" %% "play-json" % "2.9.2",
      "jnbrq" %% "strenc-scala" % "0.1.0",
      "jnbrq" %% "chisel3-interface" % "0.1.0",
      "epfl-lap" %% "chext" % "0.1.0"
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-P:chiselplugin:genBundleElements",
    ),
    addCompilerPlugin(
      "org.chipsalliance" % "chisel-plugin" % chiselVersion cross CrossVersion.full
    ),
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers ++= Resolver.sonatypeOssRepos("releases")
  )
