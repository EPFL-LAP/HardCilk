#!/bin/bash
# Regenerate the HBMEX cores in rtl/ (used by TclResources.TclHbmex, HC_HBM_STRIPING=hbmex|hbmex_stripe).
#   usage: build.sh [HBMEX_REPO] [WORK_DIR]
# Builds OUT OF TREE: copies the unmodified HBMEX sources (Stripe, Enhance, IdParallelizeNoReadBurst)
# from HBMEX_REPO (default /home/shahawy/ASPLOS-27/hbmex-fccm2025) plus src/HbmexV80.scala into
# WORK_DIR (default /scratch/shahawy/v80_agent_runs/hbmex/hbmex_build), compiles it as its own sbt
# project against the locally published chext 0.1.1 / hdlinfo 0.1.0 / Chisel 6.0.0 (same as HardCilk),
# emits HbmexV80Full/HbmexV80Stripe, prefixes their modules (hbmexF_/hbmexS_) and sv2v's them into a
# QuestaSim variant (zero-initialised regs/mems, assertions on) and a SYNTHESIS variant.
# Then self-tests both cores in QuestaSim (tb_core.sv). Edit HbmexV80Config defaults to change the
# granule etc. (and TclHbmex.stripeLog2/... to match).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
HBMEX=${1:-/home/shahawy/ASPLOS-27/hbmex-fccm2025}
W=${2:-/scratch/shahawy/v80_agent_runs/hbmex/hbmex_build}
SV2V_DIR=/home/shahawy/.local/opt/hdlstuff/bin
LOCK=/home/shahawy/v80_ws/agent_runs/sbt.lock
mkdir -p $W/src/main/scala/hcv80 $W/project $W/emit
(cd $HBMEX/rtl/src/main/scala && cp --parents chext/amba/axi4/full/components/IdParallelizeNoReadBurst.scala \
   hbmex/components/stripe/Stripe.scala hbmex/components/enhance/Enhance.scala \
   hbmex/components/enhance/Components.scala hbmex/components/id_parallelize/package.scala $W/src/main/scala/)
cp $HERE/src/HbmexV80.scala $W/src/main/scala/hcv80/
cp $HERE/build.sbt.template $W/build.sbt
cp $HERE/../project/build.properties $W/project/
(cd $W && flock $LOCK sbt --batch "runMain hcv80.EmitHbmexV80 $W/emit" && sbt shutdown >/dev/null 2>&1 || true)
export PATH=$SV2V_DIR:$PATH
for v in F S; do
  top=$([ $v = F ] && echo Full || echo Stripe)
  python3 $HERE/postprocess.py $W/emit/HbmexV80$top.sv hbmex${v}_ $W/emit/hbmex${v}_sim.v -DRANDOMIZE_MEM_INIT -DRANDOMIZE_REG_INIT "-DRANDOM=32'h0"
  python3 $HERE/postprocess.py $W/emit/HbmexV80$top.sv hbmex${v}_ $W/emit/hbmex${v}_syn.v -DSYNTHESIS
  for k in sim syn; do
    { echo "// HBMEX (hbmex-fccm2025, Sonmez/Shahawy/Ienne, FCCM'25) core for the HardCilk V80 flow --"
      echo "// GENERATED, do not edit: architecture-generator/hbmex_v80/build.sh (Chisel 6.0.0, chext 0.1.1,"
      echo "// HBMEX rtl sources unmodified; modules prefixed, sv2v'd). See src/HbmexV80.scala for the config."
      echo "// Variant: hbmex${v}_$k"
      cat $W/emit/hbmex${v}_$k.v; } > $HERE/rtl/hbmex${v}_$k.v
  done
done
# self-test (QuestaSim)
T=$W/unit; mkdir -p $T; cd $T; rm -rf work work_s
vlib work >/dev/null; vlog -sv -quiet $HERE/rtl/hbmexF_sim.v $HERE/tb_core.sv
vsim -c -quiet tb -do "run -all; quit" | grep "UNIT:"
vlib work_s >/dev/null; vlog -work work_s -sv -quiet +define+STRIPE_ONLY $HERE/rtl/hbmexS_sim.v $HERE/tb_core.sv
vsim -c -quiet -lib work_s tb -do "run -all; quit" | grep "UNIT:"
