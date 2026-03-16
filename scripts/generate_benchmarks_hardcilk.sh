cd ../architecture-generator
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/graphRandomWalk.json -o ../HardCilk-output/ -g -c -r 26 -p"
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/pageRank.json -o ../HardCilk-output/ -g -c -r 22 -p"
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/triangleCount.json -o ../HardCilk-output/ -g -c -r 23 -p"