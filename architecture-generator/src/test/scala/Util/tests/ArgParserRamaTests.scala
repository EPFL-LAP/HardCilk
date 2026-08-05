package Util.tests

import org.scalatest.flatspec.AnyFlatSpec
import Util.{ArgParser, ArchitectureMode, ArgumentServerMode}

class ArgParserRamaTests extends AnyFlatSpec {
  behavior of "architecture A/B command-line modes"

  it should "default to the updated cached profile" in {
    val cfg = ArgParser.parseArgs(Array("descriptor.json")).get
    assert(cfg.generatorProfile.architecture == ArchitectureMode.Updated)
    assert(cfg.generatorProfile.argumentServer == ArgumentServerMode.Cached)
  }

  it should "default the legacy architecture to its historical no-cache server" in {
    val cfg = ArgParser.parseArgs(
      Array("descriptor.json", "--architecture", "legacy")
    ).get
    assert(cfg.generatorProfile.architecture == ArchitectureMode.Legacy)
    assert(cfg.generatorProfile.argumentServer == ArgumentServerMode.NoCache)
  }

  it should "accept an explicit no-cache selection for legacy" in {
    val cfg = ArgParser.parseArgs(Array(
      "descriptor.json", "--architecture", "legacy",
      "--argument-server", "no-cache"
    )).get
    assert(cfg.generatorProfile.architecture == ArchitectureMode.Legacy)
    assert(cfg.generatorProfile.argumentServer == ArgumentServerMode.NoCache)
  }

  it should "select the updated no-cache profile" in {
    val cfg = ArgParser.parseArgs(
      Array("descriptor.json", "--argument-server", "no-cache")
    ).get
    assert(cfg.generatorProfile.architecture == ArchitectureMode.Updated)
    assert(cfg.generatorProfile.argumentServer == ArgumentServerMode.NoCache)
  }

  it should "reject cached argument servers on the legacy architecture" in {
    assert(ArgParser.parseArgs(Array(
      "descriptor.json", "--architecture", "legacy",
      "--argument-server", "cached"
    )).isEmpty)
  }

  it should "reject global start on the legacy architecture" in {
    assert(ArgParser.parseArgs(Array(
      "descriptor.json", "--architecture", "legacy", "--global-start"
    )).isEmpty)
  }

  it should "allow the descriptor path and benchmark software name to differ" in {
    val cfg = ArgParser.parseArgs(Array(
      "/tmp/descriptor.json", "--benchmark-name", "countDecoupled"
    )).get
    assert(cfg.json_path == "/tmp/descriptor.json")
    assert(cfg.benchmarkName.contains("countDecoupled"))
  }

  it should "reject unsafe benchmark software names" in {
    assert(ArgParser.parseArgs(Array(
      "descriptor.json", "--benchmark-name", "../countDecoupled"
    )).isEmpty)
  }

  behavior of "RAMA command-line modes"

  it should "parse striped RAMA mode" in {
    val cfg = ArgParser.parseArgs(Array("descriptor.json", "--rama-striping")).get
    assert(cfg.ramaStriping)
    assert(!cfg.ramaNoStriping)
  }

  it should "parse non-striped RAMA mode" in {
    val cfg = ArgParser.parseArgs(Array("descriptor.json", "--rama-no-striping")).get
    assert(!cfg.ramaStriping)
    assert(cfg.ramaNoStriping)
  }

  it should "reject conflicting RAMA modes" in {
    val cfg = ArgParser.parseArgs(
      Array("descriptor.json", "--rama-striping", "--rama-no-striping")
    )
    assert(cfg.isEmpty)
  }
}
