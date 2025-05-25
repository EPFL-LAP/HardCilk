#!/bin/bash

OUTPUT_DIR=output/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh

echo "Building sweep2_0"
pushd $OUTPUT_DIR/sweep2_0_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_1"
pushd $OUTPUT_DIR/sweep2_1_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_2"
pushd $OUTPUT_DIR/sweep2_2_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_3"
pushd $OUTPUT_DIR/sweep2_3_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_4"
pushd $OUTPUT_DIR/sweep2_4_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

mkdir -p results/sweep2

parallel -j 14 <<EOF
./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_0_exp2_delay128
./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_0_exp2_delay256
EOF

# ./$OUTPUT_DIR/sweep2_4_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_4_exp2_delay16


# ./$OUTPUT_DIR/sweep2_3_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_3_exp2_delay16
# ./$OUTPUT_DIR/sweep2_3_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_3_exp2_delay32
# ./$OUTPUT_DIR/sweep2_3_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_3_exp2_delay64
# ./$OUTPUT_DIR/sweep2_3_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_3_exp2_delay128
# ./$OUTPUT_DIR/sweep2_3_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_3_exp2_delay256

# ./$OUTPUT_DIR/sweep2_4_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_4_exp2_delay32
# ./$OUTPUT_DIR/sweep2_4_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_4_exp2_delay64
# ./$OUTPUT_DIR/sweep2_4_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_4_exp2_delay128
# ./$OUTPUT_DIR/sweep2_4_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_4_exp2_delay256
# ./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_0_exp2_delay16
# ./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_0_exp2_delay32
# ./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_0_exp2_delay64
# ./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_0_exp2_delay128
# ./$OUTPUT_DIR/sweep2_0_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_0_exp2_delay256
# ./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_1_exp2_delay16
# ./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_1_exp2_delay32
# ./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_1_exp2_delay64
# ./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_1_exp2_delay128
# ./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_1_exp2_delay256
# ./$OUTPUT_DIR/sweep2_2_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_2_exp2_delay16
# ./$OUTPUT_DIR/sweep2_2_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_2_exp2_delay32
# ./$OUTPUT_DIR/sweep2_2_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_2_exp2_delay64
# ./$OUTPUT_DIR/sweep2_2_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_2_exp2_delay128
# ./$OUTPUT_DIR/sweep2_2_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_2_exp2_delay256