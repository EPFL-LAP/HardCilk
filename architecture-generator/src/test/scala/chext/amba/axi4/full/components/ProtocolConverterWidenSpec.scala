package chext.amba.axi4.full.components

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import chext.amba.axi4
import chext.amba.axi4.full.ConnectOp._

class ProtocolConverterWidenHarness extends Module {
  private val slaveCfg = axi4.Config(4, 64, 32)
  private val masterCfg = axi4.Config(2, 34, 256)

  val s_axi = IO(axi4.full.Slave(slaveCfg))
  val m_axi = IO(axi4.full.Master(masterCfg))

  private val pc = Module(new ProtocolConverter(
    ProtocolConverterConfig(
      axiSlaveCfg = slaveCfg.copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
      axiMasterCfg = masterCfg
    )
  ))
  private val widen = Module(new Widen(WidenConfig(masterCfg)))

  s_axi :=> pc.s_axi
  pc.m_axi :=> widen.s_axi
  widen.m_axi :=> m_axi
}

class ProtocolConverterWidenSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  behavior of "ProtocolConverter followed by Widen"

  private def init(c: ProtocolConverterWidenHarness): Unit = {
    c.s_axi.ar.valid.poke(false.B)
    c.s_axi.aw.valid.poke(false.B)
    c.s_axi.w.valid.poke(false.B)
    c.s_axi.r.ready.poke(false.B)
    c.s_axi.b.ready.poke(false.B)

    c.m_axi.ar.ready.poke(false.B)
    c.m_axi.aw.ready.poke(false.B)
    c.m_axi.w.ready.poke(false.B)
    c.m_axi.r.valid.poke(false.B)
    c.m_axi.b.valid.poke(false.B)
  }

  private def waitUntil(c: ProtocolConverterWidenHarness, clue: String)(
      pred: => Boolean
  ): Unit = {
    var cycles = 0
    while (!pred && cycles < 32) {
      c.clock.step()
      cycles += 1
    }
    withClue(clue) {
      pred shouldBe true
    }
  }

  private def pokeReadAddress(
      c: ProtocolConverterWidenHarness,
      addr: BigInt,
      len: Int = 0,
      size: Int = 2
  ): Unit = {
    c.s_axi.ar.bits.id.poke(0.U)
    c.s_axi.ar.bits.addr.poke(addr.U)
    c.s_axi.ar.bits.len.poke(len.U)
    c.s_axi.ar.bits.size.poke(size.U)
    c.s_axi.ar.bits.burst.poke(1.U)
    c.s_axi.ar.bits.lock.poke(false.B)
    c.s_axi.ar.bits.cache.poke(0.U)
    c.s_axi.ar.bits.prot.poke(0.U)
    c.s_axi.ar.bits.qos.poke(0.U)
    c.s_axi.ar.bits.region.poke(0.U)
    c.s_axi.ar.valid.poke(true.B)
  }

  private def pokeWriteAddress(
      c: ProtocolConverterWidenHarness,
      addr: BigInt,
      len: Int = 0,
      size: Int = 2
  ): Unit = {
    c.s_axi.aw.bits.id.poke(0.U)
    c.s_axi.aw.bits.addr.poke(addr.U)
    c.s_axi.aw.bits.len.poke(len.U)
    c.s_axi.aw.bits.size.poke(size.U)
    c.s_axi.aw.bits.burst.poke(1.U)
    c.s_axi.aw.bits.lock.poke(false.B)
    c.s_axi.aw.bits.cache.poke(0.U)
    c.s_axi.aw.bits.prot.poke(0.U)
    c.s_axi.aw.bits.qos.poke(0.U)
    c.s_axi.aw.bits.region.poke(0.U)
    c.s_axi.aw.valid.poke(true.B)
  }

  private def pokeWriteData(
      c: ProtocolConverterWidenHarness,
      data: BigInt,
      last: Boolean
  ): Unit = {
    c.s_axi.w.bits.data.poke(data.U)
    c.s_axi.w.bits.strb.poke("hf".U)
    c.s_axi.w.bits.last.poke(last.B)
    c.s_axi.w.valid.poke(true.B)
  }

  private def sendWriteData(
      c: ProtocolConverterWidenHarness,
      data: BigInt,
      last: Boolean
  ): Unit = {
    pokeWriteData(c, data, last)
    waitUntil(c, "narrow write data was not accepted") {
      c.s_axi.w.ready.peek().litToBoolean
    }
    c.clock.step()
    c.s_axi.w.valid.poke(false.B)
  }

  private def expectWideWriteData(
      c: ProtocolConverterWidenHarness,
      data: BigInt,
      last: Boolean,
      expectedData: BigInt,
      expectedStrb: BigInt,
      expectedLast: Boolean
  ): Unit = {
    pokeWriteData(c, data, last)
    waitUntil(c, "wide write data was not produced") {
      c.s_axi.w.ready.peek().litToBoolean && c.m_axi.w.valid.peek().litToBoolean
    }
    c.m_axi.w.bits.data.expect(expectedData.U)
    c.m_axi.w.bits.strb.expect(expectedStrb.U)
    c.m_axi.w.bits.last.expect(expectedLast.B)
    c.clock.step()
    c.s_axi.w.valid.poke(false.B)
  }

  private def lane32(value: BigInt, lane: Int): BigInt =
    value << (32 * lane)

  it should "read the selected 32-bit lane from an aligned 256-bit beat" in {
    test(new ProtocolConverterWidenHarness) { c =>
      init(c)
      c.m_axi.ar.ready.poke(true.B)
      c.s_axi.r.ready.poke(true.B)

      pokeReadAddress(c, addr = 0x1004)
      waitUntil(c, "wide read address was not produced") {
        c.m_axi.ar.valid.peek().litToBoolean
      }

      c.m_axi.ar.bits.addr.expect(0x1000.U)
      c.m_axi.ar.bits.size.expect(5.U)
      c.m_axi.ar.bits.len.expect(0.U)

      c.clock.step()
      c.s_axi.ar.valid.poke(false.B)

      c.m_axi.r.bits.id.poke(0.U)
      c.m_axi.r.bits.data.poke((lane32(BigInt("feedcafe", 16), 1) | BigInt("11111111", 16)).U)
      c.m_axi.r.bits.resp.poke(0.U)
      c.m_axi.r.bits.last.poke(true.B)
      c.m_axi.r.valid.poke(true.B)

      waitUntil(c, "narrow read response was not produced") {
        c.s_axi.r.valid.peek().litToBoolean
      }
      c.s_axi.r.bits.data.expect("hfeedcafe".U)
      c.s_axi.r.bits.last.expect(true.B)
    }
  }

  it should "turn an unaligned 32-bit write into an aligned lane-masked 256-bit write" in {
    test(new ProtocolConverterWidenHarness) { c =>
      init(c)
      c.m_axi.aw.ready.poke(true.B)
      c.m_axi.w.ready.poke(true.B)

      pokeWriteAddress(c, addr = 0x1004)

      waitUntil(c, "wide write address was not produced") {
        c.m_axi.aw.valid.peek().litToBoolean
      }
      c.m_axi.aw.bits.addr.expect(0x1000.U)
      c.m_axi.aw.bits.size.expect(5.U)
      c.m_axi.aw.bits.len.expect(0.U)

      expectWideWriteData(
        c,
        data = BigInt("aabbccdd", 16),
        last = true,
        expectedData = lane32(BigInt("aabbccdd", 16), 1),
        expectedStrb = BigInt(0xf) << 4,
        expectedLast = true
      )
    }
  }

  it should "preserve an aligned lane-zero 32-bit write as a single full-width beat" in {
    test(new ProtocolConverterWidenHarness) { c =>
      init(c)
      c.m_axi.aw.ready.poke(true.B)
      c.m_axi.w.ready.poke(true.B)

      pokeWriteAddress(c, addr = 0x1000)

      waitUntil(c, "wide lane-zero write address was not produced") {
        c.m_axi.aw.valid.peek().litToBoolean
      }
      c.m_axi.aw.bits.addr.expect(0x1000.U)
      c.m_axi.aw.bits.size.expect(5.U)
      c.m_axi.aw.bits.len.expect(0.U)

      expectWideWriteData(
        c,
        data = BigInt("12345678", 16),
        last = true,
        expectedData = BigInt("12345678", 16),
        expectedStrb = BigInt(0xf),
        expectedLast = true
      )
    }
  }

  it should "coalesce a short 32-bit burst that fits in one 256-bit beat" in {
    test(new ProtocolConverterWidenHarness) { c =>
      init(c)
      c.m_axi.aw.ready.poke(true.B)

      pokeWriteAddress(c, addr = 0x1004, len = 1)
      waitUntil(c, "wide burst write address was not produced") {
        c.m_axi.aw.valid.peek().litToBoolean
      }
      c.m_axi.aw.bits.addr.expect(0x1000.U)
      c.m_axi.aw.bits.size.expect(5.U)
      c.m_axi.aw.bits.len.expect(0.U)

      c.clock.step()
      c.s_axi.aw.valid.poke(false.B)

      c.m_axi.w.ready.poke(false.B)
      sendWriteData(c, data = BigInt("11111111", 16), last = false)

      // The wide side must not see a partial beat before the whole narrow burst
      // has been collected.
      c.m_axi.w.valid.expect(false.B)

      c.m_axi.w.ready.poke(true.B)
      val expectedData =
        lane32(BigInt("11111111", 16), 1) | lane32(BigInt("22222222", 16), 2)
      val expectedStrb = (BigInt(0xf) << 4) | (BigInt(0xf) << 8)

      expectWideWriteData(
        c,
        data = BigInt("22222222", 16),
        last = true,
        expectedData = expectedData,
        expectedStrb = expectedStrb,
        expectedLast = true
      )
    }
  }

  it should "split a 32-bit burst that crosses a 256-bit boundary into two full-width beats" in {
    test(new ProtocolConverterWidenHarness) { c =>
      init(c)
      c.m_axi.aw.ready.poke(true.B)
      c.m_axi.w.ready.poke(true.B)

      pokeWriteAddress(c, addr = 0x101c, len = 1)
      waitUntil(c, "wide crossing-burst write address was not produced") {
        c.m_axi.aw.valid.peek().litToBoolean
      }
      c.m_axi.aw.bits.addr.expect(0x1000.U)
      c.m_axi.aw.bits.size.expect(5.U)
      c.m_axi.aw.bits.len.expect(1.U)

      c.clock.step()
      c.s_axi.aw.valid.poke(false.B)

      expectWideWriteData(
        c,
        data = BigInt("77777777", 16),
        last = false,
        expectedData = lane32(BigInt("77777777", 16), 7),
        expectedStrb = BigInt(0xf) << 28,
        expectedLast = false
      )

      expectWideWriteData(
        c,
        data = BigInt("88888888", 16),
        last = true,
        expectedData = BigInt("88888888", 16),
        expectedStrb = BigInt(0xf),
        expectedLast = true
      )
    }
  }
}
