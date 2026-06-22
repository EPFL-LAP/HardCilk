#include <testBench.h>

static const std::string KERNEL_NAME =
    "MaximalIndependentSet_0:{MaximalIndependentSet_0}";

int main(int argc, char *argv[])
{
  return run_mis_benchmark(argc, argv, KERNEL_NAME);
}
