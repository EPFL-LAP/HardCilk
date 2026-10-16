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
  */
case class SlrProfile(
    name: String,
    crRowLo: Int,
    crRowHi: Int,
    clbCount: Int,
    reserved: Boolean = false
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
    placeDirective: String
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

  private val known: Map[String, DeviceProfile] =
    Seq(alveoU55C).map(p => p.model -> p).toMap

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
