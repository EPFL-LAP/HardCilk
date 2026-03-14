#!/bin/bash

OUTPUT_DIR=../architecture-generator/output/

. $HOME/.local/opt/hdlstuff/bin/activate-hdlstuff.sh


echo "Building sweep1_1"
pushd $OUTPUT_DIR/sweep1_1_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_2_16"
pushd $OUTPUT_DIR/sweep1_2_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_2_32"
pushd $OUTPUT_DIR/sweep1_2_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_2_64"
pushd $OUTPUT_DIR/sweep1_2_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_2_128"
pushd $OUTPUT_DIR/sweep1_2_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_3_16"
pushd $OUTPUT_DIR/sweep1_3_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_3_32"
pushd $OUTPUT_DIR/sweep1_3_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_3_64"
pushd $OUTPUT_DIR/sweep1_3_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_3_128"
pushd $OUTPUT_DIR/sweep1_3_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd


echo "Building sweep1_4_16"
pushd $OUTPUT_DIR/sweep1_4_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_4_32"
pushd $OUTPUT_DIR/sweep1_4_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_4_64"
pushd $OUTPUT_DIR/sweep1_4_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep1_4_128"
pushd $OUTPUT_DIR/sweep1_4_128_hardcilk_output/software
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

echo "Building sweep2_2_16"
pushd $OUTPUT_DIR/sweep2_2_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_2_32"
pushd $OUTPUT_DIR/sweep2_2_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_2_64"
pushd $OUTPUT_DIR/sweep2_2_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_2_128"
pushd $OUTPUT_DIR/sweep2_2_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_3_16"
pushd $OUTPUT_DIR/sweep2_3_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_3_32"
pushd $OUTPUT_DIR/sweep2_3_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_3_64"
pushd $OUTPUT_DIR/sweep2_3_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_3_128"
pushd $OUTPUT_DIR/sweep2_3_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd


echo "Building sweep2_4_16"
pushd $OUTPUT_DIR/sweep2_4_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_4_32"
pushd $OUTPUT_DIR/sweep2_4_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_4_64"
pushd $OUTPUT_DIR/sweep2_4_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep2_4_128"
pushd $OUTPUT_DIR/sweep2_4_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd


echo "Building sweep3_1"
pushd $OUTPUT_DIR/sweep3_1_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_2_16"
pushd $OUTPUT_DIR/sweep3_2_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_2_32"
pushd $OUTPUT_DIR/sweep3_2_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_2_64"
pushd $OUTPUT_DIR/sweep3_2_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_2_128"
pushd $OUTPUT_DIR/sweep3_2_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_3_16"
pushd $OUTPUT_DIR/sweep3_3_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_3_32"
pushd $OUTPUT_DIR/sweep3_3_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_3_64"
pushd $OUTPUT_DIR/sweep3_3_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_3_128"
pushd $OUTPUT_DIR/sweep3_3_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd


echo "Building sweep3_4_16"
pushd $OUTPUT_DIR/sweep3_4_16_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_4_32"
pushd $OUTPUT_DIR/sweep3_4_32_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_4_64"
pushd $OUTPUT_DIR/sweep3_4_64_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd

echo "Building sweep3_4_128"
pushd $OUTPUT_DIR/sweep3_4_128_hardcilk_output/software
mkdir -p build/
pushd build
cmake .. -DCMAKE_PREFIX_PATH=$HDLSTUFF_PREFIX -G Ninja
ninja
popd
popd
