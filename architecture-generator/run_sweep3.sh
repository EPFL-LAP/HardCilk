#!/bin/bash

OUTPUT_DIR=/beta/shahawy/output

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh


echo "Building sweep3_1"
pushd $OUTPUT_DIR/sweep3_1_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_2"
pushd $OUTPUT_DIR/sweep3_2_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_3"
pushd $OUTPUT_DIR/sweep3_3_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_4"
pushd $OUTPUT_DIR/sweep3_4_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

mkdir -p results/sweep3

parallel -j 3 <<EOF
/$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 -Dexp3_initCount=7 > results/sweep3/sweep3_1_exp3_delay16
/$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 -Dexp3_initCount=7 > results/sweep3/sweep3_1_exp3_delay32
/$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 -Dexp3_initCount=7 > results/sweep3/sweep3_1_exp3_delay64
/$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 -Dexp3_initCount=7 > results/sweep3/sweep3_1_exp3_delay128
/$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 -Dexp3_initCount=7 > results/sweep3/sweep3_1_exp3_delay256
/$OUTPUT_DIR/sweep3_2_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 -Dexp3_initCount=7 > results/sweep3/sweep3_2_exp3_delay16
/$OUTPUT_DIR/sweep3_2_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 -Dexp3_initCount=7 > results/sweep3/sweep3_2_exp3_delay32
/$OUTPUT_DIR/sweep3_2_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 -Dexp3_initCount=7 > results/sweep3/sweep3_2_exp3_delay64
/$OUTPUT_DIR/sweep3_2_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 -Dexp3_initCount=7 > results/sweep3/sweep3_2_exp3_delay128
/$OUTPUT_DIR/sweep3_2_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 -Dexp3_initCount=7 > results/sweep3/sweep3_2_exp3_delay256
/$OUTPUT_DIR/sweep3_3_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 -Dexp3_initCount=7 > results/sweep3/sweep3_3_exp3_delay16
/$OUTPUT_DIR/sweep3_3_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 -Dexp3_initCount=7 > results/sweep3/sweep3_3_exp3_delay32
/$OUTPUT_DIR/sweep3_3_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 -Dexp3_initCount=7 > results/sweep3/sweep3_3_exp3_delay64
/$OUTPUT_DIR/sweep3_3_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 -Dexp3_initCount=7 > results/sweep3/sweep3_3_exp3_delay128
/$OUTPUT_DIR/sweep3_3_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 -Dexp3_initCount=7 > results/sweep3/sweep3_3_exp3_delay256
/$OUTPUT_DIR/sweep3_4_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 -Dexp3_initCount=7 > results/sweep3/sweep3_4_exp3_delay16
/$OUTPUT_DIR/sweep3_4_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 -Dexp3_initCount=7 > results/sweep3/sweep3_4_exp3_delay32
/$OUTPUT_DIR/sweep3_4_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 -Dexp3_initCount=7 > results/sweep3/sweep3_4_exp3_delay64
/$OUTPUT_DIR/sweep3_4_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 -Dexp3_initCount=7 > results/sweep3/sweep3_4_exp3_delay128
/$OUTPUT_DIR/sweep3_4_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 -Dexp3_initCount=7 > results/sweep3/sweep3_4_exp3_delay256
EOF
