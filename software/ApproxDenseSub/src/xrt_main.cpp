#include <testBench.h>

static const std::string KERNEL_NAME = "ApproxDenseSub_0:{ApproxDenseSub_0}";

int main(int argc, char *argv[])
{
  return run_approx_dense_sub_benchmark(argc, argv, KERNEL_NAME);
}
