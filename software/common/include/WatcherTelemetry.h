#pragma once

// Shared, design-agnostic helpers for the free-running telemetry watcher's STATUS
// stream. Everything here is DERIVED from the <design>.hbmports.json descriptor that
// the architecture generator emits (and the host embeds in the trace header), so the
// host needs no per-design edits when the PE count per task changes -- only the JSON
// (numProcessingElements) and the watcher HLS N_ defines move.

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

namespace hardcilk_telemetry
{

// One 32-byte beat carries two independent 128-bit bundles; a STATUS bundle packs the
// monitored PEs' handshake bits, 4 bits per PE, at bit [peNumber*4 +: 4].
static constexpr std::size_t kBeatBytes = 32;
static constexpr int kStatusSlotBits = 4;
// The STATUS word is 48 bits => at most 12 PE slots (12 * 4). The generator enforces
// the same cap at build time; this mirrors it for a defensive host-side check.
static constexpr int kMaxStatusPes = 48 / kStatusSlotBits;

struct WatcherPe
{
  int peNumber = 0;       // STATUS bit slot (== bit offset / 4)
  std::string task;       // monitored task name (label + memReader-balance key)
  std::string statusPrefix;
};

// Extract a quoted string value for `key` from the object starting at `from`.
inline std::string extractString(const std::string &s, std::size_t from,
                                 const char *key)
{
  const auto k = s.find(key, from);
  if (k == std::string::npos)
    return {};
  const auto colon = s.find(':', k);
  if (colon == std::string::npos)
    return {};
  const auto q1 = s.find('"', colon + 1);
  if (q1 == std::string::npos)
    return {};
  const auto q2 = s.find('"', q1 + 1);
  if (q2 == std::string::npos)
    return {};
  return s.substr(q1 + 1, q2 - q1 - 1);
}

// Parse the "pes" array of a <design>.hbmports.json descriptor. Minimal hand parser
// (no JSON dependency): the entries are flat objects, so the first ']' after the
// "pes" key closes the array, which excludes the later "ports" array (whose masters
// also carry a "peNumber"). Returns PEs in file order == STATUS slot order.
inline std::vector<WatcherPe> parseWatcherPes(const std::string &json)
{
  std::vector<WatcherPe> pes;
  const auto pesKey = json.find("\"pes\"");
  if (pesKey == std::string::npos)
    return pes;
  const auto lb = json.find('[', pesKey);
  if (lb == std::string::npos)
    return pes;
  const auto rb = json.find(']', lb);
  if (rb == std::string::npos)
    return pes;
  const std::string block = json.substr(lb, rb - lb);

  std::size_t pos = 0;
  while (true)
  {
    const auto p = block.find("\"peNumber\"", pos);
    if (p == std::string::npos)
      break;
    const auto colon = block.find(':', p);
    WatcherPe pe;
    pe.peNumber = colon == std::string::npos
                      ? 0
                      : static_cast<int>(std::strtol(block.c_str() + colon + 1,
                                                     nullptr, 10));
    pe.task = extractString(block, p, "\"task\"");
    pe.statusPrefix = extractString(block, p, "\"statusPrefix\"");
    pes.push_back(std::move(pe));
    pos = p + 1;
  }
  return pes;
}

// STATUS-stream conservation check, driven entirely by the parsed PE table. For each
// monitored PE, sum the cycles its input handshake (in_valid & in_ready) and output
// handshake (out_valid & out_ready) were held, reconstructed from the edge-triggered
// STATUS samples (each sample's bits hold until the next sample's cycle_count). A
// memReader PE is strictly 1-task-in / 1-result-out, so its accepts MUST equal its
// outputs -- a residual imbalance there means the watcher dropped STATUS frames.
struct StatusConservation
{
  std::vector<WatcherPe> pes;
  std::vector<uint64_t> accepts;
  std::vector<uint64_t> outputs;
  bool havePrev = false;
  uint64_t prevCycle = 0;
  uint64_t prevStatus = 0;
  std::size_t statusSamples = 0;

  StatusConservation() = default;
  explicit StatusConservation(std::vector<WatcherPe> table)
      : pes(std::move(table)), accepts(pes.size(), 0), outputs(pes.size(), 0)
  {
  }

  void consumeSample(uint64_t cycle, uint64_t status48)
  {
    if (havePrev && cycle >= prevCycle)
    {
      const uint64_t dt = cycle - prevCycle;
      for (std::size_t idx = 0; idx < pes.size(); ++idx)
      {
        const int k = pes[idx].peNumber; // STATUS bit slot
        const uint32_t nib =
            static_cast<uint32_t>((prevStatus >> (k * kStatusSlotBits)) & 0xF);
        const bool in_v = nib & 1, in_r = (nib >> 1) & 1;
        const bool out_v = (nib >> 2) & 1, out_r = (nib >> 3) & 1;
        if (in_v && in_r)
          accepts[idx] += dt;
        if (out_v && out_r)
          outputs[idx] += dt;
      }
    }
    havePrev = true;
    prevCycle = cycle;
    prevStatus = status48;
    ++statusSamples;
  }

  void consumeTraceBytes(const uint8_t *data, std::size_t bytes)
  {
    for (std::size_t idx = 0; idx + kBeatBytes <= bytes; idx += kBeatBytes)
    {
      const uint8_t *beat = data + idx;
      for (int slot = 0; slot < 2; ++slot)
      {
        const uint8_t *b = beat + slot * 16;
        uint64_t lo = 0, hi = 0;
        for (int i = 0; i < 8; ++i)
        {
          lo |= static_cast<uint64_t>(b[i]) << (8 * i);
          hi |= static_cast<uint64_t>(b[8 + i]) << (8 * i);
        }
        if ((lo & 0xFF) != 1) // STATUS header == 1
          continue;
        const uint64_t status48 = (lo >> 8) & 0xFFFFFFFFFFFFULL;
        const uint64_t cycle = (lo >> 56) | (hi << 8);
        consumeSample(cycle, status48);
      }
    }
  }

  void report() const
  {
    if (pes.empty())
    {
      std::cerr << "[telemetry] STATUS conservation skipped: no PE table (descriptor "
                   "<design>.hbmports.json not found/parsed)\n";
      return;
    }
    if (statusSamples < 2)
    {
      std::cerr << "[telemetry] !! STATUS conservation failed: only "
                << statusSamples
                << " STATUS sample(s); expected at least 2 for transition "
                   "verification\n";
      return;
    }

    std::cout << "[telemetry] STATUS conservation (handshake-cycles; memReader is "
                 "strictly 1-in/1-out, so its delta should be 0):\n";
    long long memReaderImbalance = 0;
    bool sawMemReader = false;
    for (std::size_t idx = 0; idx < pes.size(); ++idx)
    {
      const long long d =
          static_cast<long long>(outputs[idx]) - static_cast<long long>(accepts[idx]);
      std::cout << "[telemetry]   PE " << pes[idx].peNumber << " " << pes[idx].task
                << " accepts=" << accepts[idx] << " outputs=" << outputs[idx]
                << " delta=" << (d >= 0 ? "+" : "") << d << "\n";
      if (pes[idx].task == "memReader")
      {
        sawMemReader = true;
        memReaderImbalance += (d < 0 ? -d : d);
      }
    }
    if (!sawMemReader)
      std::cout << "[telemetry] STATUS conservation: no memReader PE monitored; "
                   "deltas reported, no balance assertion applied.\n";
    else if (memReaderImbalance != 0)
      std::cerr << "[telemetry] !! STATUS conservation off by " << memReaderImbalance
                << " cycles across memReader PEs -> the watcher DROPPED STATUS frames "
                   "(lossy status telemetry); treat per-cycle status counts as "
                   "approximate. Duplicated/lost TASKS stay PE-balanced, so this is a "
                   "telemetry-integrity signal, not proof of a compute bug.\n";
    else
      std::cout << "[telemetry] STATUS conservation OK (memReader balanced; no dropped "
                   "status frames detected).\n";
  }
};

} // namespace hardcilk_telemetry
