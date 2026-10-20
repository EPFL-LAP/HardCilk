#pragma once
// HBM 4 KB striping (HC_HBM_STRIPING, V80): host side of hc_axi_stripe
// (TclResources.TclV80Common.stripeVerilog). Header-only, C++11.
//
// The design's masters see a LOGICAL address L; hc_axi_stripe in front of every
// HBM NMU sends it to the PHYSICAL address P = map(L):
//   mode 1 (stripe4k):     P[34:30] = L[16:12],               P[29:12] = L[34:17]
//   mode 2 (stripe4k_xor): P[34:30] = L[16:12] ^ h(L[34:17]), P[29:12] = L[34:17]
//                          h(x) = x[4:0] ^ x[9:5] ^ x[14:10] ^ x[17:15]
// (offset bits == address bits: the HBM base 0x40_0000_0000 is 2^38-aligned).
// 4 KB block b of HBM lands in pseudo channel (b mod 32) [XOR a fold of b/32],
// at block b/32 of that PC. The host's QDMA does not pass through the PL, so a
// host copy must write/read P = map(L) itself (QdmaMemory does, see
// memIO_qdma.h), unless the bitstream routes the host through a PL striping
// path (HC_HBM_HOST_ALIAS_BASE).
//
// planStriped() turns a logical copy into physical runs: pieces of 4 KB blocks
// sorted by physical address and merged when physically adjacent. With the
// rotate layout a PC's share of a contiguous logical buffer is contiguous, so a
// large copy becomes ~32 runs (one per PC), each gathered/scattered through a
// staging buffer.

#include <algorithm>
#include <cstdint>
#include <vector>

namespace hc_stripe {

constexpr uint64_t kBlock = 4096;

inline uint64_t bitsOf(uint64_t a, int hi, int lo) { return (a >> lo) & ((uint64_t(1) << (hi - lo + 1)) - 1); }

/** Logical -> physical (what hc_axi_stripe does). mode 0 = identity. */
inline uint64_t map(uint64_t a, int mode) {
    if (mode == 0) return a;
    uint64_t blk = bitsOf(a, 16, 12);
    uint64_t pos = bitsOf(a, 34, 17);
    uint64_t h = (mode == 2) ? (bitsOf(a, 21, 17) ^ bitsOf(a, 26, 22) ^ bitsOf(a, 31, 27) ^ bitsOf(a, 34, 32)) : 0;
    uint64_t keep = a & ~((uint64_t(1) << 35) - (uint64_t(1) << 12));
    return keep | ((blk ^ h) << 30) | (pos << 12);
}

/** Physical -> logical. */
inline uint64_t unmap(uint64_t p, int mode) {
    if (mode == 0) return p;
    uint64_t pos = bitsOf(p, 29, 12);
    uint64_t pc = bitsOf(p, 34, 30);
    uint64_t h = (mode == 2) ? (bitsOf(pos, 4, 0) ^ bitsOf(pos, 9, 5) ^ bitsOf(pos, 14, 10) ^ bitsOf(pos, 17, 15)) : 0;
    uint64_t keep = p & ~((uint64_t(1) << 35) - (uint64_t(1) << 12));
    return keep | ((pc ^ h) << 12) | (pos << 17);
}

/** Pseudo channel (0..31) a logical address lands in. */
inline int pcOf(uint64_t a, int mode) { return static_cast<int>(bitsOf(map(a, mode), 34, 30)); }

struct Piece {
    uint64_t phys;    // physical device address
    uint64_t hostOff; // offset in the host buffer
    uint64_t len;     // bytes, never crosses a 4 KB block
};

/** One physically contiguous transfer made of >= 1 pieces (pieces[first, first+count)). */
struct Run {
    uint64_t phys;
    uint64_t len;
    size_t first;
    size_t count;
};

/**
 * Plan a logical copy [addr, addr + size): `pieces` sorted by physical address,
 * `runs` = maximal physically contiguous groups, each at most `maxRun` bytes.
 */
inline void planStriped(uint64_t addr, uint64_t size, int mode, uint64_t maxRun, std::vector<Piece> &pieces,
                        std::vector<Run> &runs) {
    pieces.clear();
    runs.clear();
    for (uint64_t off = 0; off < size;) {
        uint64_t a = addr + off;
        uint64_t n = std::min<uint64_t>(kBlock - (a & (kBlock - 1)), size - off);
        pieces.push_back(Piece{map(a, mode), off, n});
        off += n;
    }
    if (mode != 0 && pieces.size() > 1)
        std::sort(pieces.begin(), pieces.end(), [](const Piece &x, const Piece &y) { return x.phys < y.phys; });
    for (size_t i = 0; i < pieces.size(); ++i) {
        if (!runs.empty()) {
            Run &r = runs.back();
            if (r.phys + r.len == pieces[i].phys && r.len + pieces[i].len <= maxRun) {
                r.len += pieces[i].len;
                r.count++;
                continue;
            }
        }
        runs.push_back(Run{pieces[i].phys, pieces[i].len, i, 1});
    }
}

/** True when the run's pieces are consecutive in the host buffer too (no gather needed). */
inline bool runHostContiguous(const std::vector<Piece> &pieces, const Run &r) {
    for (size_t k = 1; k < r.count; ++k)
        if (pieces[r.first + k].hostOff != pieces[r.first + k - 1].hostOff + pieces[r.first + k - 1].len) return false;
    return true;
}

} // namespace hc_stripe
