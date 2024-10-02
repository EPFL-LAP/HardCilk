#!/bin/bash
mkdir build 
conda activate hdlstuff
cd build 
cmake .. 
make -j 
cd projects/numQueens/ 
./numQueens_systemc