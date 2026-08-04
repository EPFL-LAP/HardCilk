package TclResources

import org.scalatest.flatspec.AnyFlatSpec

class TclRamaTests extends AnyFlatSpec {
  behavior of "TclGeneralConfigs RAMA paths"

  it should "emit the 16-bank watcher-safe striping configuration" in {
    val tcl = TclGeneralConfigs.getRamaHbmPathTcl(
      name = "compute",
      upstreamPin = "[get_bd_intf_pins design/m_axi_00]",
      hbmPort = 3,
      addressWidth = 34,
      striped = true,
      memoryCount = 16,
      clkPin = "[get_bd_pins clk]",
      resetPin = "[get_bd_pins resetn]"
    )

    assert(tcl.contains("CONFIG.G_MEM_INTERLEAVE_TYPE {per_memory}"))
    assert(tcl.contains("CONFIG.G_MEM_COUNT {16}"))
    assert(tcl.contains("CONFIG.G_FRAGMENT_SIZE_BYTES {64}"))
    assert(tcl.contains("hbm_0/SAXI_03_8HI"))
  }

  it should "emit Mahfouz-compatible selective non-striped RAMA settings" in {
    val tcl = TclGeneralConfigs.getRamaHbmPathTcl(
      name = "selective",
      upstreamPin = "upstream",
      hbmPort = 0,
      addressWidth = 34,
      striped = false,
      memoryCount = 4,
      clkPin = "clk",
      resetPin = "resetn"
    )

    assert(tcl.contains("CONFIG.G_MEM_INTERLEAVE_TYPE {none}"))
    assert(tcl.contains("CONFIG.G_MEM_COUNT {4}"))
    assert(tcl.contains("CONFIG.G_FRAGMENT_SIZE_BYTES {128}"))
    assert(tcl.contains("CONFIG.G_REORDER_QUEUE_DEPTH {128}"))
  }
}
