#!/bin/bash

OUTPUT_DIR=/beta/shahawy/$OUTPUT_DIR/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh


echo "Building sweep1_1"
pushd $OUTPUT_DIR/sweep1_1_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_2"
pushd $OUTPUT_DIR/sweep1_2_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_3"
pushd $OUTPUT_DIR/sweep1_3_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_4"
pushd $OUTPUT_DIR/sweep1_4_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

mkdir -p results/sweep1

parallel -j 2 <<EOF
/$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_1_exp1_delay16
/$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_1_exp1_delay32
/$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_1_exp1_delay64
/$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_1_exp1_delay128
/$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_1_exp1_delay256
/$OUTPUT_DIR/sweep1_2_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_2_exp1_delay16
/$OUTPUT_DIR/sweep1_2_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_2_exp1_delay32
/$OUTPUT_DIR/sweep1_2_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_2_exp1_delay64
/$OUTPUT_DIR/sweep1_2_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_2_exp1_delay128
/$OUTPUT_DIR/sweep1_2_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_2_exp1_delay256
/$OUTPUT_DIR/sweep1_3_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_3_exp1_delay16
/$OUTPUT_DIR/sweep1_3_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_3_exp1_delay32
/$OUTPUT_DIR/sweep1_3_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_3_exp1_delay64
/$OUTPUT_DIR/sweep1_3_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_3_exp1_delay128
/$OUTPUT_DIR/sweep1_3_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_3_exp1_delay256
/$OUTPUT_DIR/sweep1_4_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_4_exp1_delay16
/$OUTPUT_DIR/sweep1_4_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_exp1_delay32
/$OUTPUT_DIR/sweep1_4_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_exp1_delay64
/$OUTPUT_DIR/sweep1_4_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_exp1_delay128
/$OUTPUT_DIR/sweep1_4_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_exp1_delay256
EOF
