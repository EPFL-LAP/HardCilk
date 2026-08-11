#include <testBench.h>

// The root CU is `triangle`, the task that walks every vertex.
static const std::string KERNEL_NAME = "triangle_0:{triangle_0}";

int main(int argc, char *argv[])
{
  return run_full_triangle_count_benchmark(argc, argv, KERNEL_NAME);
}
