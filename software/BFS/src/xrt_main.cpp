#include <testBench.h>

// Single user-managed compute unit. Matches nk=BFS_0:1:BFS_0 in conn_u55c.cfg
// and the <kernel name="BFS_0"> in user_0.xml.
static const std::string KERNEL_NAME = "BFS_0:{BFS_0}";

int main(int argc, char *argv[]) {
  return run_bfs_benchmark(argc, argv, KERNEL_NAME);
}
