package SoftwareUtil

import Descriptors._
import java.io.PrintWriter
import java.nio.file.{Files, Paths}

/** Emits the Vitis RTL-kernel description (`user_0.xml`) and the `v++`
  * connectivity config (`conn_u55c.cfg`) for a HardCilk top-level kernel.
  *
  * Today these two files are hand-maintained per benchmark and have to be
  * corrected by hand every time the port list changes. Everything they need is
  * already computed inside the generator, so we emit them instead and keep them
  * in lockstep with the RTL:
  *
  *  - the `m_axi_*` master list comes straight from `numHbmPortExports` (the
  *    total count of top-level HBM masters, which *includes* the LockServer's
  *    dedicated port appended last by `HardCilk.connectLockServer`);
  *  - the `s_axil_mgmt_hardcilk` register block uses the per-server management
  *    base addresses `(idx << 6) + base` already assigned in the
  *    `FullSysGenDescriptor` constructor (so they match `FullSysGenDescriptor.h`
  *    and the host driver exactly);
  *  - the mFPGA on/off state is read off the descriptor — with the flags off
  *    (single-FPGA BFS) no `m_axis_mFPGA`/`s_axis_mFPGA` ports and no CMAC stream
  *    connects are emitted.
  *
  * The emitter is benchmark-agnostic: the only lock-specific aspect is "one extra
  * `m_axi`", which it gets for free from `numHbmPortExports`. The `toLock`/
  * `fromLock` lanes never cross the kernel boundary, so nothing is emitted for
  * them.
  */
object KernelXmlTemplate {

  /** Write `user_0.xml` and `conn_u55c.cfg` into `outputDir`.
    *
    * @param descriptor         the parsed system descriptor (post-`validate()`,
    *                           so the per-server base addresses are assigned)
    * @param numHbmPortExports  total top-level `m_axi_*` masters, lock port last
    * @param kernelName         Vitis kernel name, e.g. "BFS_0"
    * @param vlnvName           VLNV leaf, e.g. "BFS" -> epfl.ch:hardcilk:BFS:1.0
    * @param outputDir          directory to write both files into
    */
  def generate(
      descriptor: FullSysGenDescriptor,
      numHbmPortExports: Int,
      kernelName: String,
      vlnvName: String,
      outputDir: String
  ): Unit = {
    Files.createDirectories(Paths.get(outputDir))

    val xml = renderKernelXml(descriptor, numHbmPortExports, kernelName, vlnvName)
    write(s"$outputDir/user_0.xml", xml)

    val cfg = renderConnCfg(descriptor, numHbmPortExports, kernelName)
    write(s"$outputDir/conn_u55c.cfg", cfg)
  }

  // --- helpers ---------------------------------------------------------------

  private def write(path: String, content: String): Unit = {
    val w = new PrintWriter(path)
    try w.write(content)
    finally w.close()
  }

  private def hex(v: Long): String = f"0x${v.toHexString.toUpperCase}"

  private def nextPow2(v: Long): Long = {
    var p = 1L
    while (p < v) p <<= 1
    p
  }

  private def portName(i: Int): String = f"m_axi_${i}%02d"

  /** Every assigned management-server base address, in `(idx << 6) + base`
    * order. These mirror `FullSysGenDescriptor.h` exactly; the host driver
    * writes raw registers at these offsets, so the args below are packaging
    * bookkeeping only — but we still place them at the true offsets so the XML is
    * self-consistent with the header. */
  private def configBaseAddresses(descriptor: FullSysGenDescriptor): Seq[Int] =
    descriptor.taskDescriptors.flatMap { t =>
      t.mgmtBaseAddresses.schedulerServersBaseAddresses ++
        t.mgmtBaseAddresses.allocationServersBaseAddresses ++
        t.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses
    }.sorted

  // --- user_0.xml ------------------------------------------------------------

  private def renderKernelXml(
      descriptor: FullSysGenDescriptor,
      numMasters: Int,
      kernelName: String,
      vlnvName: String
  ): String = {

    val mfpga = descriptor.mFPGASynth || descriptor.mFPGASimulation

    // --- ports ---
    val masterPorts = (0 until numMasters).map { i =>
      s"""      <port name="${portName(i)}" mode="master" range="0x3FFFFFFFF" dataWidth="256" portType="addressable" base="0x0"/>"""
    }

    // Size the management slave to cover both the per-server register blocks and
    // the mem_* pointer args region (placed past the last server block, >= 0x200
    // to match the existing hand-written XMLs).
    val configAddrs = configBaseAddresses(descriptor)
    val maxConfig   = if (configAddrs.nonEmpty) configAddrs.max else 0
    val memArgsBase = math.max(0x200L, (((maxConfig.toLong) >> 6) + 1) << 6)
    val topOffset   = memArgsBase + numMasters.toLong * 8
    val slaveRange  = math.max(0x1000L, nextPow2(topOffset))

    val mgmtPort =
      s"""      <port name="s_axil_mgmt_hardcilk" mode="slave" range="${hex(slaveRange)}" dataWidth="32" portType="addressable" base="0x0"/>"""

    val mfpgaPorts =
      if (mfpga)
        Seq(
          """      <port name="m_axis_mFPGA" mode="write_only" dataWidth="512" portType="stream"/>""",
          """      <port name="s_axis_mFPGA" mode="read_only" dataWidth="512" portType="stream"/>"""
        )
      else Seq.empty

    val portsBlock = (masterPorts ++ Seq(mgmtPort) ++ mfpgaPorts).mkString("\n")

    // --- args ---
    // Scalar management args: one per config server base address. (Cosmetic; the
    // driver writes raw registers. Placed at the true (idx<<6)+base offsets.)
    var id = 0
    val configArgs = configAddrs.map { addr =>
      val a =
        s"""      <arg name="cfg_${id}" addressQualifier="0" id="${id}" port="s_axil_mgmt_hardcilk" size="0x8" offset="${hex(addr.toLong)}" hostOffset="0x0" hostSize="0x8" type="ap_uint&lt;64>"/>"""
      id += 1
      a
    }

    // One mem_N void* arg per master port.
    val memArgs = (0 until numMasters).map { i =>
      val off = memArgsBase + i.toLong * 8
      val a =
        s"""      <arg name="mem_${i}" addressQualifier="1" id="${id}" port="${portName(i)}" size="0x8" offset="${hex(off)}" hostOffset="0x0" hostSize="0x8" type="void*"/>"""
      id += 1
      a
    }

    val mfpgaArgs =
      if (mfpga) {
        val msOut =
          s"""      <arg name="m_axis_mFPGA" addressQualifier="4" id="${id}" port="m_axis_mFPGA" size="0x0" offset="0x0" hostOffset="0x0" hostSize="0x0" memSize="0" type="stream&lt;ap_axiu&lt;512,0,0,4>>&amp;"/>"""
        id += 1
        val msIn =
          s"""      <arg name="s_axis_mFPGA" addressQualifier="4" id="${id}" port="s_axis_mFPGA" size="0x0" offset="0x0" hostOffset="0x0" hostSize="0x0" memSize="0" type="stream&lt;ap_axiu&lt;512,0,0,4>>&amp;"/>"""
        id += 1
        Seq(msOut, msIn)
      } else Seq.empty

    val argsBlock = (configArgs ++ memArgs ++ mfpgaArgs).mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
<root versionMajor="1" versionMinor="9">
  <kernel name="${kernelName}" language="ip" type="user_managed" vlnv="epfl.ch:hardcilk:${vlnvName}:1.0" attributes="" preferredWorkGroupSizeMultiple="0" workGroupSize="1" hwControlProtocol="user_managed">
    <ports>
${portsBlock}
    </ports>
    <args>
${argsBlock}
    </args>
  </kernel>
</root>
"""
  }

  // --- conn_u55c.cfg ---------------------------------------------------------

  private def renderConnCfg(
      descriptor: FullSysGenDescriptor,
      numMasters: Int,
      kernelName: String
  ): String = {

    val freqHz = descriptor.targetFrequency.toLong * 1000000L
    require(numMasters >= 1, s"expected at least one AXI master, got $numMasters")

    // Every master (PE/server masters AND, when present, the LockServer's
    // dedicated last m_axi master) is mapped to the full U55C HBM range.
    // The suffix after the range pins the HBM switch-network S_AXI index. Keep
    // the lock server on S_AXI00 for the shortest path to HBM0 while preserving
    // access to all pseudo-channels; assign the other masters unique nonzero
    // switch indices so the linker cannot reuse index 0.
    val lockPortIndex = numMasters - 1
    def switchIndex(i: Int): Int =
      if (i == lockPortIndex) 0 else i + 1

    val spLines = (0 until numMasters).map { i =>
      s"sp=${kernelName}.${portName(i)}:HBM[0:31].${switchIndex(i)}"
    }.mkString("\n")

    s"""[connectivity]
nk=${kernelName}:1:${kernelName}

${spLines}

[clock]
freqHz=${freqHz}:${kernelName}.clock

[vivado]
prop=run.impl_1.strategy=Performance_HighUtilSLRs
"""
  }
}
