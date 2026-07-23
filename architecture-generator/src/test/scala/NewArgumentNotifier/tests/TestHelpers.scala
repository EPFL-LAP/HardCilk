package NewArgumentNotifier.tests

import NewArgumentNotifier.ArgumentNetworksConfig

/** Shared helpers mirroring the hardware line layout. */
object NanTestUtil {
  def line(counterWidth: Int, continuationSize: Int)(
      counter: BigInt,
      remainder: BigInt
  ): BigInt = {
    require(counter < (BigInt(1) << counterWidth))
    require(remainder < (BigInt(1) << (continuationSize - counterWidth)))
    (remainder << counterWidth) | counter
  }

  def splitLine(counterWidth: Int, continuationSize: Int)(
      value: BigInt
  ): (BigInt, BigInt) = {
    (
      value & ((BigInt(1) << counterWidth) - 1),
      value >> counterWidth
    )
  }

  def lineAddress(cfg: ArgumentNetworksConfig)(byteAddress: BigInt): BigInt =
    byteAddress >> cfg.lineShift
}
