package Util

/**
  * The Alveo card a HardCilk system is generated for.
  *
  * The hardware itself is board-agnostic; what changes per board is the
  * project/block-design TCL around it (QuestaSim and PCIe flows), the host
  * access path, and which flows exist at all. Selected by the descriptor's
  * `fpgaModel` (default `ALVEO_U55C`), or overridden with `--board`.
  *
  * @param fpgaModel       the descriptor's `fpgaModel` string for this board
  * @param cliName         the `--board` value
  * @param part            Vivado part
  * @param vivadoVersion   Vivado release the generated TCL is written against
  * @param supportsVitis   whether a Vitis/XRT platform exists for this board
  * @param maxPciePorts    HBM ports the design may use in the PCIe flow
  * @param hbmBaseAddress  address of the first HBM byte as the design's masters
  *                        and the host DMA see it
  * @param hbmSizeBytes    HBM capacity addressable from every port
  * @param minAxiAddrWidth smallest `widthAXIAddress` that reaches all of
  *                        `[hbmBaseAddress, hbmBaseAddress + hbmSizeBytes)`
  */
sealed abstract class Board(
    val fpgaModel: String,
    val cliName: String,
    val part: String,
    val vivadoVersion: String,
    val supportsVitis: Boolean,
    val maxPciePorts: Int,
    val hbmBaseAddress: BigInt,
    val hbmSizeBytes: BigInt,
    val minAxiAddrWidth: Int
)

object Board {

  /** Alveo U55C: UltraScale+ HBM IP (32 AXI ports, global crossbar), XDMA. */
  case object U55C
      extends Board(
        fpgaModel = "ALVEO_U55C",
        cliName = "u55c",
        part = "xcu55c-fsvh2892-2L-e",
        vivadoVersion = "2024.1",
        supportsVitis = true,
        // The host DMA path takes the 32nd HBM port.
        maxPciePorts = 31,
        hbmBaseAddress = BigInt(0),
        hbmSizeBytes = BigInt(16) << 30,
        minAxiAddrWidth = 34
      )

  /**
    * Alveo V80 (Versal HBM, AVED shell): CPM5 QDMA, HBM through the 64 HBM NMUs
    * of the NoC. Every HBM NMU is connected to all 32 pseudo channels, so each
    * port addresses the whole 32 GB at 0x40_0000_0000. The host DMA enters the
    * NoC from the CPM, so it does not take an HBM port. There is no Vitis
    * platform for the V80.
    */
  case object V80
      extends Board(
        fpgaModel = "ALVEO_V80",
        cliName = "v80",
        part = "xcv80-lsva4737-2MHP-e-S",
        vivadoVersion = "2025.1",
        supportsVitis = false,
        maxPciePorts = 64,
        hbmBaseAddress = BigInt("4000000000", 16),
        hbmSizeBytes = BigInt(32) << 30,
        // 0x40_0000_0000 + 32 GB - 1 = 0x47_FFFF_FFFF needs 39 bits; 40 keeps
        // the width the hand-built V80 designs used.
        minAxiAddrWidth = 40
      )

  val all: Seq[Board] = Seq(U55C, V80)

  def fromModel(fpgaModel: String): Option[Board] = all.find(_.fpgaModel == fpgaModel)

  def fromCli(name: String): Option[Board] = all.find(_.cliName == name.toLowerCase)

  def forModel(fpgaModel: String): Board =
    fromModel(fpgaModel).getOrElse(
      throw new IllegalArgumentException(
        s"Unsupported fpgaModel '$fpgaModel'. Known: ${all.map(_.fpgaModel).mkString(", ")}"
      )
    )
}
