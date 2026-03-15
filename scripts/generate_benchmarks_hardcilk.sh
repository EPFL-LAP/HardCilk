cd ../architecture-generator
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/graphRandomWalk.json -o ../HardCilk-output/ -g -c -r 26 -p"
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/pageRank.json -o ../HardCilk-output/ -g -c -r 4 -p"
sbt "runMain HardCilk.HardCilkEmitter taskDescriptors/mfpga/triangleCount.json -o ../HardCilk-output/ -g -c -r 25 -p"