#!/bin/bash

OUTPUT_DIR=../architecture-generator/output/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh

mkdir -p results/sweep2


# 5 runs for each delay of sweep2_1

# 5 runs for each delay of sweep2_2_16

# 5 runs for each delay of sweep2_2_32

# 5 runs for each delay of sweep2_2_64

# 5 runs for each delay of sweep2_2_128

# 5 runs for each delay of sweep2_3_16

# 5 runs for each delay of sweep2_3_32

# 5 runs for each delay of sweep2_3_64

# 5 runs for each delay of sweep2_3_128

# 5 runs for each delay of sweep2_4_16

# 5 runs for each delay of sweep2_4_32

# 5 runs for each delay of sweep2_4_64

# 5 runs for each delay of sweep2_4_128


parallel -j 14 <<EOF
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_1_exp2_delay16
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_1_exp2_delay32
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_1_exp2_delay64
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_1_exp2_delay128
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_1_exp2_delay256
./$OUTPUT_DIR/sweep2_1_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_1_exp2_delay512


./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_2_16_exp2_delay16
./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_2_16_exp2_delay32
./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_2_16_exp2_delay64
./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_2_16_exp2_delay128
./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_2_16_exp2_delay256
./$OUTPUT_DIR/sweep2_2_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_2_16_exp2_delay512

./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_2_32_exp2_delay16
./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_2_32_exp2_delay32
./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_2_32_exp2_delay64
./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_2_32_exp2_delay128
./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_2_32_exp2_delay256
./$OUTPUT_DIR/sweep2_2_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_2_32_exp2_delay512

./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_2_64_exp2_delay16
./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_2_64_exp2_delay32
./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_2_64_exp2_delay64
./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_2_64_exp2_delay128
./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_2_64_exp2_delay256
./$OUTPUT_DIR/sweep2_2_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_2_64_exp2_delay512

./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_2_128_exp2_delay16
./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_2_128_exp2_delay32
./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_2_128_exp2_delay64
./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_2_128_exp2_delay128
./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_2_128_exp2_delay256
./$OUTPUT_DIR/sweep2_2_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_2_128_exp2_delay512

./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_3_16_exp2_delay16
./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_3_16_exp2_delay32
./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_3_16_exp2_delay64
./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_3_16_exp2_delay128
./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_3_16_exp2_delay256
./$OUTPUT_DIR/sweep2_3_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_3_16_exp2_delay512

./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_3_32_exp2_delay16
./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_3_32_exp2_delay32
./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_3_32_exp2_delay64
./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_3_32_exp2_delay128
./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_3_32_exp2_delay256
./$OUTPUT_DIR/sweep2_3_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_3_32_exp2_delay512

./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_3_64_exp2_delay16
./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_3_64_exp2_delay32
./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_3_64_exp2_delay64
./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_3_64_exp2_delay128
./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_3_64_exp2_delay256
./$OUTPUT_DIR/sweep2_3_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_3_64_exp2_delay512

./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_3_128_exp2_delay16
./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_3_128_exp2_delay32
./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_3_128_exp2_delay64
./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_3_128_exp2_delay128
./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_3_128_exp2_delay256
./$OUTPUT_DIR/sweep2_3_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_3_128_exp2_delay512

./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_4_16_exp2_delay16
./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_4_16_exp2_delay32
./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_4_16_exp2_delay64
./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_4_16_exp2_delay128
./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_4_16_exp2_delay256
./$OUTPUT_DIR/sweep2_4_16_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_4_16_exp2_delay512

./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_4_32_exp2_delay16
./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_4_32_exp2_delay32
./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_4_32_exp2_delay64
./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_4_32_exp2_delay128
./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_4_32_exp2_delay256
./$OUTPUT_DIR/sweep2_4_32_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_4_32_exp2_delay512

./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_4_64_exp2_delay16
./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_4_64_exp2_delay32
./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_4_64_exp2_delay64
./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_4_64_exp2_delay128
./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_4_64_exp2_delay256
./$OUTPUT_DIR/sweep2_4_64_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_4_64_exp2_delay512

./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=16 > results/sweep2/sweep2_4_128_exp2_delay16
./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=32 > results/sweep2/sweep2_4_128_exp2_delay32
./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=64 > results/sweep2/sweep2_4_128_exp2_delay64
./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=128 > results/sweep2/sweep2_4_128_exp2_delay128
./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=256 > results/sweep2/sweep2_4_128_exp2_delay256
./$OUTPUT_DIR/sweep2_4_128_hardcilk_output/software/build/projects/paper_exp2/paper_exp2_systemc -Dexp2_delay=512 > results/sweep2/sweep2_4_128_exp2_delay512

EOF
