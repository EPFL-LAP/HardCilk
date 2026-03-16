#include <pageRankDriver.h>
#include <main_helper.h>
 
static const std::string KERNEL_NAME = "pageRank_0:{pageRank_0}";
 
int main(int argc, char* argv[])
{
    return run_benchmark<pageRankDriver>(argc, argv, KERNEL_NAME);
}
 