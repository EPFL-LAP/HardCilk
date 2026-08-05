package Util.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Util._

class WriteBufferCounterMetadataTests
    extends AnyFlatSpec
    with ChiselScalatestTester {
  behavior of "WriteBufferCounter release metadata"

  private val cfg = new WriteBufferCounterConfig(
    wAddr = 64,
    wData = 128,
    wAllow = 8,
    wAllowData = Seq(128),
    externalWriteSink = true,
    releaseMetadataWidth = 32,
    releaseMetadataOffset = 64
  )

  it should "consume metadata with B and replicate it onto every released child" in {
    test(new WriteBufferCounter(cfg)) { dut =>
      val axi = dut.m_axi
      val pkg = dut.s_pkg
      val allow = dut.s_allows.head
      val released = dut.m_allows.head
      val metadata = dut.s_releaseMetadata.get

      axi.AWREADY.get.poke(true.B)
      axi.WREADY.get.poke(true.B)
      axi.BVALID.get.poke(false.B)
      axi.BRESP.get.poke(0.U)
      pkg.TVALID.poke(false.B)
      allow.TVALID.poke(false.B)
      metadata.TVALID.poke(false.B)
      released.TREADY.poke(false.B)
      dut.clock.step(2)

      // WriteBundleCounter is {padding, allow, size, data, addr}; addr is LSB.
      val count = 3
      val address = BigInt("1000", 16)
      val writeData = BigInt("0123456789abcdef0011223344556677", 16)
      val size = 4
      val packageBits = address |
        (writeData << 64) |
        (BigInt(size) << (64 + 128)) |
        (BigInt(count) << (64 + 128 + 32))

      pkg.TDATA.poke(packageBits.U)
      pkg.TVALID.poke(true.B)
      while (!pkg.TREADY.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      pkg.TVALID.poke(false.B)

      // Wait until both write channels have reached the external sink.
      var awSeen = false
      var wSeen = false
      var guard = 0
      while (!(awSeen && wSeen)) {
        awSeen ||= axi.AWVALID.get.peek().litToBoolean
        wSeen ||= axi.WVALID.get.peek().litToBoolean
        dut.clock.step()
        guard += 1
        assert(guard < 30, "write did not reach the external sink")
      }

      // Queue child payloads before completing the write. Their old metadata
      // fields are deliberately nonzero and must be overwritten.
      val children = Seq(
        BigInt("11111111aaaaaaaa0000000000000001", 16),
        BigInt("22222222bbbbbbbb0000000000000002", 16),
        BigInt("33333333cccccccc0000000000000003", 16)
      )
      children.foreach { child =>
        allow.TDATA.poke(child.U)
        allow.TVALID.poke(true.B)
        while (!allow.TREADY.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        allow.TVALID.poke(false.B)
      }

      val responseMetadata = BigInt("deadbeef", 16)
      metadata.TDATA.poke(responseMetadata.U)
      metadata.TVALID.poke(true.B)
      axi.BVALID.get.poke(true.B)

      // B and metadata meet at one atomic unlock join after their input buffers;
      // output backpressure must not lose the replicated word.
      guard = 0
      var bAccepted = false
      var metadataAccepted = false
      while (!(bAccepted && metadataAccepted)) {
        val takeB = !bAccepted && axi.BREADY.get.peek().litToBoolean
        val takeMetadata =
          !metadataAccepted && metadata.TREADY.peek().litToBoolean
        dut.clock.step()
        if (takeB) {
          bAccepted = true
          axi.BVALID.get.poke(false.B)
        }
        if (takeMetadata) {
          metadataAccepted = true
          metadata.TVALID.poke(false.B)
        }
        guard += 1
        assert(guard < 30, "B/metadata unlock event was not accepted")
      }
      dut.clock.step(4)

      val metadataMask = ((BigInt(1) << 32) - 1) << 64
      released.TREADY.poke(true.B)
      children.foreach { child =>
        guard = 0
        while (!released.TVALID.peek().litToBoolean) {
          dut.clock.step()
          guard += 1
          assert(guard < 30, "released child did not appear")
        }
        val expected = (child & ~metadataMask) | (responseMetadata << 64)
        released.TDATA.expect(expected.U)
        dut.clock.step()
      }
      released.TVALID.expect(false.B)
    }
  }

  it should "fork one metadata response across multiple allow streams" in {
    val multiCfg = new WriteBufferCounterConfig(
      wAddr = 64,
      wData = 128,
      wAllow = 8,
      wAllowData = Seq(128, 128),
      externalWriteSink = true,
      releaseMetadataWidth = 32,
      releaseMetadataOffset = 64
    )

    test(new WriteBufferCounter(multiCfg)) { dut =>
      val axi = dut.m_axi
      val pkg = dut.s_pkg
      val allows = dut.s_allows
      val released = dut.m_allows
      val metadata = dut.s_releaseMetadata.get

      axi.AWREADY.get.poke(true.B)
      axi.WREADY.get.poke(true.B)
      axi.BVALID.get.poke(false.B)
      axi.BRESP.get.poke(0.U)
      pkg.TVALID.poke(false.B)
      allows.foreach(_.TVALID.poke(false.B))
      metadata.TVALID.poke(false.B)
      released.foreach(_.TREADY.poke(false.B))
      dut.clock.step(2)

      val counts = Seq(2, 1)
      val packageBits = BigInt("2000", 16) |
        (BigInt("0123456789abcdef0011223344556677", 16) << 64) |
        (BigInt(4) << (64 + 128)) |
        (BigInt(counts(0)) << (64 + 128 + 32)) |
        (BigInt(counts(1)) << (64 + 128 + 32 + 8))

      pkg.TDATA.poke(packageBits.U)
      pkg.TVALID.poke(true.B)
      while (!pkg.TREADY.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      pkg.TVALID.poke(false.B)

      var awSeen = false
      var wSeen = false
      var guard = 0
      while (!(awSeen && wSeen)) {
        awSeen ||= axi.AWVALID.get.peek().litToBoolean
        wSeen ||= axi.WVALID.get.peek().litToBoolean
        dut.clock.step()
        guard += 1
        assert(guard < 30, "write did not reach the external sink")
      }

      val children = Seq(
        Seq(
          BigInt("11111111aaaaaaaa0000000000000001", 16),
          BigInt("22222222bbbbbbbb0000000000000002", 16)
        ),
        Seq(BigInt("33333333cccccccc0000000000000003", 16))
      )
      children.zipWithIndex.foreach { case (streamChildren, stream) =>
        streamChildren.foreach { child =>
          allows(stream).TDATA.poke(child.U)
          allows(stream).TVALID.poke(true.B)
          while (!allows(stream).TREADY.peek().litToBoolean) dut.clock.step()
          dut.clock.step()
          allows(stream).TVALID.poke(false.B)
        }
      }

      val responseMetadata = BigInt("cafef00d", 16)
      metadata.TDATA.poke(responseMetadata.U)
      metadata.TVALID.poke(true.B)
      axi.BVALID.get.poke(true.B)

      guard = 0
      var bAccepted = false
      var metadataAccepted = false
      while (!(bAccepted && metadataAccepted)) {
        val takeB = !bAccepted && axi.BREADY.get.peek().litToBoolean
        val takeMetadata =
          !metadataAccepted && metadata.TREADY.peek().litToBoolean
        dut.clock.step()
        if (takeB) {
          bAccepted = true
          axi.BVALID.get.poke(false.B)
        }
        if (takeMetadata) {
          metadataAccepted = true
          metadata.TVALID.poke(false.B)
        }
        guard += 1
        assert(guard < 30, "B/metadata unlock event was not accepted")
      }
      dut.clock.step(4)

      val metadataMask = ((BigInt(1) << 32) - 1) << 64
      children.zipWithIndex.foreach { case (streamChildren, stream) =>
        released(stream).TREADY.poke(true.B)
        streamChildren.foreach { child =>
          guard = 0
          while (!released(stream).TVALID.peek().litToBoolean) {
            dut.clock.step()
            guard += 1
            assert(guard < 30, s"released child did not appear on stream $stream")
          }
          val expected =
            (child & ~metadataMask) | (responseMetadata << 64)
          released(stream).TDATA.expect(expected.U)
          dut.clock.step()
        }
        released(stream).TREADY.poke(false.B)
      }
    }
  }
}
