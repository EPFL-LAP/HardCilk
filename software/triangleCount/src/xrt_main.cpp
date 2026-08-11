#include <testBench.h>

static const std::string KERNEL_NAME = "triangle_0:{triangle_0}";

int main(int argc, char *argv[])
{
  return run_triangle_count_benchmark(argc, argv, KERNEL_NAME);
}
