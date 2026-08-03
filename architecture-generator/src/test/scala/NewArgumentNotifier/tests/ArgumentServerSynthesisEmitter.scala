package NewArgumentNotifier.tests

import _root_.circt.stage.ChiselStage
import NewArgumentNotifier.{
  ArgumentNetworks,
  ArgumentNetworksConfig,
  ArgumentServer
}

/** Emits the production-width ArgumentServer used by countDecoupled so memory
  * inference and hierarchical utilization can be checked out of context.
  */
object ArgumentServerSynthesisEmitter extends App {
  val targetDir =
    args.headOption.getOrElse("output/argumentServerSynthesis")

  ChiselStage.emitSystemVerilogFile(
    new ArgumentServer(
      counterWidth = 32,
      lineAddressWidth = 28,
      serverTagWidth = 1,
      serverIDWidth = 7,
      continuationSize = 512,
      NParallelNew = 4,
      NParallelUpdate = 5,
      cacheDelayCycles = 65,
      missedUpdateExtra = 64
    ),
    Array(s"--target-dir=$targetDir"),
    Array("--disable-all-randomization")
  )
}

/** Emits the complete countDecoupled ArgumentNetworks configuration used by
  * the investigated hardware checkpoint.
  */
object ArgumentNetworksSynthesisEmitter extends App {
  val targetDir =
    args.headOption.getOrElse("output/argumentNetworksSynthesis")

  ChiselStage.emitSystemVerilogFile(
    new ArgumentNetworks(
      ArgumentNetworksConfig(
        nServers = 2,
        newLanesPerServer = 4,
        updateLanesPerServer = 4,
        nSlowHandlers = 1,
        nEvictionSavers = 1,
        counterWidth = 32,
        sysAddressWidth = 64,
        realAddressWidth = 34,
        serverIDWidth = 7,
        continuationSize = 512,
        updatePayloadWidth = 64,
        updateOffsetWidth = 3,
        slowCutCount = 1,
        evictCutCount = 1,
        slowRequestQueueDepth = 64,
        cacheDelayCycles = 65,
        missedUpdateExtra = 64
      )
    ),
    Array(s"--target-dir=$targetDir"),
    Array("--disable-all-randomization")
  )
}
