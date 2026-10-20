#pragma once
// QdmaMemory: the Alveo V80 (CPM5 QDMA, AVED shell) host backend. It is a drop-in
// replacement for the U55C XDMA `PCIeMemory` (memIO_pcie.h) and implements the
// same `Memory` interface.
//
// Address model (V80 contract 1):
//   - copyToDevice/copyFromDevice take ABSOLUTE Versal AXI addresses. The QDMA MM
//     engine uses the file offset of a pread/pwrite on /dev/qdma<bdf>-MM-<q> as the
//     AXI address, so these addresses go to the device unchanged. allocateMemFPGA hands out
//     addresses in [hbmBase, hbmBase + hbmSize), 0x40_0000_0000 + 32 GB by default.
//   - Register accessors take offsets relative to the HardCilk management slave,
//     exactly as on U55C: regs at 0, soft-reset GPIO at 0x8000. The slave is at
//     AXI 0x202_0000_0000 (= regBase), which is also PF1 BAR2 offset 0.
//
// Register path, selected with HC_REG_ACCESS:
//   bar (default)  mmap of /sys/bus/pci/devices/<PF1>/resource2, a 64 KB window.
//                  Accesses are single 4- or 8-byte volatile loads and stores.
//   dma            4- or 8-byte QDMA MM transfers at regBase + off, which is what
//                  the original working V80 driver did. Slower, but needs no BAR.
//   If the BAR cannot be mapped (missing, too small, or no permission), the
//   default mode falls back to dma with a warning. HC_REG_ACCESS=bar makes that a
//   hard error instead.
//
// Environment:
//   HC_QDMA_BDF        PF1 BDF, e.g. 71:00.1 or 0000:71:00.1. By default sysfs is
//                      scanned for 10ee:50b5.
//   HC_QDMA_H2C        H2C (write) queue char device. Default
//                      /dev/qdma<bdf>-MM-<HC_QDMA_QUEUE>.
//   HC_QDMA_C2H        C2H (read) queue char device. Default is the same node: one
//                      bidirectional MM queue serves both directions (dma-ctl
//                      "q add idx 0 mode mm dir bi"), and the driver creates a
//                      single cdev per queue pair.
//   HC_QDMA_QUEUE      queue index used for the default names (default 0)
//   HC_QDMA_BAR        register BAR file (default /sys/bus/pci/devices/<PF1>/resource2)
//   HC_REG_ACCESS      bar | dma (see above)
//   HC_QDMA_CHUNK      largest single DMA request in bytes (default 4 MiB, see
//                      kMaxChunk)
//   HC_QDMA_READ_ALIGN reads of HBM are widened to this power-of-two alignment
//                      (default 64; 1 disables). Widening only reads extra bytes
//                      and never writes them back.
//   HC_QDMA_NO_RANGE_CHECK=1  allow copies outside the HBM window, e.g. into the
//                      register block (register-window bytes are still checked
//                      as below)
//   HC_REG_UNSAFE=1    allow register accesses outside the decoded range (below)
//   HC_QDMA_VERBOSE=1  print the resolved configuration
//
// Decoded register range: HardCilk decodes only HC_NUM_CONFIG_PORTS 64-byte
// register blocks, offsets [0, HC_REG_DECODED_BYTES), and never answers an access
// to any other offset. On the V80 such an access stalls the CPM AXI bridge; the
// PCIe completion timeout that follows is fatal and resets the host. (Bitstreams
// generated with hc_axil_guard answer it with SLVERR instead; older ones hang.)
// Every register accessor therefore refuses offsets outside
//   [0, HC_REG_DECODED_BYTES)  and  the reset GPIO [0x8000, 0x9000)
// with a std::runtime_error, unless HC_REG_UNSAFE=1. The range comes from the
// generated FullSysGenDescriptor.h (included here when it is on the include
// path), the constructor's reg_decoded_bytes argument, or setRegDecodedBytes().
// When it is unknown only the reset GPIO is accessible.
//
// HBM striping (HC_HBM_STRIPING=stripe4k|stripe4k_xor bitstreams, see hbm_stripe.h):
// the PEs' addresses pass through hc_axi_stripe, the QDMA does not. The generated
// header says how the host must lay data out:
//   HC_HBM_STRIPE_MODE 1|2 and no HC_HBM_HOST_ALIAS_BASE: copyToDevice/
//     copyFromDevice apply the same 4 KB permutation (one DMA per physically
//     contiguous run, i.e. ~one per pseudo channel for a large copy, gathered/
//     scattered through a staging buffer of HC_QDMA_CHUNK bytes).
//   HC_HBM_HOST_ALIAS_BASE: the bitstream routes host HBM traffic through its own
//     PL hc_axi_stripe at that alias window; copies only rebase the address.
//   HC_HBM_STRIPE_MODE=<n> (environment) overrides the header (debug only: a
//   mismatch with the bitstream silently scrambles HBM).
// rawRead/rawWrite always use physical addresses.

#ifndef _FILE_OFFSET_BITS
#define _FILE_OFFSET_BITS 64
#endif

#include <memIO.h>

#if defined(__has_include)
#if __has_include(<FullSysGenDescriptor.h>)
#include <FullSysGenDescriptor.h>  // HC_REG_DECODED_BYTES (V80 headers)
#endif
#endif
#ifndef HC_HBM_STRIPE_MODE
#define HC_HBM_STRIPE_MODE 0
#endif
#ifndef HC_HBM_HOST_ALIAS_BASE
#define HC_HBM_HOST_ALIAS_BASE 0
#endif
#include <hbm_stripe.h>
#ifdef HC_REG_DECODED_BYTES
#define HC_QDMA_REG_DECODED_BYTES_DEFAULT (HC_REG_DECODED_BYTES)
#else
#define HC_QDMA_REG_DECODED_BYTES_DEFAULT 0
#endif

#include <algorithm>
#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace hc_qdma {

// V80 contract 1. A generated V80 FullSysGenDescriptor.h reports the same values
// through getBoardHbmBaseAddress() / getBoardHbmSizeBytes() / getBoardRegBaseAddress().
// v80_main.cpp passes those explicitly, and these are the defaults.
constexpr uint64_t kV80HbmBase = 0x4000000000ULL;   // 0x40_0000_0000
constexpr uint64_t kV80HbmSize = 32ULL << 30;       // 32 GB
constexpr uint64_t kV80RegBase = 0x20200000000ULL;  // 0x202_0000_0000
constexpr uint64_t kRegWindow  = 64ULL << 10;       // PF1 BAR2 aperture
constexpr uint64_t kResetGpioOff  = 0x8000;         // soft-reset AXI GPIO (always accessible)
constexpr uint64_t kResetGpioSize = 0x1000;
constexpr uint16_t kQdmaVendor = 0x10ee;
constexpr uint16_t kQdmaDevice = 0x50b5;            // V80 PF1 (QDMA)

// Largest single DMA request. qdma_request.count is a 32-bit field, so 4 GiB is a
// hard limit. Each request is also pinned through a kmalloc'ed scatter list
// (~48 bytes per page, physically contiguous), and it must finish inside the
// driver's 10 s request timeout. 4 MiB keeps that list at ~48 KB and costs
// nothing measurable in throughput.
constexpr uint64_t kDefaultChunk = 4ULL << 20;
constexpr uint64_t kMaxChunk     = 1ULL << 30;

// Transfers up to this size are staged through a pinned, page-aligned buffer:
// one copy is cheaper than pinning the user pages. Staging also avoids an EFAULT
// on H2C. The driver pins every user buffer with FOLL_WRITE, even on H2C, so a
// write from read-only memory (string literals, const statics) would otherwise
// fail.
constexpr uint64_t kBounceThreshold = 64ULL << 10;

inline std::string envOr(const char *name, const std::string &dflt) {
    const char *v = std::getenv(name);
    return (v && *v) ? std::string(v) : dflt;
}

inline uint64_t envU64(const char *name, uint64_t dflt) {
    const char *v = std::getenv(name);
    if (!v || !*v) return dflt;
    char *end = nullptr;
    unsigned long long x = std::strtoull(v, &end, 0);
    if (end == v || *end != '\0')
        throw std::runtime_error(std::string("QdmaMemory: cannot parse ") + name + "='" + v + "'");
    return x;
}

inline bool readSysfsHex(const std::string &path, unsigned long &out) {
    FILE *f = std::fopen(path.c_str(), "r");
    if (!f) return false;
    char buf[64] = {0};
    bool ok = std::fgets(buf, sizeof(buf), f) != nullptr;
    std::fclose(f);
    if (!ok) return false;
    out = std::strtoul(buf, nullptr, 0);
    return true;
}

struct Bdf {
    unsigned domain = 0, bus = 0, dev = 0, fn = 0;
    std::string str() const {
        char b[32];
        std::snprintf(b, sizeof(b), "%04x:%02x:%02x.%x", domain, bus, dev, fn);
        return b;
    }
};

// Accepts "71:00.1", "0000:71:00.1", "71001" (the QDMA driver's own idx form).
inline Bdf parseBdf(const std::string &s) {
    Bdf b;
    unsigned d, bu, de, f;
    if (std::sscanf(s.c_str(), "%x:%x:%x.%x", &d, &bu, &de, &f) == 4) {
        b.domain = d; b.bus = bu; b.dev = de; b.fn = f; return b;
    }
    if (std::sscanf(s.c_str(), "%x:%x.%x", &bu, &de, &f) == 3) {
        b.bus = bu; b.dev = de; b.fn = f; return b;
    }
    if (s.size() == 5 && s.find_first_not_of("0123456789abcdefABCDEF") == std::string::npos) {
        unsigned long v = std::strtoul(s.c_str(), nullptr, 16);
        b.bus = (v >> 12) & 0xff; b.dev = (v >> 4) & 0xff; b.fn = v & 0xf; return b;
    }
    throw std::runtime_error("QdmaMemory: cannot parse PCI BDF '" + s + "'");
}

// Every PCI function with the given IDs, in sysfs order.
inline std::vector<Bdf> findPciFunctions(uint16_t vendor, uint16_t device) {
    std::vector<Bdf> out;
    DIR *d = opendir("/sys/bus/pci/devices");
    if (!d) return out;
    std::vector<std::string> names;
    while (dirent *e = readdir(d)) {
        if (e->d_name[0] == '.') continue;
        names.emplace_back(e->d_name);
    }
    closedir(d);
    std::sort(names.begin(), names.end());
    for (auto &n : names) {
        unsigned long v = 0, dv = 0;
        std::string base = "/sys/bus/pci/devices/" + n;
        if (readSysfsHex(base + "/vendor", v) && readSysfsHex(base + "/device", dv) &&
            v == vendor && dv == device)
            out.push_back(parseBdf(n));
    }
    return out;
}

// The QDMA driver names its devices "qdma%05x" over the value
//   (bus << 12) | (slot << 4) | func, plus (domain << 20) only on kernels built with
//   CONFIG_PCI_DOMAINS_GENERIC (not x86).
// See libqdma/xdev.c:xdev_list_add (PCI_SHIFT_* in libqdma_config.h), and
// qdma_descq.c ("%05x-%s-%u", bdf, "MM"/"ST", qidx) for queue nodes. The value is
// lower-case hex, zero-padded to at least 5 digits: 71:00.1 -> qdma71001, and
// 05:00.1 -> qdma05001.
inline std::vector<std::string> qdmaDevNames(const Bdf &b) {
    std::vector<std::string> v;
    unsigned idx = (b.bus << 12) | (b.dev << 4) | b.fn;
    char buf[32];
    std::snprintf(buf, sizeof(buf), "qdma%05x", idx);
    v.emplace_back(buf);
    if (b.domain) {
        std::snprintf(buf, sizeof(buf), "qdma%05x", idx | (b.domain << 20));
        v.emplace_back(buf);
    }
    return v;
}

inline bool fileExists(const std::string &p) {
    struct stat st;
    return ::stat(p.c_str(), &st) == 0;
}

static_assert(sizeof(off_t) == 8, "QdmaMemory needs 64-bit file offsets (AXI addresses above 4 GB)");

/** A failed pread/pwrite; `err` is the errno it failed with. */
struct DmaError : std::runtime_error {
    int err;
    DmaError(const std::string &m, int e) : std::runtime_error(m), err(e) {}
};

} // namespace hc_qdma

struct QdmaMemory : Memory {
    enum class RegMode { Bar, Dma };

    /**
     * @param hbm_base  first HBM byte (absolute AXI address)
     * @param hbm_size  HBM bytes the allocator may hand out
     * @param reg_base  absolute AXI address of the HardCilk management slave
     *                  (only used by the DMA register path; the BAR maps it at 0)
     * @param reg_decoded_bytes  register offsets [0, this) are decoded by the
     *                  design (HC_NUM_CONFIG_PORTS * 64); 0 = unknown, only the
     *                  reset GPIO is accessible. Defaults to the generated header's
     *                  HC_REG_DECODED_BYTES when that header is on the include path.
     */
    explicit QdmaMemory(uint64_t hbm_base = hc_qdma::kV80HbmBase,
                        uint64_t hbm_size = hc_qdma::kV80HbmSize,
                        uint64_t reg_base = hc_qdma::kV80RegBase,
                        uint64_t reg_decoded_bytes = HC_QDMA_REG_DECODED_BYTES_DEFAULT)
        : hbm_base_(hbm_base), hbm_size_(hbm_size), reg_base_(reg_base),
          reg_decoded_bytes_(reg_decoded_bytes), free_mem_base_addr(hbm_base) {
        using namespace hc_qdma;
        verbose_ = envOr("HC_QDMA_VERBOSE", "0") != "0";
        reg_unsafe_ = envOr("HC_REG_UNSAFE", "0") != "0";
        if (reg_unsafe_)
            std::fprintf(stderr, "[QdmaMemory] WARNING: HC_REG_UNSAFE=1: register offsets are not checked. An "
                                 "access outside the decoded register blocks can hang the PCIe link and reset "
                                 "the host on a bitstream without hc_axil_guard.\n");
        range_check_ = envOr("HC_QDMA_NO_RANGE_CHECK", "0") == "0";
        chunk_ = envU64("HC_QDMA_CHUNK", kDefaultChunk);
        if (chunk_ == 0 || chunk_ > kMaxChunk)
            throw std::runtime_error("QdmaMemory: HC_QDMA_CHUNK must be in (0, 1 GiB]");
        read_align_ = envU64("HC_QDMA_READ_ALIGN", 64);
        stripe_mode_ = static_cast<int>(envU64("HC_HBM_STRIPE_MODE", HC_HBM_STRIPE_MODE));
        if (stripe_mode_ < 0 || stripe_mode_ > 2)
            throw std::runtime_error("QdmaMemory: HC_HBM_STRIPE_MODE must be 0, 1 or 2");
        if (stripe_mode_ != HC_HBM_STRIPE_MODE)
            std::fprintf(stderr, "[QdmaMemory] WARNING: HC_HBM_STRIPE_MODE=%d overrides the bitstream's %d\n",
                         stripe_mode_, HC_HBM_STRIPE_MODE);
        host_alias_ = HC_HBM_HOST_ALIAS_BASE;
        if (read_align_ == 0 || (read_align_ & (read_align_ - 1)) || read_align_ > kBounceThreshold)
            throw std::runtime_error("QdmaMemory: HC_QDMA_READ_ALIGN must be a power of two <= 64 KiB");

        resolveDevice();
        openQueues();

        bounce_size_ = std::max<uint64_t>(kBounceThreshold + 2 * read_align_, 4096);
        bounce_size_ = (bounce_size_ + 4095) & ~uint64_t(4095);
        if (posix_memalign(&bounce_, 4096, bounce_size_) != 0)
            throw std::runtime_error("QdmaMemory: cannot allocate the bounce buffer");
        std::memset(bounce_, 0, bounce_size_);

        std::string mode = envOr("HC_REG_ACCESS", "auto");
        if (mode == "dma") {
            reg_mode_ = RegMode::Dma;
        } else if (mode == "bar" || mode == "auto") {
            std::string why;
            if (mapBar(why)) {
                reg_mode_ = RegMode::Bar;
            } else if (mode == "bar") {
                cleanup();
                throw std::runtime_error("QdmaMemory: HC_REG_ACCESS=bar but " + why);
            } else {
                std::fprintf(stderr, "[QdmaMemory] warning: %s; using QDMA MM transfers for registers "
                                     "(HC_REG_ACCESS=dma)\n", why.c_str());
                reg_mode_ = RegMode::Dma;
            }
        } else {
            cleanup();
            throw std::runtime_error("QdmaMemory: HC_REG_ACCESS must be 'bar' or 'dma', got '" + mode + "'");
        }

        if (verbose_) describe(stderr);
    }

    QdmaMemory(const QdmaMemory &) = delete;
    QdmaMemory &operator=(const QdmaMemory &) = delete;

    ~QdmaMemory() override { cleanup(); }

    // ---- bulk data ---------------------------------------------------------

    void copyToDevice(uint64_t dest_addr, uint8_t const *src, uint64_t size) override {
        if (size == 0) return;
        checkRange("copyToDevice", dest_addr, size);
        checkRegOverlap("copyToDevice", dest_addr, size);
        if (stripe_mode_ != 0 && host_alias_ != 0) {
            dmaWrite(dest_addr - hbm_base_ + host_alias_, src, size);
        } else if (stripe_mode_ != 0) {
            stripedCopy(true, dest_addr, const_cast<uint8_t *>(src), size);
        } else {
            dmaWrite(dest_addr, src, size);
        }
    }

    void copyFromDevice(uint8_t *dest, uint64_t src_addr, uint64_t size) override {
        if (size == 0) return;
        checkRange("copyFromDevice", src_addr, size);
        checkRegOverlap("copyFromDevice", src_addr, size);
        if (stripe_mode_ != 0 && host_alias_ != 0) {
            readHbm(dest, src_addr - hbm_base_ + host_alias_, size, host_alias_);
        } else if (stripe_mode_ != 0) {
            stripedCopy(false, src_addr, dest, size);
        } else {
            readHbm(dest, src_addr, size, hbm_base_);
        }
    }

    /** HBM striping mode applied by copyToDevice/copyFromDevice (0 = none). */
    int stripeMode() const { return stripe_mode_; }

  private:
    // A read of [src_addr, +size) in a window starting at `base` (HBM or its
    // alias): small or unaligned reads are widened to read_align_ (reads have
    // no side effects on HBM) and staged through the bounce buffer.
    void readHbm(uint8_t *dest, uint64_t src_addr, uint64_t size, uint64_t base) {
        uint64_t mask = read_align_ - 1;
        uint64_t lo = src_addr & ~mask;
        uint64_t hi = (src_addr + size + mask) & ~mask;
        if (read_align_ > 1 && (lo != src_addr || hi != src_addr + size) && (hi - lo) <= hc_qdma::kBounceThreshold &&
            lo >= base && hi <= base + hbm_size_) {
            // Small or unaligned read of HBM: read the enclosing aligned window,
            // copy out the requested bytes. Reads have no side effects on HBM.
            std::lock_guard<std::mutex> g(bounce_mu_);
            dmaReadExact(static_cast<uint8_t *>(bounce_), lo, hi - lo);
            std::memcpy(dest, static_cast<uint8_t *>(bounce_) + (src_addr - lo), size);
            return;
        }
        dmaRead(dest, src_addr, size);
    }

    // Logical HBM copy on a striped bitstream: one DMA per physically
    // contiguous run (hbm_stripe.h); runs whose pieces are not consecutive in
    // the host buffer go through the staging buffer. Widening never leaves a
    // run's first/last 4 KB block, so it stays inside one physical block.
    void stripedCopy(bool write, uint64_t addr, uint8_t *host, uint64_t size) {
        std::lock_guard<std::mutex> g(stripe_mu_);
        hc_stripe::planStriped(addr, size, stripe_mode_, chunk_, stripe_pieces_, stripe_runs_);
        stripe_stats_runs_ += stripe_runs_.size();
        stripe_stats_pieces_ += stripe_pieces_.size();
        for (const hc_stripe::Run &r : stripe_runs_) {
            const hc_stripe::Piece &p0 = stripe_pieces_[r.first];
            if (hc_stripe::runHostContiguous(stripe_pieces_, r)) {
                if (write) dmaWrite(r.phys, host + p0.hostOff, r.len);
                else readHbm(host + p0.hostOff, r.phys, r.len, hbm_base_);
                continue;
            }
            if (!stage_) {
                if (posix_memalign(&stage_, 4096, chunk_) != 0) {
                    stage_ = nullptr;
                    throw std::runtime_error("QdmaMemory: cannot allocate the striping staging buffer");
                }
            }
            uint8_t *st = static_cast<uint8_t *>(stage_);
            if (write) {
                uint64_t o = 0;
                for (size_t k = 0; k < r.count; ++k) {
                    const hc_stripe::Piece &p = stripe_pieces_[r.first + k];
                    std::memcpy(st + o, host + p.hostOff, p.len);
                    o += p.len;
                }
                dmaWrite(r.phys, st, r.len);
            } else {
                readHbm(st, r.phys, r.len, hbm_base_);
                uint64_t o = 0;
                for (size_t k = 0; k < r.count; ++k) {
                    const hc_stripe::Piece &p = stripe_pieces_[r.first + k];
                    std::memcpy(host + p.hostOff, st + o, p.len);
                    o += p.len;
                }
            }
        }
    }

  public:
    /** DMA requests / 4 KB pieces issued by striped copies so far. */
    uint64_t stripeRuns() const { return stripe_stats_runs_; }
    uint64_t stripePieces() const { return stripe_stats_pieces_; }

    /** copyFromDevice without HBM read widening or the range check (for the probe tool). */
    void rawRead(uint8_t *dest, uint64_t addr, uint64_t size) {
        checkRegOverlap("rawRead", addr, size);
        dmaRead(dest, addr, size);
    }
    /** copyToDevice without the HBM range check (for the probe tool). */
    void rawWrite(uint64_t addr, uint8_t const *src, uint64_t size) {
        checkRegOverlap("rawWrite", addr, size);
        dmaWrite(addr, src, size);
    }

    // ---- registers ---------------------------------------------------------

    void writeReg32(uint64_t addr, uint32_t value) override {
        if (reg_mode_ == RegMode::Bar) { *barPtr<uint32_t>(addr) = value; return; }
        dmaRegWrite(addr, &value, sizeof(value));
    }
    void writeReg64(uint64_t addr, uint64_t value) override {
        if (reg_mode_ == RegMode::Bar) { *barPtr<uint64_t>(addr) = value; return; }
        dmaRegWrite(addr, &value, sizeof(value));
    }
    uint32_t readReg32(uint64_t addr) override {
        if (reg_mode_ == RegMode::Bar) return *barPtr<uint32_t>(addr);
        uint32_t v = 0; dmaRegRead(addr, &v, sizeof(v)); return v;
    }
    uint64_t readReg64(uint64_t addr) override {
        if (reg_mode_ == RegMode::Bar) return *barPtr<uint64_t>(addr);
        uint64_t v = 0; dmaRegRead(addr, &v, sizeof(v)); return v;
    }

    // Explicit-path accessors (probe tool, debugging). Throw if the BAR is not mapped.
    uint64_t readReg64Bar(uint64_t off) { requireBar(); return *barPtr<uint64_t>(off); }
    uint32_t readReg32Bar(uint64_t off) { requireBar(); return *barPtr<uint32_t>(off); }
    void writeReg64Bar(uint64_t off, uint64_t v) { requireBar(); *barPtr<uint64_t>(off) = v; }
    uint64_t readReg64Dma(uint64_t off) { uint64_t v = 0; dmaRegRead(off, &v, 8); return v; }
    uint32_t readReg32Dma(uint64_t off) { uint32_t v = 0; dmaRegRead(off, &v, 4); return v; }
    void writeReg64Dma(uint64_t off, uint64_t v) { dmaRegWrite(off, &v, 8); }
    bool barMapped() const { return bar_ != nullptr; }
    RegMode regMode() const { return reg_mode_; }

    /** Register offsets [0, n) are decoded by the loaded design (HC_NUM_CONFIG_PORTS * 64). */
    void setRegDecodedBytes(uint64_t n) { reg_decoded_bytes_ = n; }
    uint64_t regDecodedBytes() const { return reg_decoded_bytes_; }
    bool regUnsafe() const { return reg_unsafe_; }
    /** True if a `size`-byte register access at `off` passes the decoded-range check. */
    bool regAccessAllowed(uint64_t off, uint64_t size) const {
        if (reg_unsafe_) return true;
        if (off >= hc_qdma::kResetGpioOff && size <= hc_qdma::kResetGpioSize &&
            off - hc_qdma::kResetGpioOff <= hc_qdma::kResetGpioSize - size)
            return true;
        return size <= reg_decoded_bytes_ && off <= reg_decoded_bytes_ - size;
    }

    // ---- allocator ---------------------------------------------------------
    // Same semantics as the U55C PCIeMemory allocator: first-fit reuse of
    // freed_mem_blocks (with alignment), otherwise an aligned bump from the
    // start of HBM. The bump ceiling is the HBM window rather than an assert(),
    // so a too-large request fails in release builds as well.

    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment /** bytes */) override {
        if (alignment == 0) alignment = 1;
        for (auto it = freed_mem_blocks.begin(); it != freed_mem_blocks.end(); it++) {
            if (it->size >= size) {
                auto reminder = it->addr % alignment;
                auto offset = (reminder == 0) ? 0 : alignment - reminder;
                if (it->size - offset < size) continue;
                uint64_t addr = it->addr + offset;
                it->addr += (size + offset);
                it->size -= (size + offset);
                if (it->size == 0) freed_mem_blocks.erase(it);
                return addr;
            }
        }
        auto reminder = free_mem_base_addr % alignment;
        auto offset = (reminder == 0) ? 0 : alignment - reminder;
        uint64_t addr = free_mem_base_addr + offset;
        uint64_t end = addr + size;
        if (end < addr || end > hbm_base_ + hbm_size_) {
            char b[256];
            std::snprintf(b, sizeof(b),
                          "QdmaMemory::allocateMemFPGA: out of HBM (request 0x%llx bytes, align %llu; "
                          "used 0x%llx of 0x%llx)",
                          (unsigned long long)size, (unsigned long long)alignment,
                          (unsigned long long)(free_mem_base_addr - hbm_base_),
                          (unsigned long long)hbm_size_);
            throw std::runtime_error(b);
        }
        free_mem_base_addr = end;
        for (auto &pair : trackMalloc)
            if (addr >= pair.first && addr < pair.second)
                throw std::runtime_error("QdmaMemory::allocateMemFPGA: invalid allocation (overlap)");
        trackMalloc.push_back(std::pair<uint64_t, uint64_t>(addr, end));
        return addr;
    }

    /** Bytes handed out by the bump allocator so far. */
    uint64_t bytesAllocated() const { return free_mem_base_addr - hbm_base_; }

    // ---- info --------------------------------------------------------------
    const std::string &bdf() const { return bdf_; }
    const std::string &h2cPath() const { return h2c_path_; }
    const std::string &c2hPath() const { return c2h_path_; }
    uint64_t hbmBase() const { return hbm_base_; }
    uint64_t hbmSize() const { return hbm_size_; }
    uint64_t regBase() const { return reg_base_; }

    void describe(FILE *f) const {
        std::fprintf(f,
                     "[QdmaMemory] PF1 %s  H2C %s  C2H %s\n"
                     "[QdmaMemory] HBM 0x%llx +0x%llx  regs 0x%llx via %s%s  chunk %llu  read-align %llu\n"
                     "[QdmaMemory] decoded registers [0, 0x%llx)%s, reset GPIO [0x8000, 0x9000)\n",
                     bdf_.c_str(), h2c_path_.c_str(), c2h_path_.c_str(),
                     (unsigned long long)hbm_base_, (unsigned long long)hbm_size_,
                     (unsigned long long)reg_base_, reg_mode_ == RegMode::Bar ? "BAR2 mmap " : "QDMA MM",
                     reg_mode_ == RegMode::Bar ? bar_path_.c_str() : "",
                     (unsigned long long)chunk_, (unsigned long long)read_align_,
                     (unsigned long long)reg_decoded_bytes_,
                     reg_unsafe_ ? " (NOT ENFORCED: HC_REG_UNSAFE=1)" : (reg_decoded_bytes_ ? "" : " (unknown)"));
        if (stripe_mode_ != 0)
            std::fprintf(f, "[QdmaMemory] HBM striping mode %d (%s), host path: %s 0x%llx\n", stripe_mode_,
                         stripe_mode_ == 1 ? "stripe4k" : "stripe4k_xor",
                         host_alias_ ? "PL alias window" : "software permutation",
                         (unsigned long long)host_alias_);
    }

  private:
    uint64_t hbm_base_, hbm_size_, reg_base_;
    uint64_t reg_decoded_bytes_ = 0;
    bool reg_unsafe_ = false;
    uint64_t free_mem_base_addr;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;

    std::string bdf_, h2c_path_, c2h_path_, bar_path_;
    int h2c_fd_ = -1, c2h_fd_ = -1;
    volatile uint8_t *bar_ = nullptr;
    size_t bar_len_ = 0;
    RegMode reg_mode_ = RegMode::Dma;
    uint64_t chunk_ = hc_qdma::kDefaultChunk;
    uint64_t read_align_ = 64;
    bool verbose_ = false, range_check_ = true;
    void *bounce_ = nullptr;
    uint64_t bounce_size_ = 0;
    std::mutex bounce_mu_;
    // HBM striping (see the header comment).
    int stripe_mode_ = 0;
    uint64_t host_alias_ = 0;
    void *stage_ = nullptr;
    std::mutex stripe_mu_;
    std::vector<hc_stripe::Piece> stripe_pieces_;
    std::vector<hc_stripe::Run> stripe_runs_;
    uint64_t stripe_stats_runs_ = 0, stripe_stats_pieces_ = 0;

    void cleanup() {
        if (bar_) { munmap(const_cast<uint8_t *>(bar_), bar_len_); bar_ = nullptr; }
        if (c2h_fd_ >= 0 && c2h_fd_ != h2c_fd_) ::close(c2h_fd_);
        if (h2c_fd_ >= 0) ::close(h2c_fd_);
        h2c_fd_ = c2h_fd_ = -1;
        std::free(bounce_);
        bounce_ = nullptr;
        std::free(stage_);
        stage_ = nullptr;
    }

    static std::string errnoStr() { return std::strerror(errno); }

    void resolveDevice() {
        using namespace hc_qdma;
        hc_qdma::Bdf b;
        std::string env = envOr("HC_QDMA_BDF", "");
        if (!env.empty()) {
            b = parseBdf(env);
        } else {
            auto found = findPciFunctions(kQdmaVendor, kQdmaDevice);
            if (found.empty())
                throw std::runtime_error(
                    "QdmaMemory: no 10ee:50b5 (V80 QDMA PF1) function in /sys/bus/pci/devices. Is a "
                    "HardCilk V80 image loaded? Run software/scripts/v80_bringup.sh (as root), or set HC_QDMA_BDF.");
            if (found.size() > 1)
                std::fprintf(stderr, "[QdmaMemory] warning: %zu V80 QDMA functions found, using %s "
                                     "(set HC_QDMA_BDF to choose)\n", found.size(), found[0].str().c_str());
            b = found[0];
        }
        bdf_ = b.str();

        std::string q = envOr("HC_QDMA_QUEUE", "0");
        std::string dflt;
        for (auto &n : qdmaDevNames(b)) {
            std::string p = "/dev/" + n + "-MM-" + q;
            if (dflt.empty()) dflt = p;
            if (fileExists(p)) { dflt = p; break; }
        }
        h2c_path_ = envOr("HC_QDMA_H2C", dflt);
        c2h_path_ = envOr("HC_QDMA_C2H", h2c_path_);
        bar_path_ = envOr("HC_QDMA_BAR", "/sys/bus/pci/devices/" + bdf_ + "/resource2");
    }

    void openQueues() {
        auto hint = [&](const std::string &p) {
            return "QdmaMemory: cannot open " + p + ": " + errnoStr() +
                   ". Is the MM queue added and started (software/scripts/v80_bringup.sh), and readable by "
                   "this user? Override with HC_QDMA_H2C / HC_QDMA_C2H.";
        };
        if (h2c_path_ == c2h_path_) {
            h2c_fd_ = ::open(h2c_path_.c_str(), O_RDWR);
            if (h2c_fd_ < 0) throw std::runtime_error(hint(h2c_path_));
            c2h_fd_ = h2c_fd_;
        } else {
            h2c_fd_ = ::open(h2c_path_.c_str(), O_WRONLY);
            if (h2c_fd_ < 0) throw std::runtime_error(hint(h2c_path_));
            c2h_fd_ = ::open(c2h_path_.c_str(), O_RDONLY);
            if (c2h_fd_ < 0) {
                std::string m = hint(c2h_path_);
                ::close(h2c_fd_); h2c_fd_ = -1;
                throw std::runtime_error(m);
            }
        }
    }

    bool mapBar(std::string &why) {
        int fd = ::open(bar_path_.c_str(), O_RDWR | O_SYNC);
        if (fd < 0) { why = "cannot open " + bar_path_ + ": " + errnoStr(); return false; }
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size < (off_t)hc_qdma::kRegWindow) {
            why = bar_path_ + " is smaller than 64 KB (is BAR2 the AXI bridge?)";
            ::close(fd);
            return false;
        }
        bar_len_ = hc_qdma::kRegWindow;
        void *p = mmap(nullptr, bar_len_, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        ::close(fd);
        if (p == MAP_FAILED) { why = "mmap " + bar_path_ + ": " + errnoStr(); return false; }
        bar_ = static_cast<volatile uint8_t *>(p);
        return true;
    }

    void requireBar() const {
        if (!bar_) throw std::runtime_error("QdmaMemory: BAR2 is not mapped (" + bar_path_ + ")");
    }

    // Throws unless a register access of `n` bytes at offset `off` is in the
    // decoded register blocks or the reset GPIO (see the header comment).
    void checkRegAccess(const char *what, uint64_t off, uint64_t n) const {
        if (regAccessAllowed(off, n)) return;
        char b[640];
        if (reg_decoded_bytes_ == 0)
            std::snprintf(b, sizeof(b),
                          "QdmaMemory: refusing %s of %llu byte(s) at register offset 0x%llx: the design's decoded "
                          "register range is unknown (FullSysGenDescriptor.h with HC_REG_DECODED_BYTES not on the "
                          "include path; pass reg_decoded_bytes to the constructor or call setRegDecodedBytes). "
                          "HC_REG_UNSAFE=1 overrides.",
                          what, (unsigned long long)n, (unsigned long long)off);
        else
            std::snprintf(b, sizeof(b),
                          "QdmaMemory: refusing %s of %llu byte(s) at register offset 0x%llx: HardCilk decodes only "
                          "[0, 0x%llx) (%llu register blocks of 64 B) and the reset GPIO [0x8000, 0x9000). It never "
                          "answers other offsets, which can hang the PCIe link and reset the host. HC_REG_UNSAFE=1 "
                          "overrides.",
                          what, (unsigned long long)n, (unsigned long long)off,
                          (unsigned long long)reg_decoded_bytes_, (unsigned long long)(reg_decoded_bytes_ / 64));
        throw std::runtime_error(b);
    }

    // A raw DMA that overlaps the 64 KB register window is held to the same rule.
    void checkRegOverlap(const char *what, uint64_t addr, uint64_t size) const {
        uint64_t lo = std::max(addr, reg_base_);
        uint64_t hi = std::min(addr + size, reg_base_ + hc_qdma::kRegWindow);
        if (lo < hi) checkRegAccess(what, lo - reg_base_, hi - lo);
    }

    template <typename T>
    volatile T *barPtr(uint64_t off) const {
        checkRegAccess(sizeof(T) == 8 ? "64-bit BAR access" : "32-bit BAR access", off, sizeof(T));
        if (off % sizeof(T) != 0 || off >= bar_len_ || sizeof(T) > bar_len_ - off) {
            char b[160];
            std::snprintf(b, sizeof(b), "QdmaMemory: register offset 0x%llx is misaligned for a %zu-byte "
                                        "access or outside the 64 KB BAR window", (unsigned long long)off, sizeof(T));
            throw std::runtime_error(b);
        }
        return reinterpret_cast<volatile T *>(bar_ + off);
    }

    void checkRange(const char *what, uint64_t addr, uint64_t size) const {
        if (!range_check_) return;
        if (addr < hbm_base_ || size > hbm_size_ || addr - hbm_base_ > hbm_size_ - size) {
            char b[320];
            std::snprintf(b, sizeof(b),
                          "QdmaMemory::%s: [0x%llx, +0x%llx) is outside HBM [0x%llx, +0x%llx). Device "
                          "addresses are absolute on V80; use addresses from allocateMemFPGA "
                          "(HC_QDMA_NO_RANGE_CHECK=1 to bypass)",
                          what, (unsigned long long)addr, (unsigned long long)size,
                          (unsigned long long)hbm_base_, (unsigned long long)hbm_size_);
            throw std::runtime_error(b);
        }
    }

    // One pread/pwrite loop at absolute AXI address `addr`, retrying on EINTR and
    // continuing after short transfers.
    void xferExact(bool write, uint64_t addr, uint8_t *buf, uint64_t size) {
        int fd = write ? h2c_fd_ : c2h_fd_;
        uint64_t done = 0;
        int zero_tries = 0;
        while (done < size) {
            size_t n = static_cast<size_t>(size - done);
            ssize_t r = write ? ::pwrite(fd, buf + done, n, static_cast<off_t>(addr + done))
                              : ::pread(fd, buf + done, n, static_cast<off_t>(addr + done));
            if (r < 0) {
                int e = errno;
                if (e == EINTR) continue;
                char b[256];
                std::snprintf(b, sizeof(b), "QdmaMemory: %s of %llu bytes at 0x%llx on %s failed: %s",
                              write ? "H2C write" : "C2H read", (unsigned long long)n,
                              (unsigned long long)(addr + done),
                              (write ? h2c_path_ : c2h_path_).c_str(), std::strerror(e));
                throw hc_qdma::DmaError(b, e);
            }
            if (r == 0) {
                if (++zero_tries > 3)
                    throw std::runtime_error("QdmaMemory: DMA made no progress (0-byte transfer); "
                                             "is the queue started?");
                continue;
            }
            zero_tries = 0;
            done += static_cast<uint64_t>(r);
        }
    }

    void dmaWrite(uint64_t addr, uint8_t const *src, uint64_t size) {
        while (size > 0) {
            uint64_t n = std::min<uint64_t>(size, chunk_);
            if (n <= hc_qdma::kBounceThreshold) {
                std::lock_guard<std::mutex> g(bounce_mu_);
                std::memcpy(bounce_, src, n);
                xferExact(true, addr, static_cast<uint8_t *>(bounce_), n);
            } else {
                try {
                    xferExact(true, addr, const_cast<uint8_t *>(src), n);
                } catch (const hc_qdma::DmaError &e) {
                    if (e.err != EFAULT) throw;
                    // Source pages could not be pinned writable (read-only
                    // mapping): stage this chunk through the bounce buffer.
                    std::lock_guard<std::mutex> g(bounce_mu_);
                    for (uint64_t o = 0; o < n; o += hc_qdma::kBounceThreshold) {
                        uint64_t m = std::min<uint64_t>(n - o, hc_qdma::kBounceThreshold);
                        std::memcpy(bounce_, src + o, m);
                        xferExact(true, addr + o, static_cast<uint8_t *>(bounce_), m);
                    }
                }
            }
            addr += n; src += n; size -= n;
        }
    }

    void dmaRead(uint8_t *dest, uint64_t addr, uint64_t size) {
        while (size > 0) {
            uint64_t n = std::min<uint64_t>(size, chunk_);
            if (n <= hc_qdma::kBounceThreshold) {
                std::lock_guard<std::mutex> g(bounce_mu_);
                xferExact(false, addr, static_cast<uint8_t *>(bounce_), n);
                std::memcpy(dest, bounce_, n);
            } else {
                xferExact(false, addr, dest, n);
            }
            addr += n; dest += n; size -= n;
        }
    }

    void dmaReadExact(uint8_t *dest, uint64_t addr, uint64_t size) { xferExact(false, addr, dest, size); }

    void dmaRegCheck(uint64_t off, size_t n) const {
        checkRegAccess(n == 8 ? "64-bit DMA register access" : "32-bit DMA register access", off, n);
        if (off % n != 0 || off >= hc_qdma::kRegWindow || n > hc_qdma::kRegWindow - off) {
            char b[160];
            std::snprintf(b, sizeof(b), "QdmaMemory: register offset 0x%llx is misaligned for a %zu-byte "
                                        "access or outside the 64 KB register window", (unsigned long long)off, n);
            throw std::runtime_error(b);
        }
    }
    // Registers are never widened: the access is exactly 4 or 8 bytes at
    // regBase + off, as in the original working V80 driver.
    void dmaRegWrite(uint64_t off, const void *v, size_t n) {
        dmaRegCheck(off, n);
        std::lock_guard<std::mutex> g(bounce_mu_);
        std::memcpy(bounce_, v, n);
        xferExact(true, reg_base_ + off, static_cast<uint8_t *>(bounce_), n);
    }
    void dmaRegRead(uint64_t off, void *v, size_t n) {
        dmaRegCheck(off, n);
        std::lock_guard<std::mutex> g(bounce_mu_);
        xferExact(false, reg_base_ + off, static_cast<uint8_t *>(bounce_), n);
        std::memcpy(v, bounce_, n);
    }
};
