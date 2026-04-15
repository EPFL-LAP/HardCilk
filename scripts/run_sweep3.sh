#!/bin/bash

OUTPUT_DIR=../architecture-generator/output/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh

mkdir -p results/sweep3


# 5 runs for each delay of sweep3_1

# 5 runs for each delay of sweep3_2_16

# 5 runs for each delay of sweep3_2_32

# 5 runs for each delay of sweep3_2_64

# 5 runs for each delay of sweep3_2_128

# 5 runs for each delay of sweep3_3_16

# 5 runs for each delay of sweep3_3_32

# 5 runs for each delay of sweep3_3_64

# 5 runs for each delay of sweep3_3_128

# 5 runs for each delay of sweep3_4_16

# 5 runs for each delay of sweep3_4_32

# 5 runs for each delay of sweep3_4_64

# 5 runs for each delay of sweep3_4_128


parallel -j 14 <<EOF
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_1_exp3_delay16
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_1_exp3_delay32
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_1_exp3_delay64
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_1_exp3_delay128
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_1_exp3_delay256
./$OUTPUT_DIR/sweep3_1_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_1_exp3_delay512


./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_2_16_exp3_delay16
./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_2_16_exp3_delay32
./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_2_16_exp3_delay64
./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_2_16_exp3_delay128
./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_2_16_exp3_delay256
./$OUTPUT_DIR/sweep3_2_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_2_16_exp3_delay512

./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_2_32_exp3_delay16
./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_2_32_exp3_delay32
./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_2_32_exp3_delay64
./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_2_32_exp3_delay128
./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_2_32_exp3_delay256
./$OUTPUT_DIR/sweep3_2_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_2_32_exp3_delay512

./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_2_64_exp3_delay16
./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_2_64_exp3_delay32
./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_2_64_exp3_delay64
./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_2_64_exp3_delay128
./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_2_64_exp3_delay256
./$OUTPUT_DIR/sweep3_2_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_2_64_exp3_delay512

./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_2_128_exp3_delay16
./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_2_128_exp3_delay32
./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_2_128_exp3_delay64
./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_2_128_exp3_delay128
./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_2_128_exp3_delay256
./$OUTPUT_DIR/sweep3_2_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_2_128_exp3_delay512

./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_3_16_exp3_delay16
./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_3_16_exp3_delay32
./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_3_16_exp3_delay64
./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_3_16_exp3_delay128
./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_3_16_exp3_delay256
./$OUTPUT_DIR/sweep3_3_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_3_16_exp3_delay512

./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_3_32_exp3_delay16
./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_3_32_exp3_delay32
./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_3_32_exp3_delay64
./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_3_32_exp3_delay128
./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_3_32_exp3_delay256
./$OUTPUT_DIR/sweep3_3_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_3_32_exp3_delay512

./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_3_64_exp3_delay16
./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_3_64_exp3_delay32
./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_3_64_exp3_delay64
./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_3_64_exp3_delay128
./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_3_64_exp3_delay256
./$OUTPUT_DIR/sweep3_3_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_3_64_exp3_delay512

./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_3_128_exp3_delay16
./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_3_128_exp3_delay32
./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_3_128_exp3_delay64
./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_3_128_exp3_delay128
./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_3_128_exp3_delay256
./$OUTPUT_DIR/sweep3_3_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_3_128_exp3_delay512

./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_4_16_exp3_delay16
./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_4_16_exp3_delay32
./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_4_16_exp3_delay64
./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_4_16_exp3_delay128
./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_4_16_exp3_delay256
./$OUTPUT_DIR/sweep3_4_16_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_4_16_exp3_delay512

./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_4_32_exp3_delay16
./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_4_32_exp3_delay32
./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_4_32_exp3_delay64
./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_4_32_exp3_delay128
./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_4_32_exp3_delay256
./$OUTPUT_DIR/sweep3_4_32_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_4_32_exp3_delay512

./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_4_64_exp3_delay16
./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_4_64_exp3_delay32
./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_4_64_exp3_delay64
./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_4_64_exp3_delay128
./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_4_64_exp3_delay256
./$OUTPUT_DIR/sweep3_4_64_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_4_64_exp3_delay512

./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=16 > results/sweep3/sweep3_4_128_exp3_delay16
./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=32 > results/sweep3/sweep3_4_128_exp3_delay32
./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=64 > results/sweep3/sweep3_4_128_exp3_delay64
./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=128 > results/sweep3/sweep3_4_128_exp3_delay128
./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=256 > results/sweep3/sweep3_4_128_exp3_delay256
./$OUTPUT_DIR/sweep3_4_128_hardcilk_output/software/build/projects/paper_exp3/paper_exp3_systemc -Dexp3_delay=512 > results/sweep3/sweep3_4_128_exp3_delay512

EOF
