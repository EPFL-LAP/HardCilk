#pragma once

#include <hardCilkDriver.h>

#include <algorithm>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <numeric>
#include <random>
#include <sstream>
#include <streambuf>
#include <string>
#include <thread>
#include <tuple>
#include <utility>
#include <vector>

#include <gbbs/gbbs.h>

using Addr = uint64_t;

static constexpr Addr BYTE_ATOMIC_SLOT_BYTES = 1;
static constexpr uint32_t UNCOLORED_U32 = 0xFFFFFFFFu;

inline bool hardcilkDoneConditionStub(int32_t) { return false; }

class NullStreambuf : public std::streambuf
{
protected:
  int_type overflow(int_type ch) override { return traits_type::not_eof(ch); }
};

class ScopedCoutSilencer
{
public:
  ScopedCoutSilencer() : previous_(std::cout.rdbuf(&null_)) {}
  ~ScopedCoutSilencer() { std::cout.rdbuf(previous_); }

private:
  NullStreambuf null_;
  std::streambuf *previous_;
};

struct UnweightedGraph
{
  uint32_t num_vertices = 0;
  uint32_t num_edges = 0; // input edges, before undirected expansion
  std::vector<uint32_t> offsets;
  std::vector<uint32_t> neighbors;

  uint32_t degree(uint32_t u) const { return offsets[u + 1] - offsets[u]; }
};

struct WeightedEdge
{
  uint32_t src = 0;
  uint32_t dst = 0;
  float weight = 0.0f;
};

struct WeightedGraph
{
  uint32_t num_vertices = 0;
  uint32_t num_edges = 0;
  std::vector<uint32_t> offsets;
  std::vector<WeightedEdge> edges;

  uint32_t degree(uint32_t u) const { return offsets[u + 1] - offsets[u]; }
};

inline bool parseUnweightedEdge(std::string line, uint32_t &src,
                                uint32_t &dst)
{
  const size_t comment = line.find('#');
  if (comment != std::string::npos)
    line.resize(comment);
  std::replace(line.begin(), line.end(), ',', ' ');
  std::replace(line.begin(), line.end(), '\t', ' ');
  std::istringstream iss(line);
  return (iss >> src >> dst) ? true : false;
}

inline bool loadUndirectedGraph(const std::string &path, UnweightedGraph &G)
{
  std::ifstream f(path);
  if (!f.is_open())
  {
    std::cerr << "[graph] cannot open graph: " << path << "\n";
    return false;
  }

  std::vector<std::pair<uint32_t, uint32_t>> input_edges;
  uint32_t max_vertex = 0;
  std::string line;
  while (std::getline(f, line))
  {
    uint32_t src = 0, dst = 0;
    if (!parseUnweightedEdge(line, src, dst))
      continue;
    max_vertex = std::max(max_vertex, std::max(src, dst));
    input_edges.push_back({src, dst});
  }

  G.num_vertices = input_edges.empty() ? 0 : max_vertex + 1;
  G.num_edges = (uint32_t)input_edges.size();
  if (G.num_vertices == 0)
  {
    G.offsets.assign(1, 0);
    return true;
  }

  std::vector<uint32_t> degree(G.num_vertices, 0);
  for (auto e : input_edges)
  {
    degree[e.first]++;
    if (e.second != e.first)
      degree[e.second]++;
  }

  G.offsets.assign((size_t)G.num_vertices + 1, 0);
  for (uint32_t v = 0; v < G.num_vertices; v++)
    G.offsets[(size_t)v + 1] = G.offsets[v] + degree[v];

  G.neighbors.assign(G.offsets.back(), 0);
  std::vector<uint32_t> cursor = G.offsets;
  for (auto e : input_edges)
  {
    G.neighbors[cursor[e.first]++] = e.second;
    if (e.second != e.first)
      G.neighbors[cursor[e.second]++] = e.first;
  }
  return true;
}

inline bool parseWeightedEdge(std::string line, WeightedEdge &edge)
{
  const size_t comment = line.find('#');
  if (comment != std::string::npos)
    line.resize(comment);
  std::replace(line.begin(), line.end(), ',', ' ');
  std::replace(line.begin(), line.end(), '\t', ' ');

  double weight = 0.0;
  std::istringstream iss(line);
  if (!(iss >> edge.src >> edge.dst >> weight))
    return false;
  edge.weight = (float)weight;
  return true;
}

inline bool loadWeightedDirectedCsv(const std::string &path, uint32_t source,
                                    WeightedGraph &G)
{
  std::ifstream f(path);
  if (!f.is_open())
  {
    std::cerr << "[graph] cannot open weighted CSV: " << path << "\n";
    return false;
  }

  std::vector<WeightedEdge> input_edges;
  std::vector<uint32_t> degree;
  uint32_t max_vertex = source;
  std::string line;
  while (std::getline(f, line))
  {
    WeightedEdge e;
    if (!parseWeightedEdge(line, e))
      continue;
    max_vertex = std::max(max_vertex, std::max(e.src, e.dst));
    if (e.src >= degree.size())
      degree.resize((size_t)e.src + 1, 0);
    degree[e.src]++;
    input_edges.push_back(e);
  }

  G.num_vertices = max_vertex + 1;
  G.num_edges = (uint32_t)input_edges.size();
  G.offsets.assign((size_t)G.num_vertices + 1, 0);
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    uint32_t d = (v < degree.size()) ? degree[v] : 0;
    G.offsets[(size_t)v + 1] = G.offsets[v] + d;
  }

  G.edges.assign(G.num_edges, WeightedEdge{});
  std::vector<uint32_t> cursor = G.offsets;
  for (const WeightedEdge &e : input_edges)
    G.edges[cursor[e.src]++] = e;
  return true;
}

inline uint32_t floatToBits(float value)
{
  uint32_t bits = 0;
  std::memcpy(&bits, &value, sizeof(bits));
  return bits;
}

inline float bitsToFloat(uint32_t bits)
{
  float value = 0.0f;
  std::memcpy(&value, &bits, sizeof(value));
  return value;
}

inline uint32_t fixedU16_16(double value)
{
  if (value < 0.0)
    value = 0.0;
  double scaled = std::round(value * 65536.0);
  if (scaled > (double)std::numeric_limits<uint32_t>::max())
    scaled = (double)std::numeric_limits<uint32_t>::max();
  return (uint32_t)scaled;
}

inline double fixedU32_32ToDouble(uint64_t value)
{
  return (double)value / 4294967296.0;
}

inline std::vector<uint32_t> seededPermutation(uint32_t n, uint32_t seed)
{
  std::vector<uint32_t> p(n);
  std::iota(p.begin(), p.end(), 0);
  std::mt19937 rng(seed);
  std::shuffle(p.begin(), p.end(), rng);
  return p;
}

inline auto buildGbbsUnweightedGraph(const UnweightedGraph &G)
{
  using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;
  auto edges = gbbs::sequence<GbbsEdge>::uninitialized(G.neighbors.size());
  size_t out = 0;
  for (uint32_t u = 0; u < G.num_vertices; u++)
  {
    for (uint32_t i = G.offsets[u]; i < G.offsets[u + 1]; i++)
      edges[out++] = GbbsEdge{(gbbs::uintE)u, (gbbs::uintE)G.neighbors[i],
                              gbbs::empty{}};
  }
  return gbbs::asymmetric_graph<gbbs::asymmetric_vertex,
                                gbbs::empty>::from_edges(edges,
                                                         G.num_vertices);
}

inline auto buildGbbsWeightedFloatGraph(const WeightedGraph &G)
{
  using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, float>;
  auto edges = gbbs::sequence<GbbsEdge>::uninitialized(G.edges.size());
  for (size_t i = 0; i < G.edges.size(); i++)
    edges[i] = GbbsEdge{(gbbs::uintE)G.edges[i].src,
                        (gbbs::uintE)G.edges[i].dst, G.edges[i].weight};
  return gbbs::asymmetric_graph<gbbs::asymmetric_vertex, float>::from_edges(
      edges, G.num_vertices);
}

inline bool weightedGraphHasIntegerWeights(const WeightedGraph &G)
{
  for (const WeightedEdge &e : G.edges)
  {
    if (!std::isfinite(e.weight))
      return false;
    if (std::fabs((double)e.weight - std::round((double)e.weight)) > 1e-6)
      return false;
  }
  return true;
}

inline auto buildGbbsWeightedIntGraph(const WeightedGraph &G)
{
  using GbbsEdge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::intE>;
  auto edges = gbbs::sequence<GbbsEdge>::uninitialized(G.edges.size());
  for (size_t i = 0; i < G.edges.size(); i++)
    edges[i] = GbbsEdge{(gbbs::uintE)G.edges[i].src,
                        (gbbs::uintE)G.edges[i].dst,
                        (gbbs::intE)std::llround((double)G.edges[i].weight)};
  return gbbs::asymmetric_graph<gbbs::asymmetric_vertex, gbbs::intE>::from_edges(
      edges, G.num_vertices);
}

inline void copyBytesToDevice(Memory *memory, Addr addr, const void *data,
                              uint64_t bytes)
{
  if (bytes != 0)
    memory->copyToDevice(addr, reinterpret_cast<const uint8_t *>(data), bytes);
}

template <class T>
inline void copyVectorToDevice(Memory *memory, Addr addr,
                               const std::vector<T> &data)
{
  copyBytesToDevice(memory, addr, data.data(), data.size() * sizeof(T));
}

inline Addr writeUnweightedCsrToHbm(Memory *memory, const UnweightedGraph &G,
                                    Addr &neighbors_base)
{
  uint64_t neighbor_bytes = std::max<uint64_t>(
      (uint64_t)G.neighbors.size() * sizeof(uint32_t), sizeof(uint32_t));
  neighbors_base = memory->allocateMemFPGA(neighbor_bytes, 512);
  copyVectorToDevice(memory, neighbors_base, G.neighbors);

  std::vector<uint64_t> graph_entries(2ull * G.num_vertices);
  for (uint32_t u = 0; u < G.num_vertices; u++)
  {
    uint64_t neighbor_addr =
        neighbors_base + (uint64_t)G.offsets[u] * sizeof(uint32_t);
    graph_entries[2 * u + 0] = neighbor_addr;
    graph_entries[2 * u + 1] = G.degree(u);
  }

  Addr graph_base =
      memory->allocateMemFPGA(graph_entries.size() * sizeof(uint64_t), 512);
  copyVectorToDevice(memory, graph_base, graph_entries);
  return graph_base;
}

inline Addr writeWeightedCsrToHbm(Memory *memory, const WeightedGraph &G,
                                  Addr &edges_base)
{
  std::vector<uint64_t> packed(std::max<size_t>(G.edges.size(), 1), 0);
  for (size_t i = 0; i < G.edges.size(); i++)
    packed[i] = ((uint64_t)floatToBits(G.edges[i].weight) << 32) |
                (uint64_t)G.edges[i].dst;

  edges_base = memory->allocateMemFPGA(packed.size() * sizeof(uint64_t), 512);
  copyVectorToDevice(memory, edges_base, packed);

  std::vector<uint64_t> graph_entries(2ull * G.num_vertices);
  for (uint32_t u = 0; u < G.num_vertices; u++)
  {
    uint64_t edge_addr = edges_base + (uint64_t)G.offsets[u] * sizeof(uint64_t);
    graph_entries[2 * u + 0] = edge_addr >> 1;
    graph_entries[2 * u + 1] = G.degree(u);
  }

  Addr graph_base =
      memory->allocateMemFPGA(graph_entries.size() * sizeof(uint64_t), 512);
  copyVectorToDevice(memory, graph_base, graph_entries);
  return graph_base;
}

inline bool floatDistanceMatch(double ref, float got, double rel_eps = 1e-3)
{
  if (std::isinf(ref))
    return std::isinf(got) && ((ref < 0.0) == (got < 0.0));
  if (!std::isfinite(got))
    return false;
  const double diff = std::fabs((double)got - ref);
  const double scale = std::max(1.0, std::fabs(ref));
  return diff <= rel_eps * scale;
}

inline std::vector<double> referenceWidestPath(const WeightedGraph &G,
                                               uint32_t source)
{
  const double neg_inf = -std::numeric_limits<double>::infinity();
  std::vector<double> width(G.num_vertices, neg_inf);
  if (source >= G.num_vertices)
    return width;

  width[source] = std::numeric_limits<double>::infinity();
  for (uint32_t pass = 0; pass + 1 < G.num_vertices; pass++)
  {
    bool changed = false;
    for (const WeightedEdge &e : G.edges)
    {
      if (std::isinf(width[e.src]) && width[e.src] < 0.0)
        continue;
      double candidate = std::min(width[e.src], (double)e.weight);
      if (candidate > width[e.dst] + 1e-12)
      {
        width[e.dst] = candidate;
        changed = true;
      }
    }
    if (!changed)
      break;
  }
  return width;
}

inline std::vector<double> referenceBellmanFord(const WeightedGraph &G,
                                                uint32_t source)
{
  const double inf = std::numeric_limits<double>::infinity();
  std::vector<double> dist(G.num_vertices, inf);
  if (source >= G.num_vertices)
    return dist;

  dist[source] = 0.0;
  for (uint32_t pass = 0; pass + 1 < G.num_vertices; pass++)
  {
    bool changed = false;
    for (const WeightedEdge &e : G.edges)
    {
      if (std::isinf(dist[e.src]) && dist[e.src] > 0.0)
        continue;
      double candidate = dist[e.src] + (double)e.weight;
      if (candidate + 1e-12 < dist[e.dst])
      {
        dist[e.dst] = candidate;
        changed = true;
      }
    }
    if (!changed)
      break;
  }
  return dist;
}

inline uint64_t inducedEdgeCount(const UnweightedGraph &G,
                                 const std::vector<uint8_t> &in_set)
{
  uint64_t arcs = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    if (!in_set[v])
      continue;
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      if (in_set[G.neighbors[i]])
        arcs++;
  }
  return arcs / 2;
}

struct ApproxDenseSubReference
{
  std::vector<uint32_t> best_set;
  double best_density = 0.0;
  uint32_t rounds = 0;
  uint32_t final_length = 0;
  double seconds = 0.0;
};

struct MISReference
{
  std::vector<uint8_t> in_mis;
  uint64_t size = 0;
  uint32_t rounds = 0;
  uint32_t last_covered_length = 0;
  double seconds = 0.0;
};

struct ColoringReference
{
  std::vector<uint32_t> colors;
  uint32_t colors_used = 0;
  uint32_t rounds = 0;
  uint32_t last_frontier_length = 0;
  double seconds = 0.0;
};

struct NumericVectorSummary
{
  uint32_t finite = 0;
  uint32_t pos_inf = 0;
  uint32_t neg_inf = 0;
  uint32_t nan = 0;
  double min_value = std::numeric_limits<double>::infinity();
  double max_value = -std::numeric_limits<double>::infinity();
  double sum = 0.0;
  uint64_t checksum = 1469598103934665603ull;
};

inline uint64_t mixSummaryWord(uint64_t hash, uint64_t word)
{
  hash ^= word + 0x9e3779b97f4a7c15ull + (hash << 6) + (hash >> 2);
  return hash;
}

template <class T>
inline NumericVectorSummary summarizeNumericVector(const std::vector<T> &values)
{
  NumericVectorSummary out;
  for (size_t i = 0; i < values.size(); i++)
  {
    double x = (double)values[i];
    uint64_t code = 0;
    if (std::isnan(x))
    {
      out.nan++;
      code = 0x7ff8000000000000ull;
    }
    else if (std::isinf(x) && x > 0.0)
    {
      out.pos_inf++;
      code = 0x7ff0000000000000ull;
    }
    else if (std::isinf(x) && x < 0.0)
    {
      out.neg_inf++;
      code = 0xfff0000000000000ull;
    }
    else
    {
      out.finite++;
      out.min_value = std::min(out.min_value, x);
      out.max_value = std::max(out.max_value, x);
      out.sum += x;
      code = (uint64_t)(int64_t)std::llround(x * 1000000.0);
    }
    out.checksum = mixSummaryWord(out.checksum, ((uint64_t)i << 32) ^ code);
  }
  return out;
}

inline void printNumericSummary(const std::string &label,
                                const NumericVectorSummary &s)
{
  std::cout << label << " finite=" << s.finite << " +inf=" << s.pos_inf
            << " -inf=" << s.neg_inf << " nan=" << s.nan;
  if (s.finite != 0)
    std::cout << " min=" << s.min_value << " max=" << s.max_value
              << " sum=" << s.sum;
  std::cout << " checksum=0x" << std::hex << s.checksum << std::dec << "\n";
}

inline uint64_t summarizeBitset(const std::vector<uint8_t> &values,
                                uint64_t &set_count)
{
  set_count = 0;
  uint64_t checksum = 1469598103934665603ull;
  for (size_t i = 0; i < values.size(); i++)
  {
    if (values[i] != 0)
      set_count++;
    checksum = mixSummaryWord(checksum, ((uint64_t)i << 1) | (values[i] != 0));
  }
  return checksum;
}

inline void printColorHistogram(const std::string &label,
                                const std::vector<uint32_t> &colors,
                                uint32_t colors_used)
{
  std::vector<uint32_t> counts(colors_used, 0);
  uint32_t uncolored = 0;
  uint64_t checksum = 1469598103934665603ull;
  for (size_t i = 0; i < colors.size(); i++)
  {
    uint32_t color = colors[i];
    if (color < colors_used)
      counts[color]++;
    else
      uncolored++;
    checksum = mixSummaryWord(checksum, ((uint64_t)i << 32) ^ color);
  }

  std::cout << label << " colors_used=" << colors_used
            << " uncolored_or_oob=" << uncolored << " histogram=[";
  uint32_t shown = std::min<uint32_t>(colors_used, 8);
  for (uint32_t c = 0; c < shown; c++)
  {
    if (c != 0)
      std::cout << ",";
    std::cout << c << ":" << counts[c];
  }
  if (colors_used > shown)
    std::cout << ",...";
  std::cout << "] checksum=0x" << std::hex << checksum << std::dec << "\n";
}

inline ApproxDenseSubReference
runExposedApproxDenseSubReference(const UnweightedGraph &G, double epsilon)
{
  auto t0 = std::chrono::high_resolution_clock::now();
  const double epsilon_quantized = (double)fixedU16_16(epsilon) / 65536.0;
  std::vector<uint32_t> degree(G.num_vertices);
  std::vector<uint32_t> S;
  std::vector<uint8_t> in_set(G.num_vertices, 1);
  ApproxDenseSubReference out;
  S.reserve(G.num_vertices);
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    degree[v] = G.degree(v);
    S.push_back(v);
  }

  bool have_best = false;
  while (!S.empty())
  {
    out.rounds++;
    uint64_t degree_sum = 0;
    for (uint32_t v : S)
      degree_sum += degree[v];
    double density = (double)degree_sum / (2.0 * (double)S.size());
    if (!have_best || density > out.best_density)
    {
      out.best_density = density;
      out.best_set = S;
      have_best = true;
    }
    if (density == 0.0)
      break;

    double threshold = 2.0 * (1.0 + epsilon_quantized) * density;

    std::vector<uint32_t> R;
    for (uint32_t v : S)
      if ((double)degree[v] < threshold)
        R.push_back(v);
    if (R.empty())
      break;

    for (uint32_t v : R)
      in_set[v] = 0;
    for (uint32_t v : R)
    {
      degree[v] = 0;
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (in_set[u] && degree[u] != 0)
          degree[u]--;
      }
    }

    std::vector<uint32_t> next;
    next.reserve(S.size());
    for (uint32_t v : S)
      if (in_set[v])
        next.push_back(v);
    S.swap(next);
  }
  out.final_length = (uint32_t)S.size();
  auto t1 = std::chrono::high_resolution_clock::now();
  out.seconds = std::chrono::duration<double>(t1 - t0).count();
  return out;
}

inline std::vector<uint32_t>
referenceApproxDenseSub(const UnweightedGraph &G, double epsilon,
                        double &best_density)
{
  ApproxDenseSubReference ref = runExposedApproxDenseSubReference(G, epsilon);
  best_density = ref.best_density;
  return ref.best_set;
}

inline MISReference runSeededMISReference(const UnweightedGraph &G,
                                          const std::vector<uint32_t> &priority)
{
  auto t0 = std::chrono::high_resolution_clock::now();
  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> count(n, 0);
  std::vector<uint8_t> covered(n, 0);
  MISReference out;
  out.in_mis.assign(n, 0);

  for (uint32_t v = 0; v < n; v++)
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      if (priority[G.neighbors[i]] > priority[v])
        count[v]++;

  uint32_t num_finished = 0;
  while (num_finished < n)
  {
    std::vector<uint32_t> newly_covered;
    for (uint32_t v = 0; v < n; v++)
    {
      if (covered[v] == 0 && count[v] == 0)
      {
        out.in_mis[v] = 1;
        covered[v] = 1;
        newly_covered.push_back(v);
        for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
        {
          uint32_t u = G.neighbors[i];
          if (covered[u] == 0)
          {
            covered[u] = 1;
            newly_covered.push_back(u);
          }
        }
      }
    }
    if (newly_covered.empty())
      break;
    out.rounds++;
    out.last_covered_length = (uint32_t)newly_covered.size();
    for (uint32_t v : newly_covered)
    {
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (covered[u] == 0 && priority[v] > priority[u] && count[u] != 0)
          count[u]--;
      }
    }
    num_finished += (uint32_t)newly_covered.size();
  }
  for (uint8_t x : out.in_mis)
    out.size += (x != 0);
  auto t1 = std::chrono::high_resolution_clock::now();
  out.seconds = std::chrono::duration<double>(t1 - t0).count();
  return out;
}

inline void referenceMIS(const UnweightedGraph &G,
                         const std::vector<uint32_t> &priority,
                         std::vector<uint8_t> &in_mis)
{
  in_mis = runSeededMISReference(G, priority).in_mis;
}

inline uint32_t floorLog2(uint32_t x)
{
  uint32_t out = 0;
  while (x >>= 1)
    out++;
  return out;
}

inline bool coloringRunsBefore(uint32_t left_log_degree, uint32_t left_rank,
                               uint32_t right_log_degree, uint32_t right_rank)
{
  return left_log_degree > right_log_degree ||
         (left_log_degree == right_log_degree && left_rank < right_rank);
}

inline ColoringReference runSeededColoringReference(
    const UnweightedGraph &G, const std::vector<uint32_t> &rank,
    uint32_t max_colors)
{
  auto t0 = std::chrono::high_resolution_clock::now();
  const uint32_t n = G.num_vertices;
  std::vector<uint32_t> priority(n, 0);
  ColoringReference out;
  out.colors.assign(n, UNCOLORED_U32);

  for (uint32_t v = 0; v < n; v++)
  {
    uint32_t v_log = floorLog2(G.degree(v));
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (coloringRunsBefore(floorLog2(G.degree(u)), rank[u], v_log, rank[v]))
        priority[v]++;
    }
  }

  uint32_t finished = 0;
  while (finished < n)
  {
    std::vector<uint32_t> roots;
    for (uint32_t v = 0; v < n; v++)
      if (out.colors[v] == UNCOLORED_U32 && priority[v] == 0)
        roots.push_back(v);
    if (roots.empty())
      break;
    out.rounds++;
    out.last_frontier_length = (uint32_t)roots.size();

    for (uint32_t v : roots)
    {
      std::vector<uint8_t> used(max_colors, 0);
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
      {
        uint32_t u = G.neighbors[i];
        if (out.colors[u] != UNCOLORED_U32 && out.colors[u] < max_colors)
          used[out.colors[u]] = 1;
      }
      uint32_t chosen = 0;
      while (chosen + 1 < max_colors && used[chosen])
        chosen++;
      out.colors[v] = chosen;
      out.colors_used = std::max(out.colors_used, chosen + 1);
    }

    finished += (uint32_t)roots.size();
    for (uint32_t v : roots)
      for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
        if (priority[G.neighbors[i]] > 0)
          priority[G.neighbors[i]]--;
  }
  auto t1 = std::chrono::high_resolution_clock::now();
  out.seconds = std::chrono::duration<double>(t1 - t0).count();
  return out;
}

inline void referenceColoring(const UnweightedGraph &G,
                              const std::vector<uint32_t> &rank,
                              uint32_t max_colors,
                              std::vector<uint32_t> &colors,
                              uint32_t &colors_used)
{
  ColoringReference ref = runSeededColoringReference(G, rank, max_colors);
  colors = ref.colors;
  colors_used = ref.colors_used;
}

inline bool validateColoring(const UnweightedGraph &G,
                             const std::vector<uint32_t> &colors,
                             uint32_t max_colors, uint32_t &bad_edges,
                             uint32_t &uncolored)
{
  bad_edges = 0;
  uncolored = 0;
  for (uint32_t v = 0; v < G.num_vertices; v++)
  {
    if (colors[v] == UNCOLORED_U32 || colors[v] >= max_colors)
      uncolored++;
    for (uint32_t i = G.offsets[v]; i < G.offsets[v + 1]; i++)
    {
      uint32_t u = G.neighbors[i];
      if (u > v && colors[u] == colors[v])
        bad_edges++;
    }
  }
  return bad_edges == 0 && uncolored == 0;
}

class BenchmarkDriverBase : public hardCilkDriver
{
public:
  BenchmarkDriverBase(Memory *memory, double watchdog_s, bool fast_mode,
                      std::string label)
      : hardCilkDriver(memory), watchdog_s_(watchdog_s),
        fast_mode_(fast_mode), label_(std::move(label)) {}

protected:
  double watchdog_s_;
  bool fast_mode_;
  std::string label_;
  std::chrono::high_resolution_clock::time_point t_kernel_done_;

  void tuneSchedulerQueueCapacities(const std::string &launcher_name,
                                    uint32_t vertex_count)
  {
    const uint64_t launcher_entries = 64;
    const uint64_t worker_entries =
        std::max<uint64_t>(64, (uint64_t)vertex_count);
    for (auto &task : descriptor.taskDescriptors)
    {
      uint64_t target =
          (task.name == launcher_name) ? launcher_entries : worker_entries;
      for (auto &config : task.sidesConfigs)
      {
        if (config.sideType != "scheduler" || config.capacityVirtualQueue <= 0)
          continue;
        if (target < (uint64_t)config.capacityVirtualQueue)
        {
          std::cout << "[" << label_ << "] scheduler queue cap for "
                    << task.name << ": " << config.capacityVirtualQueue
                    << " -> " << target << " entries\n";
          config.capacityVirtualQueue = (int)target;
        }
      }
    }
  }

  template <class Task>
  int runRootTask(const std::vector<Task> &base_task_data, Addr cont_base,
                  Addr done_offset)
  {
    initSystem(base_task_data, &hardcilkDoneConditionStub, 0, 0, false);
    auto start = std::chrono::high_resolution_clock::now();
    startSystem();
    return pollDone(cont_base, done_offset, start);
  }

  int pollDone(Addr cont_base, Addr done_offset,
               std::chrono::high_resolution_clock::time_point start)
  {
    const auto deadline = start + std::chrono::duration<double>(watchdog_s_);
    uint64_t iters = 0;
    while (true)
    {
      if (!fast_mode_ && checkPaused() == 0)
        managePausedServer();

      uint32_t done = 0;
      memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&done),
                              cont_base + done_offset, sizeof(done));
      if (done != 0)
      {
        t_kernel_done_ = std::chrono::high_resolution_clock::now();
        std::cout << "[" << label_ << "] done after " << iters
                  << " polls";
        if (fast_mode_)
          std::cout << " (fast mode)";
        std::cout << "\n";
        return 0;
      }

      auto now = std::chrono::high_resolution_clock::now();
      if (now > deadline)
      {
        t_kernel_done_ = now;
        std::cerr << "[" << label_ << "] WATCHDOG: " << watchdog_s_
                  << "s elapsed without done.\n";
        return -1;
      }
      iters++;
      std::this_thread::sleep_for(fast_mode_ ? std::chrono::milliseconds(10)
                                             : std::chrono::microseconds(200));
    }
  }
};
