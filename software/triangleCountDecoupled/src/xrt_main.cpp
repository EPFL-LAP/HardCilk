#include <testBench.h>

static const std::string KERNEL_NAME =
    "whileLoopMain_reentry0_0:{whileLoopMain_reentry0_0}";

int main(int argc, char *argv[])
{
  return run_triangle_count_decoupled_benchmark(argc, argv, KERNEL_NAME);
}
