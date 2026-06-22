#include <testBench.h>

static const std::string KERNEL_NAME = "BellmanFord_0:{BellmanFord_0}";

int main(int argc, char *argv[])
{
  return run_bellman_ford_benchmark(argc, argv, KERNEL_NAME);
}
