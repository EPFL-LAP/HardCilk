source ~/.local/opt/hdlstuff/bin/activate-hdlstuff.sh
cd ../architecture-generator 
sbt "runMain HardCilk.mFpga_Sweep1"
sbt "runMain HardCilk.mFpga_Sweep2"
sbt "runMain HardCilk.mFpga_Sweep3"