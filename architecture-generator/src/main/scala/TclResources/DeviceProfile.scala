package TclResources

/**
  * Per-SLR geometry of a target device.
  *
  * @param name     the SLR's Vivado name, as accepted by `resize_pblock -add`
  * @param crRowLo  lowest clock-region row (the `Y` in `X0Y<n>`) this SLR covers
  * @param crRowHi  highest clock-region row this SLR covers
  * @param clbCount CLBs available in this SLR; the denominator of the occupancy
  *                 guard in [[Floorplan]]
  * @param reserved true when the device fixes hard IP the design cannot move out
  *                 of this SLR (PCIe, its GTY quads, the HBM AXI interfaces), so
  *                 the generated logic must stay out of it
  * @param ramb36Count RAMB36 sites in this SLR; 0 = not modelled. Only the
  *                 multi-spill planner uses it.
  */
case class SlrProfile(
    name: String,
    crRowLo: Int,
    crRowHi: Int,
    clbCount: Int,
    reserved: Boolean = false,
    ramb36Count: Int = 0
)

/**
  * What the floorplan generator needs to know about a device.
  *
  * Keyed off `FullSysGenDescriptor.fpgaModel` through [[DeviceProfile.forModel]],
  * so adding a device is a matter of adding an entry here rather than touching
  * the emitter logic in [[Floorplan]].
  *
  * @param model        the `fpgaModel` string this profile answers to
  * @param slrs         every SLR, ordered bottom to top
  * @param hubSlr       where the schedulers, allocators, notifiers and the rest of
  *                     the kernel go. Hub logic fans out to every PE, so it wants
  *                     the die that is one hop from both PE groups.
  * @param spillSlr     the SLR whole PEs are moved into once a task has more of
  *                     them than [[Floorplan.splitThreshold]]
  * @param memSideSlr   the SLR the memory-side register slice must sit in, since
  *                     that is where the device puts HBM
  * @param clbPerPe     coarse per-PE CLB estimate for the occupancy guard. This is
  *                     a generation-time sanity check, not a synthesis result:
  *                     it only has to be right to within about one PE.
  * @param clbHubBase   coarse CLB estimate for the non-PE kernel logic
  *                     (schedulers, allocators, notifiers, the HBM interconnect),
  *                     charged to `hubSlr`.
  * @param occupancyWarnFraction
  *                     fraction of an SLR's CLBs above which the generator warns.
  * @param placeDirective
  *                     `place_design -directive` for this device. Split out from
  *                     the rest of the strategy in [[TclGeneralConfigs.getImplStrategyTcl]]
  *                     because the right answer is device-shaped: an SSI
  *                     directive only means anything on a multi-die part.
  * @param extraSpillSlrs further SLRs PEs are spread over, after `spillSlr`
  *                     (multi-spill planning, see [[Floorplan.plan]]). Empty keeps
  *                     the two-die hub/spill split.
  * @param reservedClb  CLBs per SLR already taken by logic outside the kernel
  *                     (shell, memory-side slices), charged by the multi-spill planner
  * @param sllPerBoundary SLLs between two adjacent SLRs; 0 = not modelled. The
  *                     multi-spill planner keeps the PEs it moves off the hub
  *                     within a fraction of it.
  * @param bdInstance   instance name of the block design in the synthesised netlist
  */
case class DeviceProfile(
    model: String,
    slrs: Seq[SlrProfile],
    hubSlr: String,
    spillSlr: String,
    memSideSlr: String,
    clbPerPe: Int,
    clbHubBase: Int,
    occupancyWarnFraction: Double,
    placeDirective: String,
    extraSpillSlrs: Seq[String] = Seq.empty,
    sllPerBoundary: Int = 0,
    reservedClb: Map[String, Int] = Map.empty,
    bdInstance: String = "design_1_i"
) {
  def slr(name: String): SlrProfile =
    slrs
      .find(_.name == name)
      .getOrElse(throw new IllegalArgumentException(s"$model has no SLR named '$name'"))
}

object DeviceProfile {

  /**
    * Alveo U55C (xcu55c), 3 SLRs.
    *
    * SLR0 holds the PCIE4CE4 sites, all sixteen PCIe GTY channels (quads 224-227)
    * and all thirty-two HBM AXI interfaces. None of those can be moved, so the
    * generated design is pushed up into SLR1/SLR2 and reaches HBM through the
    * rs0/rs1 register-slice pair. Clock-region rows: SLR0 = Y0-Y3, SLR1 = Y4-Y7,
    * SLR2 = Y8-Y11.
    *
    * `clbPerPe` and `occupancyWarnFraction` are calibrated from measured builds of
    * this design family on this device, not from a datasheet:
    *
    *   - a 158k-LUT kernel occupying 67.7% of SLR1's CLBs closed at +0.028 ns;
    *   - the 265k-LUT kernel at 80.0% did not (-1.909 ns), so the guard fires
    *     at 65%;
    *   - the four PEs this design moved to SLR2 measured 49.7% of an SLR between
    *     them, i.e. about 12.4% or ~6700 CLBs each.
    *
    * `placeDirective` is `SSI_SpreadSLLs` rather than the Vitis default
    * `ExtraNetDelay_high`. On the measured pageRank_overlap build the router said
    * so itself, twice:
    *
    *   WARNING: [Route 35-3311] The design has high localized SLL routing demand.
    *   Router might not be able to find a suitable solution to route all SLR
    *   crossing nets. Use SSI placer directives or location constraints that
    *   reduce localized SLL routing congestion.
    *
    * That build used 16379 SLLs with SLR1->SLR2 at 28.7% occupancy, and finished
    * at WNS -0.107 ns with congestion at level 5. `ExtraNetDelay_high` is not an
    * SSI directive and does nothing about where the crossings land; SSI_SpreadSLLs
    * is the one named for this condition.
    */
  private val alveoU55C = DeviceProfile(
    model = "ALVEO_U55C",
    slrs = Seq(
      SlrProfile("SLR0", crRowLo = 0, crRowHi = 3, clbCount = 54000, reserved = true),
      SlrProfile("SLR1", crRowLo = 4, crRowHi = 7, clbCount = 54000),
      SlrProfile("SLR2", crRowLo = 8, crRowHi = 11, clbCount = 54000)
    ),
    hubSlr = "SLR1",
    spillSlr = "SLR2",
    memSideSlr = "SLR0",
    clbPerPe = 6700,
    clbHubBase = 6000,
    occupancyWarnFraction = 0.65,
    placeDirective = "SSI_SpreadSLLs"
  )


  /**
    * Alveo V80 (xcv80-lsva4737-2MHP-e-S), 3 SLRs. Measured from the device in
    * Vivado 2025.1 (`get_sites -of <slr> -filter {SITE_TYPE =~ SLICE*}`; one
    * Versal SLICE = 8 LUT6 + 16 FF, the same unit `clbCount` uses on the U55C):
    *
    *   SLR0  clock-region rows Y0-Y4   112528 slices, 1341 RAMB36 (row 0 = PS/CPM5/PMC, no fabric)
    *   SLR1  rows Y5-Y7                102528 slices, 1200 RAMB36
    *   SLR2  rows Y8-Y11               106720 slices, 1200 RAMB36 (row 11 = the 64 HBM NMUs, no fabric)
    *
    * HBM and all 64 NOC_NMU_HBM2E sites are at the top of SLR2, so SLR2 is the
    * memory side. It is also the hub: with the hub on SLR1 (the U55C shape) a
    * 32-port pageRank_overlap needed 23525 SLLs between SLR1 and SLR2 against
    * 18870 available (Place 30-5425) -- every HBM port and every SLR2 PE's
    * memory traffic crossed the boundary, the latter twice. On SLR2 the HBM
    * ports and the hub-die PEs cross nothing; PEs spill to SLR1 and then SLR0.
    * The CPM (PCIe/QDMA) is in SLR0, but the host reaches the kernel through
    * the NoC, so no die is reserved for it; AVED's own logic (base_logic,
    * clock_reset) is pinned to SLR0 by the shell's impl.xdc.
    *
    * Placer directive: Vivado 2025.1 has no SSI_* directives on Versal, so
    * Explore. `clbHubBase` is measured: the non-PE kernel logic of a 12-PE,
    * 32-port pageRank_overlap synthesised to 88k LUT / 35k FF (~15k CLB at the
    * 1.35 packing factor); an applyFn PE to 19.2k LUT / 28.9k FF / 78 RAMB36,
    * which the HLS-based estimate puts at 3342 CLB.
    */
  private val alveoV80 = DeviceProfile(
    model = "ALVEO_V80",
    slrs = Seq(
      SlrProfile("SLR0", crRowLo = 0, crRowHi = 4, clbCount = 112528, ramb36Count = 1341),
      SlrProfile("SLR1", crRowLo = 5, crRowHi = 7, clbCount = 102528, ramb36Count = 1200),
      SlrProfile("SLR2", crRowLo = 8, crRowHi = 11, clbCount = 106720, ramb36Count = 1200)
    ),
    hubSlr = "SLR2",
    spillSlr = "SLR1",
    memSideSlr = "SLR2",
    clbPerPe = 6700,
    clbHubBase = 15000,
    occupancyWarnFraction = 0.65,
    placeDirective = "Explore",
    extraSpillSlrs = Seq("SLR0"),
    // From Place 30-5425 ("only 18870 available" between SLR1 and SLR2).
    sllPerBoundary = 18870,
    // SLR0: AVED base_logic + clock_reset and the CIPS/NoC/DDR glue;
    // SLR2: the HBM-side register slices of 32 ports.
    reservedClb = Map("SLR0" -> 8000, "SLR2" -> 3000),
    bdInstance = "top_i"
  )

  private val known: Map[String, DeviceProfile] =
    Seq(alveoU55C, alveoV80).map(p => p.model -> p).toMap

  def forModel(fpgaModel: String): DeviceProfile =
    known.getOrElse(
      fpgaModel,
      throw new IllegalArgumentException(
        s"No floorplan device profile for fpgaModel '$fpgaModel'. " +
          s"Known models: ${known.keys.toSeq.sorted.mkString(", ")}. " +
          s"Add one to TclResources.DeviceProfile."
      )
    )

  def isKnown(fpgaModel: String): Boolean = known.contains(fpgaModel)
}
