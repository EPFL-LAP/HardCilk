// GBBS triangle count, kept in its own translation unit so the HLS headers and
// GBBS/parlay never meet: the kernels build as C++14 with Vitis HLS on the
// include path, GBBS needs C++17 and parlay. The harness passes a plain CSR
// across the boundary.
//
// Built only by `make gbbs`; the default `make test` does not need it.

#include <cstdint>
#include <cstdio>

#include <gbbs/gbbs.h>
#include <benchmarks/TriangleCounting/ShunTangwongsan15/Triangle.h>

size_t gbbsTriangleCount(uint32_t num_vertices, const uint32_t *offsets,
                         const uint32_t *neighbours)
{
  using Edge = std::tuple<gbbs::uintE, gbbs::uintE, gbbs::empty>;
  const size_t m = offsets[num_vertices];
  auto edges = gbbs::sequence<Edge>::uninitialized(m);
  size_t out = 0;
  for (uint32_t u = 0; u < num_vertices; u++)
    for (uint32_t i = offsets[u]; i < offsets[u + 1]; i++)
      edges[out++] = Edge{(gbbs::uintE)u, (gbbs::uintE)neighbours[i],
                          gbbs::empty{}};

  auto G = gbbs::symmetric_graph<gbbs::symmetric_vertex, gbbs::empty>::
      from_edges(edges, num_vertices);
  auto noop = [](gbbs::uintE, gbbs::uintE, gbbs::uintE) {};
  return gbbs::Triangle_degree_ordering(G, noop);
}
