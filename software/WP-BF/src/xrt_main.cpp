#include <testBench.h>

static const std::string KERNEL_NAME = "WidestPath_0:{WidestPath_0}";

int main(int argc, char *argv[])
{
  return run_widest_path_benchmark(argc, argv, KERNEL_NAME);
}
