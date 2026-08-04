package SoftwareUtil

import org.scalatest.flatspec.AnyFlatSpec
import Descriptors._
import Descriptors.DescriptorJSON._

class KernelXmlRamaTests extends AnyFlatSpec {
  private lazy val watcherDescriptor =
    parseJsonFile[FullSysGenDescriptor]("taskDescriptors/mfpga/countDecoupled.json").normalized

  behavior of "KernelXmlTemplate RAMA connectivity"

  it should "stripe the resolved RAMA ports over HBM 0:15 and leave watcher ports direct" in {
    val cfg = KernelXmlTemplate.renderConnCfg(
      descriptor = watcherDescriptor,
      numMasters = 5,
      kernelName = "countDecoupled_0",
      hasWatcher = true,
      ramaPortIndices = Set(0, 1, 2),
      enableRamaStriping = true
    )

    assert(cfg.contains("sp=countDecoupled_0.m_axi_00:HBM[0:15].0.RAMA"))
    assert(cfg.contains("sp=countDecoupled_0.m_axi_02:HBM[0:15].2.RAMA"))
    assert(cfg.contains("sp=countDecoupled_0.m_axi_03:HBM[16:23].16\n"))
    assert(cfg.contains("sp=countDecoupled_0.m_axi_04:HBM[24:31].24\n"))
    assert(!cfg.contains("HBM[16:23].16.RAMA"))
    assert(!cfg.contains("HBM[24:31].24.RAMA"))
  }

  it should "attach RAMA only to requested PE ports in selective mode" in {
    val cfg = KernelXmlTemplate.renderConnCfg(
      descriptor = watcherDescriptor,
      numMasters = 5,
      kernelName = "countDecoupled_0",
      hasWatcher = true,
      ramaPortIndices = Set(1),
      enableRamaStriping = false
    )

    assert(cfg.contains("sp=countDecoupled_0.m_axi_00:HBM[0:15].0\n"))
    assert(cfg.contains("sp=countDecoupled_0.m_axi_01:HBM[0:15].1.RAMA"))
    assert(!cfg.contains("m_axi_00:HBM[0:15].0.RAMA"))
  }

  it should "stripe compute ports over all 32 banks when no watcher is present" in {
    val cfg = KernelXmlTemplate.renderConnCfg(
      descriptor = watcherDescriptor.copy(watcherConfig = None),
      numMasters = 2,
      kernelName = "countDecoupled_0",
      hasWatcher = false,
      ramaPortIndices = Set(0, 1),
      enableRamaStriping = true
    )

    assert(cfg.contains("sp=countDecoupled_0.m_axi_00:HBM[0:31].1.RAMA"))
    assert(cfg.contains("sp=countDecoupled_0.m_axi_01:HBM[0:31].0.RAMA"))
  }

  it should "preserve an explicit opt-out in striped mode" in {
    val cfg = KernelXmlTemplate.renderConnCfg(
      descriptor = watcherDescriptor,
      numMasters = 5,
      kernelName = "countDecoupled_0",
      hasWatcher = true,
      ramaPortIndices = Set(0, 2),
      enableRamaStriping = true
    )

    assert(cfg.contains("m_axi_00:HBM[0:15].0.RAMA"))
    assert(cfg.contains("m_axi_01:HBM[0:15].1\n"))
    assert(cfg.contains("m_axi_02:HBM[0:15].2.RAMA"))
  }

  it should "emit Mahfouz's watcher-safe striped XRT parameters" in {
    val tcl = KernelXmlTemplate.renderRamaXrtHookTcl(
      expectedRamaCount = 14,
      striped = true,
      memoryCount = 16
    )

    assert(tcl.contains("CONFIG.G_MEM_INTERLEAVE_TYPE {per_memory}"))
    assert(tcl.contains("CONFIG.G_MEM_COUNT {16}"))
    assert(tcl.contains("CONFIG.G_FRAGMENT_SIZE_BYTES {64}"))
    assert(tcl.contains("CONFIG.G_REORDER_QUEUE_DEPTH {256}"))
    assert(tcl.contains("expected 14 generated RAMA XCI files"))
    assert(tcl.contains("*hmss* bd_* ip * *rama*.xci"))
    assert(tcl.contains("get_ips -quiet -all *rama*"))
    assert(tcl.contains("rename generate_target hc_generate_target_original"))
    assert(tcl.contains("hc_patch_generated_ramas"))
    assert(tcl.contains("G_MEM_INTERLEAVE_TYPE=per_memory"))
  }

  it should "emit Mahfouz's non-striped XRT parameters" in {
    val tcl = KernelXmlTemplate.renderRamaXrtHookTcl(
      expectedRamaCount = 1,
      striped = false,
      memoryCount = 32
    )

    assert(tcl.contains("CONFIG.G_MEM_INTERLEAVE_TYPE {none}"))
    assert(tcl.contains("CONFIG.G_MEM_COUNT {4}"))
    assert(tcl.contains("CONFIG.G_FRAGMENT_SIZE_BYTES {128}"))
    assert(tcl.contains("CONFIG.G_REORDER_QUEUE_DEPTH {128}"))
  }

  it should "install the XRT RAMA hook once after system link" in {
    val cfg = KernelXmlTemplate.renderConnCfg(
      descriptor = watcherDescriptor,
      numMasters = 5,
      kernelName = "countDecoupled_0",
      hasWatcher = true,
      ramaPortIndices = Set(0),
      enableRamaStriping = true,
      ramaHookPath = Some("/tmp/rama_configure_xrt.tcl")
    )

    assert(cfg.contains("[linkhook]\ncustom=postSysLink,/tmp/rama_configure_xrt.tcl"))
    assert(!cfg.contains("do_first=vpl.synth"))
    assert(!cfg.contains("userPostSysLink"))
  }
}
