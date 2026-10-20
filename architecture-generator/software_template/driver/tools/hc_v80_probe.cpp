// hc_v80_probe: first thing to run on a V80 after v80_bringup.sh.
//
// Checks the host <-> card paths that QdmaMemory relies on, without the HardCilk
// protocol:
//   1. HBM through QDMA MM: write a pattern and read it back at several places
//      across the 32 GB window (both ends, every 1 GB pseudo channel, 4 KB and
//      chunk boundaries). Includes small and unaligned transfers, done both
//      exactly and through QdmaMemory's widened read, so the result shows
//      directly whether the old driver's whole-4KB-page read was needed.
//   2. HardCilk register block: the first --reg-words 64-bit registers and the
//      reset GPIO, read through PF1 BAR2 (mmap) and through QDMA MM, compared.
//      Optionally a write/readback on a scratch register (--reg-write).
//      Only the decoded register blocks are ever read: HardCilk never answers
//      an access past HC_NUM_CONFIG_PORTS * 64 bytes, and on the V80 that hangs
//      the PCIe link and resets the host. The block count comes from the
//      generated FullSysGenDescriptor.h (the tool is built inside a generated
//      software tree), or --config-ports N. --reg-words defaults to all decoded
//      registers and larger values are refused unless --unsafe.
//   3. Throughput of one large H2C and one large C2H transfer.
//
// WARNING: overwrites the HBM it tests. Run it before the HardCilk host program,
// never during a run.
//
// Exit status 0 means every check passed.
#include <memIO_qdma.h>  // also pulls in the generated FullSysGenDescriptor.h when on the include path

#define HC_PROBE_STR2(x) #x
#define HC_PROBE_STR(x) HC_PROBE_STR2(x)

#include <chrono>
#include <cstdarg>
#include <memory>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

namespace {

int g_fail = 0;
int g_pass = 0;

void report(bool ok, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
void report(bool ok, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::printf("  [%s] ", ok ? " OK " : "FAIL");
    std::vprintf(fmt, ap);
    std::printf("\n");
    va_end(ap);
    (ok ? g_pass : g_fail)++;
}

std::vector<uint8_t> pattern(uint64_t addr, uint64_t n, uint32_t salt) {
    std::vector<uint8_t> v(n);
    std::mt19937_64 rng(addr ^ (uint64_t(salt) << 32) ^ 0x9E3779B97F4A7C15ULL);
    for (uint64_t i = 0; i < n; i += 8) {
        uint64_t r = rng();
        std::memcpy(v.data() + i, &r, std::min<uint64_t>(8, n - i));
    }
    return v;
}

// Write a pattern at addr, read it back, and compare. `exact` reads without
// widening (rawRead); otherwise copyFromDevice is used.
bool hbmCheck(QdmaMemory &m, uint64_t addr, uint64_t n, bool exact, uint32_t salt, const char *label) {
    auto w = pattern(addr, n, salt);
    std::vector<uint8_t> r(n, 0xEE);
    try {
        m.copyToDevice(addr, w.data(), n);
        // A striped bitstream permutes HBM: raw (physical) reads only match
        // logical writes through copyFromDevice.
        if (exact && m.stripeMode() == 0) m.rawRead(r.data(), addr, n);
        else m.copyFromDevice(r.data(), addr, n);
    } catch (const std::exception &e) {
        report(false, "%-34s 0x%011" PRIx64 " len %-9" PRIu64 " exception: %s", label, addr, n, e.what());
        return false;
    }
    uint64_t bad = 0, first = ~0ULL;
    for (uint64_t i = 0; i < n; i++)
        if (w[i] != r[i]) { bad++; if (first == ~0ULL) first = i; }
    if (bad)
        report(false, "%-34s 0x%011" PRIx64 " len %-9" PRIu64 " %" PRIu64 " bytes differ, first at +%" PRIu64
               " (wrote %02x read %02x)", label, addr, n, bad, first, w[first], r[first]);
    else
        report(true, "%-34s 0x%011" PRIx64 " len %" PRIu64, label, addr, n);
    return bad == 0;
}

// HBM striping host-path benchmark (any bitstream: physical raw transfers only).
// Compares, for n bytes at physical HBM `at`: one linear transfer; the same bytes
// as 4 KB requests (per-request cost of the software striping path's worst case);
// and the software striping path itself (hbm_stripe.h plan + staging gather, as
// QdmaMemory::stripedCopy does) for stripe4k and stripe4k_xor. Overwrites HBM in
// every pseudo channel around offset (at - base) / 32 (and [at, at + n)).
void stripeBench(QdmaMemory &m, uint64_t base, uint64_t at, uint64_t n) {
    using clk = std::chrono::steady_clock;
    auto secs = [](clk::time_point a, clk::time_point b) { return std::chrono::duration<double>(b - a).count(); };
    std::printf("\n== HBM striping host path (%" PRIu64 " MiB, logical +0x%" PRIx64 ")\n", n >> 20, at - base);
    auto w = pattern(at, n, 11);
    std::vector<uint8_t> r(n);
    auto t0 = clk::now();
    m.rawWrite(at, w.data(), n);
    auto t1 = clk::now();
    m.rawRead(r.data(), at, n);
    auto t2 = clk::now();
    std::printf("  linear      : H2C %6.2f GB/s  C2H %6.2f GB/s  (1 request)\n", n / secs(t0, t1) / 1e9,
                n / secs(t1, t2) / 1e9);
    uint64_t n4 = std::min<uint64_t>(n, 64ULL << 20);
    t0 = clk::now();
    for (uint64_t o = 0; o < n4; o += 4096) m.rawWrite(at + o, w.data() + o, 4096);
    t1 = clk::now();
    for (uint64_t o = 0; o < n4; o += 4096) m.rawRead(r.data() + o, at + o, 4096);
    t2 = clk::now();
    double k = double(n4 / 4096);
    std::printf("  4 KB reqs   : H2C %6.2f GB/s  C2H %6.2f GB/s  (%.1f / %.1f us per request, %" PRIu64 " MiB)\n",
                n4 / secs(t0, t1) / 1e9, n4 / secs(t1, t2) / 1e9, secs(t0, t1) / k * 1e6, secs(t1, t2) / k * 1e6,
                n4 >> 20);
    const uint64_t chunk = 4ULL << 20;
    std::vector<uint8_t> stage(chunk);
    for (int mode = 1; mode <= 2; ++mode) {
        std::vector<hc_stripe::Piece> pieces;
        std::vector<hc_stripe::Run> runs;
        auto tp = clk::now();
        hc_stripe::planStriped(at, n, mode, chunk, pieces, runs);
        auto tq = clk::now();
        for (const auto &ru : runs) {
            uint64_t o = 0;
            for (size_t i = 0; i < ru.count; ++i) {
                const auto &pc = pieces[ru.first + i];
                std::memcpy(stage.data() + o, w.data() + pc.hostOff, pc.len);
                o += pc.len;
            }
            m.rawWrite(ru.phys, stage.data(), ru.len);
        }
        auto tw = clk::now();
        for (const auto &ru : runs) {
            m.rawRead(stage.data(), ru.phys, ru.len);
            uint64_t o = 0;
            for (size_t i = 0; i < ru.count; ++i) {
                const auto &pc = pieces[ru.first + i];
                std::memcpy(r.data() + pc.hostOff, stage.data() + o, pc.len);
                o += pc.len;
            }
        }
        auto tr = clk::now();
        // Physical layout check: block b of the logical range sits at map(b).
        bool ok = r == w;
        std::vector<uint8_t> blk(4096);
        for (uint64_t o = 0; ok && o < n; o += n / 7 + 4096) {
            uint64_t a = (at + o) & ~4095ULL;
            if (a < at || a + 4096 > at + n) continue;
            m.rawRead(blk.data(), hc_stripe::map(a, mode), 4096);
            ok = std::memcmp(blk.data(), w.data() + (a - at), 4096) == 0;
        }
        report(ok, "stripe%s: H2C %6.2f GB/s  C2H %6.2f GB/s  (%zu requests, plan %.2f ms)",
               mode == 1 ? "4k    " : "4k_xor", n / secs(tq, tw) / 1e9, n / secs(tw, tr) / 1e9, runs.size(),
               secs(tp, tq) * 1e3);
    }
}

void usage(const char *argv0) {
    std::printf(
        "usage: %s [options]\n"
        "  --quick           fewer HBM points, smaller throughput test\n"
        "  --no-hbm          skip the HBM tests\n"
        "  --no-regs         skip the register tests\n"
        "  --reg-words N     64-bit registers to compare from offset 0 (default: every decoded\n"
        "                    register, HC_NUM_CONFIG_PORTS * 8; more is refused unless --unsafe)\n"
        "  --config-ports N  register blocks the loaded design decodes (default: from the\n"
        "                    generated FullSysGenDescriptor.h%s)\n"
        "  --unsafe          allow register offsets outside the decoded blocks (also sets\n"
        "                    HC_REG_UNSAFE=1). Can hang the PCIe link and reset the host on a\n"
        "                    bitstream without hc_axil_guard.\n"
        "  --reg-write OFF   write/readback test on the 64-bit register at OFF via BAR and DMA\n"
        "                    (e.g. 0x8 = raddr of the first server; harmless before a run)\n"
        "  --reset           pulse the HardCilk soft reset (0x8000: 0 then 1) first\n"
        "  --bw-mib N        throughput transfer size in MiB (default 256)\n"
        "  --stripe-bench    HBM striping host-path benchmark (linear vs 4 KB requests vs the\n"
        "                    software permutation; physical raw transfers, any bitstream)\n"
        "Environment: see driver/include/memIO_qdma.h (HC_QDMA_BDF, HC_QDMA_H2C, HC_QDMA_C2H,\n"
        "             HC_REG_ACCESS, HC_QDMA_CHUNK, HC_QDMA_READ_ALIGN, HC_REG_UNSAFE).\n",
        argv0,
#ifdef HC_NUM_CONFIG_PORTS
        ", built-in: " HC_PROBE_STR(HC_NUM_CONFIG_PORTS)
#else
        "; this build has none, so it is required for --reg-words/--reg-write"
#endif
    );
}

} // namespace

int main(int argc, char **argv) {
    bool quick = false, doHbm = true, doRegs = true, doReset = false;
    long long regWordsArg = -1;   // -1: every decoded register
    long long configPorts = -1;   // -1: from the generated header
    bool unsafe = false;
    long long regWrite = -1;
    uint64_t bwMiB = 256;
    bool doStripeBench = false;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        auto next = [&]() -> const char * {
            if (i + 1 >= argc) { usage(argv[0]); std::exit(2); }
            return argv[++i];
        };
        if (a == "--quick") quick = true;
        else if (a == "--no-hbm") doHbm = false;
        else if (a == "--no-regs") doRegs = false;
        else if (a == "--reset") doReset = true;
        else if (a == "--reg-words") regWordsArg = std::strtoll(next(), nullptr, 0);
        else if (a == "--config-ports") configPorts = std::strtoll(next(), nullptr, 0);
        else if (a == "--unsafe") unsafe = true;
        else if (a == "--reg-write") regWrite = std::strtoll(next(), nullptr, 0);
        else if (a == "--bw-mib") bwMiB = std::strtoull(next(), nullptr, 0);
        else if (a == "--stripe-bench") doStripeBench = true;
        else { usage(argv[0]); return a == "-h" || a == "--help" ? 0 : 2; }
    }

    // Decoded register range of the loaded design.
#ifdef HC_NUM_CONFIG_PORTS
    if (configPorts < 0) configPorts = HC_NUM_CONFIG_PORTS;
#endif
    if (configPorts > 64 * 1024 / 64) {
        std::fprintf(stderr, "hc_v80_probe: --config-ports %lld is larger than the register window\n", configPorts);
        return 2;
    }
    if (unsafe) setenv("HC_REG_UNSAFE", "1", 1);
    unsigned regWords = 0;
    if (doRegs) {
        if (configPorts < 0 && !unsafe) {
            if (regWordsArg > 0 || regWrite >= 0) {
                std::fprintf(stderr, "hc_v80_probe: this build does not know how many register blocks the design "
                                     "decodes; pass --config-ports N (HC_NUM_CONFIG_PORTS of the design), or build "
                                     "the tool inside the design's generated software tree.\n");
                return 2;
            }
            std::printf("[hc_v80_probe] decoded register range unknown (no --config-ports): only the reset GPIO is "
                        "read\n");
        } else {
            long long maxWords = configPorts >= 0 ? configPorts * 8 : 0;
            if (regWordsArg < 0) regWords = unsigned(maxWords);
            else if (regWordsArg > maxWords && !unsafe) {
                std::fprintf(stderr, "hc_v80_probe: --reg-words %lld reaches past the %lld decoded register block(s) "
                                     "(%lld words, offsets < 0x%llx). An undecoded register is never answered and "
                                     "can hang the PCIe link and reset the host; use --unsafe to force it.\n",
                             regWordsArg, configPorts, maxWords, maxWords * 8);
                return 2;
            } else regWords = unsigned(regWordsArg);
        }
    }

    std::unique_ptr<QdmaMemory> mp;
    try {
        mp.reset(new QdmaMemory());
        if (configPorts >= 0) mp->setRegDecodedBytes(uint64_t(configPorts) * 64);
    } catch (const std::exception &e) {
        std::fprintf(stderr, "hc_v80_probe: %s\n", e.what());
        return 1;
    }
    try {
    QdmaMemory &m = *mp;
    m.describe(stdout);
    const uint64_t B = m.hbmBase(), S = m.hbmSize();

    if (doReset) {
        std::printf("\n== soft reset (reg 0x8000 <- 0, then 1)\n");
        m.writeReg32(0x8000, 0);
        usleep(100000);
        m.writeReg32(0x8000, 1);
        usleep(100000);
    }

    if (doHbm) {
        std::printf("\n== HBM via QDMA MM (window 0x%" PRIx64 " + %" PRIu64 " GB)\n", B, S >> 30);
        hbmCheck(m, B, 64, true, 1, "start, 64 B aligned");
        hbmCheck(m, B + 0x1000, 4096, true, 1, "4 KB page");
        hbmCheck(m, B + 0x2000, 8, true, 1, "8 B aligned (exact)");
        hbmCheck(m, B + 0x2104, 4, true, 1, "4 B unaligned (exact)");
        hbmCheck(m, B + 0x2203, 3, true, 1, "3 B odd address (exact)");
        hbmCheck(m, B + 0x2304, 4, false, 2, "4 B unaligned (widened)");
        hbmCheck(m, B + 0x2FF0, 0x20, true, 1, "crosses 4 KB page (exact)");
        hbmCheck(m, B + 0x10000 + 13, (4ULL << 20) + 77, true, 1, "crosses 4 MiB chunk, unaligned");
        // Every pseudo channel: HBMc_PC0 = base + c*2 GB, PC1 = +1 GB.
        uint64_t step = quick ? (4ULL << 30) : (1ULL << 30);
        for (uint64_t o = step; o < S; o += step) {
            char l[64];
            std::snprintf(l, sizeof(l), "PC @ +%" PRIu64 " GB", o >> 30);
            hbmCheck(m, B + o + 0x40, 512, true, 3, l);
        }
        hbmCheck(m, B + (16ULL << 30) - 32, 64, true, 4, "straddles the 16 GB boundary");
        hbmCheck(m, B + S - 4096, 4096, true, 5, "last 4 KB of HBM");
        hbmCheck(m, B + S - 4, 4, true, 6, "last 4 B of HBM (exact)");

        // Earlier writes must survive later ones: re-read the first point.
        {
            auto w = pattern(B, 64, 1);
            std::vector<uint8_t> r(64);
            m.rawRead(r.data(), B, 64);
            report(std::memcmp(w.data(), r.data(), 64) == 0, "%-34s 0x%011" PRIx64, "start still intact", B);
        }

        uint64_t n = (quick ? 64ULL : bwMiB) << 20;
        std::printf("\n== throughput (%" PRIu64 " MiB at +1 GB)\n", n >> 20);
        auto w = pattern(B + (1ULL << 30), n, 7);
        std::vector<uint8_t> r(n);
        auto t0 = std::chrono::steady_clock::now();
        m.copyToDevice(B + (1ULL << 30), w.data(), n);
        auto t1 = std::chrono::steady_clock::now();
        m.copyFromDevice(r.data(), B + (1ULL << 30), n);
        auto t2 = std::chrono::steady_clock::now();
        double h = std::chrono::duration<double>(t1 - t0).count(), c = std::chrono::duration<double>(t2 - t1).count();
        std::printf("  H2C %.2f GB/s   C2H %.2f GB/s\n", n / h / 1e9, n / c / 1e9);
        report(w == r, "%-34s %" PRIu64 " MiB", "bulk readback", n >> 20);
        if (doStripeBench) stripeBench(m, B, B + (2ULL << 30), n);
    }

    if (doRegs) {
        std::printf("\n== HardCilk register block (AXI 0x%" PRIx64 ", %u decoded words%s)\n", m.regBase(),
                    unsigned(m.regDecodedBytes() / 8), unsafe ? ", --unsafe" : "");
        bool bar = m.barMapped();
        if (!bar) std::printf("  BAR2 not mapped: only the DMA path is shown\n");
        int mism = 0;
        for (unsigned i = 0; i < regWords; i++) {
            uint64_t off = 8ULL * i;
            uint64_t d = 0, b = 0;
            bool dok = true;
            try { d = m.readReg64Dma(off); } catch (const std::exception &e) {
                dok = false;
                std::printf("  +0x%03" PRIx64 "  DMA read failed: %s\n", off, e.what());
            }
            if (bar) b = m.readReg64Bar(off);
            bool same = !bar || !dok || b == d;
            if (!same) mism++;
            std::printf("  +0x%03" PRIx64 "  dma %016" PRIx64 "%s%s\n", off, d,
                        bar ? "  bar " : "", bar ? (same ? "(same)" : "MISMATCH") : "");
            if (bar && !same) std::printf("              bar %016" PRIx64 "\n", b);
        }
        if (bar) report(mism == 0, "BAR2 and DMA agree on %u registers", regWords);
        {
            uint32_t g = m.readReg32Dma(0x8000);
            if (bar) {
                uint32_t gb = m.readReg32Bar(0x8000);
                report(g == gb, "reset GPIO @0x8000: dma 0x%x bar 0x%x (1 = out of reset)", g, gb);
            } else {
                std::printf("  reset GPIO @0x8000: dma 0x%x (1 = out of reset)\n", g);
            }
        }
        if (regWrite >= 0) {
            uint64_t off = uint64_t(regWrite);
            uint64_t old = m.readReg64Dma(off);
            const uint64_t v1 = 0x00000ABCDEF01234ULL, v2 = 0x0000001122334455ULL;
            m.writeReg64Dma(off, v1);
            uint64_t r1 = bar ? m.readReg64Bar(off) : m.readReg64Dma(off);
            report(r1 == v1, "reg +0x%" PRIx64 ": DMA write 0x%" PRIx64 ", %s read 0x%" PRIx64, off, v1,
                   bar ? "BAR" : "DMA", r1);
            if (bar) {
                m.writeReg64Bar(off, v2);
                uint64_t r2 = m.readReg64Dma(off);
                report(r2 == v2, "reg +0x%" PRIx64 ": BAR write 0x%" PRIx64 ", DMA read 0x%" PRIx64, off, v2, r2);
            }
            m.writeReg64Dma(off, old);
        }
    }

    } catch (const std::exception &e) {
        std::printf("\nhc_v80_probe: aborted: %s\n", e.what());
        g_fail++;
    }

    std::printf("\n%d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
