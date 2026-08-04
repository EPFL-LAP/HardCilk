package Util.tests

import org.scalatest.flatspec.AnyFlatSpec
import Util.ArgParser

class ArgParserRamaTests extends AnyFlatSpec {
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
