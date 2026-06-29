#include <testBench.h>

static const std::string KERNEL_NAME =
    "taskInitiator_reentry0_0:{taskInitiator_reentry0_0}";

int main(int argc, char *argv[])
{
  return run_count_decoupled_benchmark(argc, argv, KERNEL_NAME);
}
