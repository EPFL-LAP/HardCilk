#include <graphRandomWalkDriver.h>
#include <main_helper.h>
 
static const std::string KERNEL_NAME = "graphRandomWalk_0:{graphRandomWalk_0}";
 
int main(int argc, char* argv[])
{
    return run_benchmark<graphRandomWalkDriver>(argc, argv, KERNEL_NAME);
}
 