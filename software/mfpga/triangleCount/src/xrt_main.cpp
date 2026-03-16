#include <triangleCountDriver.h>
#include <main_helper.h>
 
static const std::string KERNEL_NAME = "triangleCount_0:{triangleCount_0}";
 
int main(int argc, char* argv[])
{
    return run_benchmark<triangleCountDriver>(argc, argv, KERNEL_NAME);
}
 