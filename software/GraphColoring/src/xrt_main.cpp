#include <testBench.h>

static const std::string KERNEL_NAME = "GraphColoring_0:{GraphColoring_0}";

int main(int argc, char *argv[])
{
  return run_graph_coloring_benchmark(argc, argv, KERNEL_NAME);
}
