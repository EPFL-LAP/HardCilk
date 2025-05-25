#!/bin/bash

OUTPUT_DIR=output/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh


# echo "Building sweep1_1"
# pushd $OUTPUT_DIR/sweep1_1_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_2_16"
# pushd $OUTPUT_DIR/sweep1_2_16_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_2_32"
# pushd $OUTPUT_DIR/sweep1_2_32_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_2_64"
# pushd $OUTPUT_DIR/sweep1_2_64_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_2_128"
# pushd $OUTPUT_DIR/sweep1_2_128_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_3_16"
# pushd $OUTPUT_DIR/sweep1_3_16_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_3_32"
# pushd $OUTPUT_DIR/sweep1_3_32_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_3_64"
# pushd $OUTPUT_DIR/sweep1_3_64_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_3_128"
# pushd $OUTPUT_DIR/sweep1_3_128_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd


# echo "Building sweep1_4_16"
# pushd $OUTPUT_DIR/sweep1_4_16_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_4_32"
# pushd $OUTPUT_DIR/sweep1_4_32_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_4_64"
# pushd $OUTPUT_DIR/sweep1_4_64_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd

# echo "Building sweep1_4_128"
# pushd $OUTPUT_DIR/sweep1_4_128_hardcilk_output/software
# mkdir -p build/
# pushd build
# cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
# ninja
# popd
# popd


mkdir -p results/sweep1

parallel -j 14 <<EOF
./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_64_exp1_delay32
./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_64_exp1_delay64
./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_32_exp1_delay256
./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_32_exp1_delay128
./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_4_16_exp1_delay512
./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_16_exp1_delay256
./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_16_exp1_delay128
EOF

# parallel -j 14 <<EOF
# # 5 runs for each delay of sweep1_1
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_1_exp1_delay16
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_1_exp1_delay32
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_1_exp1_delay64
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_1_exp1_delay128
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_1_exp1_delay256
# ./$OUTPUT_DIR/sweep1_1_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_1_exp1_delay512


# # 5 runs for each delay of sweep1_2_16
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_2_16_exp1_delay16
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_2_16_exp1_delay32
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_2_16_exp1_delay64
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_2_16_exp1_delay128
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_2_16_exp1_delay256
# ./$OUTPUT_DIR/sweep1_2_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_2_16_exp1_delay512

# # 5 runs for each delay of sweep1_2_32
# ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_2_32_exp1_delay16
# ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_2_32_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_2_32_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_2_32_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_2_32_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_2_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_2_32_exp1_delay512

# # 5 runs for each delay of sweep1_2_64
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_2_64_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_2_64_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_2_64_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_2_64_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_2_64_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_2_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_2_64_exp1_delay512

# # 5 runs for each delay of sweep1_2_128
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_2_128_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_2_128_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_2_128_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_2_128_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_2_128_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_2_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_2_128_exp1_delay512

# # 5 runs for each delay of sweep1_3_16
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_3_16_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_3_16_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_3_16_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_3_16_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_3_16_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_3_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_3_16_exp1_delay512

# # 5 runs for each delay of sweep1_3_32
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_3_32_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_3_32_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_3_32_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_3_32_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_3_32_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_3_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_3_32_exp1_delay512

# # 5 runs for each delay of sweep1_3_64
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_3_64_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_3_64_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_3_64_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_3_64_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_3_64_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_3_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_3_64_exp1_delay512

# # 5 runs for each delay of sweep1_3_128
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_3_128_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_3_128_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_3_128_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_3_128_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_3_128_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_3_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_3_128_exp1_delay512

# # 5 runs for each delay of sweep1_4_16
# # ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_4_16_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_16_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_16_exp1_delay64
# ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_16_exp1_delay128
# ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_16_exp1_delay256
# ./$OUTPUT_DIR/sweep1_4_16_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_4_16_exp1_delay512

# # 5 runs for each delay of sweep1_4_32
# # ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_4_32_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_32_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_32_exp1_delay64
# ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_32_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_32_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_4_32_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_4_32_exp1_delay512

# # 5 runs for each delay of sweep1_4_64
# # ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_4_64_exp1_delay16
# ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_64_exp1_delay32
# ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_64_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_64_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_64_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_4_64_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_4_64_exp1_delay512

# # 5 runs for each delay of sweep1_4_128
# ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=16 > results/sweep1/sweep1_4_128_exp1_delay16
# # ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=32 > results/sweep1/sweep1_4_128_exp1_delay32
# # ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=64 > results/sweep1/sweep1_4_128_exp1_delay64
# # ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=128 > results/sweep1/sweep1_4_128_exp1_delay128
# # ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=256 > results/sweep1/sweep1_4_128_exp1_delay256
# # ./$OUTPUT_DIR/sweep1_4_128_hardcilk_output/software/build/projects/paper_exp1/paper_exp1_systemc -Dexp1_delay=512 > results/sweep1/sweep1_4_128_exp1_delay512

# EOF
