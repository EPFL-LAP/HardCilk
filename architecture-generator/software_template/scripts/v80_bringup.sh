#!/usr/bin/env bash
# Bring an Alveo V80 (AVED shell, CPM5 QDMA) up for the HardCilk V80 host
# programs (<spec>_v80, hc_v80_probe). Run it as root:
#
#   sudo ./v80_bringup.sh [--flash <image.pdi> [--partition N]] [options]
#
# What it does. Each step prints what it runs, and each step can be re-run safely:
#   1. Builds qdma-pf.ko (and ami.ko when needed) for the running kernel, out of
#      tree in $HC_V80_BUILD_DIR. It rebuilds only when the module is missing or
#      was built for another kernel. The build runs serially, as $SUDO_USER.
#   2. Makes sure PF0 (10ee:50b4) is driven by `ami` and not by `xclmgmt`.
#   3. Optional --flash: programs the image into flash with ami_tool and boots it.
#      Optional --boot N: boots partition N without programming anything.
#   4. Loads qdma-pf.ko and waits for PF1 (10ee:50b5) to appear and bind.
#   5. Sets qmax, then (re)creates one bidirectional MM queue (idx 0 by default).
#      That gives /dev/qdma<bbddf>-MM-0, which QdmaMemory uses for both H2C and C2H.
#      --split-queues instead creates the old layout: idx 0 = H2C, idx 1 = C2H.
#   6. Gives the invoking user access to the queue node and to PF1 BAR2
#      (resource2, the HardCilk register window).
#   7. Prints a status summary.
#
# --dry-run prints the plan and the current state without changing anything,
# and works without root.
set -uo pipefail

# ---- configuration (environment overrides) ---------------------------------
QDMA_SRC=${QDMA_SRC:-/home/shahawy/v80_ws/repos/Xilinx/dma_ip_drivers/QDMA/linux-kernel}
AMI_SRC=${AMI_SRC:-/home/shahawy/AVED/sw/AMI}
HC_V80_BUILD_DIR=${HC_V80_BUILD_DIR:-/home/shahawy/v80_ws/agent_runs/software/hostdrv}
KREL=$(uname -r)

DRY=0
FLASH=""
PARTITION=0
BOOT=""
QIDX=0
QMAX=64
SPLIT=0
OWNER=${SUDO_USER:-}
QDMA_MODE=""
RELOAD_QDMA=0
NO_BUILD=0
PF0=""

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
    cat <<EOF

Options:
  --dry-run            print the steps, change nothing
  --flash <pdi>        program <pdi> with ami_tool, then boot it (see --partition)
  --partition N        flash partition (default 0). These are FPT entries; the
                       stock AVED fpt.json has p0 and p1.
                         FPT image (fpt_pdi_gen.py output, e.g. pcie/output/<name>.pdi):
                           N=0: ami_tool cfgmem_fpt (rewrites FPT and p0)
                           N>0: the FPT header (first 32 KB) is stripped, then
                                ami_tool cfgmem_program -p N
                         plain PDI (*_nofpt.pdi): ami_tool cfgmem_program -p N
  --boot N             no flashing: ami_tool device_boot -p N (hot reset into partition N)
  --queue IDX          MM queue index (default 0)
  --split-queues       idx IDX = H2C and idx IDX+1 = C2H (old scripts); export
                       HC_QDMA_C2H=/dev/qdma<bdf>-MM-<IDX+1> for the host
  --qmax N             queues for PF1 (default 64)
  --qdma-mode M        qdma-pf mode for PF1: 0 auto, 1 poll, 2 direct intr, 3 indirect intr
  --reload-qdma        rmmod and re-insmod qdma-pf even if it is already loaded
  --owner USER         user to give device access to (default \$SUDO_USER; none = 0666)
  --pf0 BDF            PF0 BDF (default: auto-detect 10ee:50b4)
  --no-build           never build modules; use what is there
Environment: QDMA_SRC, AMI_SRC, HC_V80_BUILD_DIR (currently $HC_V80_BUILD_DIR)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1 ;;
        --flash) FLASH=${2:?}; shift ;;
        --partition) PARTITION=${2:?}; shift ;;
        --boot) BOOT=${2:?}; shift ;;
        --queue) QIDX=${2:?}; shift ;;
        --split-queues) SPLIT=1 ;;
        --qmax) QMAX=${2:?}; shift ;;
        --qdma-mode) QDMA_MODE=${2:?}; shift ;;
        --reload-qdma) RELOAD_QDMA=1 ;;
        --owner) OWNER=${2:?}; shift ;;
        --pf0) PF0=${2:?}; shift ;;
        --no-build) NO_BUILD=1 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage; exit 2 ;;
    esac
    shift
done

# ---- helpers ----------------------------------------------------------------
STEP=0
step() { STEP=$((STEP + 1)); echo; echo "=== [$STEP] $*"; }
info() { echo "    $*"; }
warn() { echo "    WARNING: $*" >&2; }
die()  { echo "    ERROR: $*" >&2; exit 1; }
# run CMD...: echo it, and execute it unless --dry-run.
run() {
    echo "    + $*"
    [ "$DRY" = 1 ] && return 0
    "$@"
}
# run_sh 'shell string' (for redirections)
run_sh() {
    echo "    + $1"
    [ "$DRY" = 1 ] && return 0
    bash -c "$1"
}
# run_quiet CMD...: like run, but discards the command's output and status.
run_quiet() {
    echo "    + $* (errors ignored)"
    [ "$DRY" = 1 ] && return 0
    "$@" >/dev/null 2>&1 || true
}
# as_user CMD...: build steps run as the invoking user, so the build tree stays theirs.
as_user() {
    if [ -n "${SUDO_USER:-}" ] && [ "$(id -u)" = 0 ]; then run sudo -u "$SUDO_USER" "$@"; else run "$@"; fi
}

find_fn() { # vendor device -> first BDF
    local d
    for d in /sys/bus/pci/devices/*; do
        [ "$(cat "$d/vendor" 2>/dev/null)" = "0x$1" ] && [ "$(cat "$d/device" 2>/dev/null)" = "0x$2" ] &&
            { basename "$d"; return 0; }
    done
    return 1
}
drv_of() { local l; l=$(readlink "/sys/bus/pci/devices/$1/driver" 2>/dev/null) && basename "$l" || echo "(none)"; }
mod_loaded() { [ -d "/sys/module/$1" ]; }
ko_vermagic() { modinfo -F vermagic "$1" 2>/dev/null | awk '{print $1}'; }
qdma_name() { # 0000:71:00.1 -> qdma71001 (dma_ip_drivers libqdma/xdev.c: (bus<<12)|(dev<<4)|fn, %05x)
    local b=${1#*:} bus dev fn
    bus=$((16#${b%%:*})); b=${b#*:}; dev=$((16#${b%%.*})); fn=$((16#${b#*.}))
    printf 'qdma%05x' $(( (bus << 12) | (dev << 4) | fn ))
}

if [ "$DRY" = 0 ] && [ "$(id -u)" != 0 ]; then
    die "run as root (sudo $0 ...), or use --dry-run"
fi
[ "$DRY" = 1 ] && echo "*** DRY RUN: nothing will be changed ***"

QDMA_KO=$HC_V80_BUILD_DIR/qdma/linux-kernel/bin/qdma-pf.ko
DMA_CTL=$HC_V80_BUILD_DIR/qdma/linux-kernel/bin/dma-ctl
AMI_KO=$HC_V80_BUILD_DIR/ami/AMI/driver/ami.ko

# ---- 0. what is there --------------------------------------------------------
step "Detecting the V80"
[ -n "$PF0" ] || PF0=$(find_fn 10ee 50b4 || true)
case "$PF0" in ????:*) ;; ?*) PF0="0000:$PF0" ;; esac
[ -n "$PF0" ] || die "no 10ee:50b4 (V80 PF0) found in /sys/bus/pci/devices"
BUS=${PF0#*:}; BUS=${BUS%%:*}
PF1_EXPECTED="${PF0%.*}.1"
info "PF0 $PF0 driver: $(drv_of "$PF0")   (expected PF1: $PF1_EXPECTED)"
PF1=$(find_fn 10ee 50b5 || true)
info "PF1 ${PF1:-absent}${PF1:+ driver: $(drv_of "$PF1")}"
info "kernel $KREL"

# ---- 1. modules ------------------------------------------------------------
step "Kernel modules for $KREL"
build_qdma() {
    [ -d "$QDMA_SRC/driver" ] || die "QDMA driver source not found at $QDMA_SRC (set QDMA_SRC)"
    as_user mkdir -p "$HC_V80_BUILD_DIR/qdma"
    as_user rsync -a --delete --exclude .git "$QDMA_SRC/" "$HC_V80_BUILD_DIR/qdma/linux-kernel/"
    # The QDMA makefiles break under parallel make: build serially.
    as_user make -C "$HC_V80_BUILD_DIR/qdma/linux-kernel" -j1 driver apps
}
if [ -f "$QDMA_KO" ] && [ "$(ko_vermagic "$QDMA_KO")" = "$KREL" ]; then
    info "qdma-pf.ko up to date: $QDMA_KO"
elif [ "$NO_BUILD" = 1 ]; then
    die "no qdma-pf.ko for $KREL at $QDMA_KO and --no-build given"
else
    info "building qdma-pf.ko (have: $( [ -f "$QDMA_KO" ] && ko_vermagic "$QDMA_KO" || echo none))"
    build_qdma
fi
if ! modinfo -F alias "$QDMA_KO" 2>/dev/null | grep -qi 'd000050B5'; then
    [ "$DRY" = 1 ] || die "$QDMA_KO does not claim 10ee:50b5 (needs the V80 entry in driver/src/pci_ids.h)"
fi
[ -x "$DMA_CTL" ] || DMA_CTL=$(command -v dma-ctl || echo "$DMA_CTL")
info "dma-ctl: $DMA_CTL"

# AMI: use the installed module if it matches this kernel, else build one.
AMI_INSTALLED_VM=$(modinfo -k "$KREL" -F vermagic ami 2>/dev/null | awk '{print $1}')
if [ "$AMI_INSTALLED_VM" = "$KREL" ]; then
    AMI_LOAD="modprobe ami"
    info "installed ami module matches $KREL"
else
    [ -n "$AMI_INSTALLED_VM" ] && info "installed ami module was built for $AMI_INSTALLED_VM, not $KREL"
    if [ -f "$AMI_KO" ] && [ "$(ko_vermagic "$AMI_KO")" = "$KREL" ]; then
        info "ami.ko up to date: $AMI_KO"
    elif [ "$NO_BUILD" = 1 ]; then
        die "no ami.ko for $KREL and --no-build given"
    else
        [ -d "$AMI_SRC/driver" ] || die "AMI source not found at $AMI_SRC (set AMI_SRC)"
        as_user mkdir -p "$HC_V80_BUILD_DIR/ami"
        as_user rsync -a --delete --exclude .git "$AMI_SRC/" "$HC_V80_BUILD_DIR/ami/AMI/"
        as_user make -C "$HC_V80_BUILD_DIR/ami/AMI/driver"
    fi
    AMI_LOAD="insmod $AMI_KO"
fi

# ---- 2. PF0 -> ami ---------------------------------------------------------
ensure_ami() {
    local cur
    cur=$(drv_of "$PF0")
    if [ "$cur" = xclmgmt ]; then
        info "PF0 is bound to xclmgmt (XRT/VMR): unbinding"
        run_sh "echo $PF0 > /sys/bus/pci/drivers/xclmgmt/unbind"
    fi
    # xclmgmt would grab the card back after the remove/rescan of a hot reset.
    if mod_loaded xclmgmt; then
        if [ -z "$(ls /sys/bus/pci/drivers/xclmgmt 2>/dev/null | grep -E '^[0-9a-f]{4}:')" ] || [ "$DRY" = 1 ]; then
            run rmmod xclmgmt || warn "rmmod xclmgmt failed (still in use?)"
        else
            warn "xclmgmt still drives other devices; not unloading it (a rescan may rebind PF0 to it)"
        fi
    fi
    if ! mod_loaded ami; then
        # shellcheck disable=SC2086
        run $AMI_LOAD || die "loading ami failed ($AMI_LOAD); see dmesg"
    fi
    [ "$DRY" = 1 ] || sleep 1
    if [ "$(drv_of "$PF0")" != ami ] && [ "$DRY" = 0 ]; then
        run_sh "echo $PF0 > /sys/bus/pci/drivers/ami/bind" || true
        sleep 1
    fi
    [ "$DRY" = 1 ] || [ "$(drv_of "$PF0")" = ami ] || die "PF0 $PF0 is not bound to ami (now: $(drv_of "$PF0")); see dmesg"
    info "PF0 driver: $(drv_of "$PF0")"
}
step "PF0 $PF0 -> ami"
ensure_ami

# ---- 3. qdma-pf (before booting, so PF1 binds as soon as it appears) --------
step "qdma-pf driver"
QDMA_PARAMS="master_pf=0x$BUS:1"
[ -n "$QDMA_MODE" ] && QDMA_PARAMS="$QDMA_PARAMS mode=0x$BUS:1:$QDMA_MODE"
if grep -qs 'master_pf=0x[0-9a-fA-F]*:0' /etc/modprobe.d/*.conf; then
    warn "/etc/modprobe.d sets qdma-pf master_pf=<bus>:0, but PF0 is AMI here. This script insmods with"
    warn "$QDMA_PARAMS; a plain 'modprobe qdma-pf' would make PF1 a non-master function"
fi
if mod_loaded qdma_pf && [ "$RELOAD_QDMA" = 1 ]; then
    run rmmod qdma_pf || die "rmmod qdma_pf failed (queues in use? run v80_teardown.sh)"
fi
if mod_loaded qdma_pf && [ "$RELOAD_QDMA" = 0 ]; then
    info "qdma_pf already loaded (version $(cat /sys/module/qdma_pf/version 2>/dev/null)); --reload-qdma to replace it"
else
    # shellcheck disable=SC2086
    run insmod "$QDMA_KO" $QDMA_PARAMS || die "insmod qdma-pf failed; see dmesg"
fi

# ---- 4. optional flash / boot ----------------------------------------------
wait_pf1() {
    local i
    [ "$DRY" = 1 ] && { PF1=${PF1:-$PF1_EXPECTED}; return 0; }
    for i in $(seq 1 30); do
        PF1=$(find_fn 10ee 50b5 || true)
        [ -n "$PF1" ] && return 0
        [ "$i" = 5 ] && run_sh "echo 1 > /sys/bus/pci/rescan"
        sleep 1
    done
    return 1
}
boot_partition() {
    run ami_tool device_boot -d "$PF0" -p "$1" || die "ami_tool device_boot -p $1 failed"
    # device_boot hot-resets the card and does the sysfs remove/rescan itself.
    # Rescan once more in case the PF1 enumeration came late.
    run_sh "echo 1 > /sys/bus/pci/rescan"
    [ "$DRY" = 1 ] || sleep 2
    ensure_ami
}
if [ -n "$FLASH" ]; then
    step "Flashing $FLASH (partition $PARTITION)"
    [ -f "$FLASH" ] || die "$FLASH not found"
    MAGIC=$(od -An -tx4 -N4 "$FLASH" | tr -d ' ')
    info "image: $(stat -c %s "$FLASH") bytes, first word 0x$MAGIC"
    run ami_tool cfgmem_info -d "$PF0" -t primary || warn "cfgmem_info failed: is the AVED AMC running?"
    if [ "$MAGIC" = 92f7a516 ]; then
        info "FPT image (fpt_pdi_gen.py: FPT padded to 32 KB, then the PDI)"
        if [ "$PARTITION" = 0 ]; then
            run ami_tool cfgmem_fpt -d "$PF0" -t primary -i "$FLASH" -y || die "cfgmem_fpt failed"
        else
            NOFPT=$HC_V80_BUILD_DIR/$(basename "${FLASH%.pdi}")_nofpt.pdi
            as_user mkdir -p "$HC_V80_BUILD_DIR"
            run_sh "tail -c +32769 '$FLASH' > '$NOFPT'"
            run ami_tool cfgmem_program -d "$PF0" -t primary -i "$NOFPT" -p "$PARTITION" -y -q ||
                die "cfgmem_program failed"
        fi
    else
        info "plain PDI: written raw at partition $PARTITION's base"
        run ami_tool cfgmem_program -d "$PF0" -t primary -i "$FLASH" -p "$PARTITION" -y -q ||
            die "cfgmem_program failed"
    fi
    step "Booting partition $PARTITION"
    boot_partition "$PARTITION"
elif [ -n "$BOOT" ]; then
    step "Booting partition $BOOT"
    boot_partition "$BOOT"
fi

# ---- 5. PF1 ----------------------------------------------------------------
step "PF1 (10ee:50b5, QDMA)"
wait_pf1 || die "PF1 did not appear. The loaded image is not a HardCilk/AVED QDMA design. Flash one with --flash, or boot one with --boot N (e.g. --boot 0)"
info "PF1 $PF1 driver: $(drv_of "$PF1")"
if [ "$DRY" = 0 ] && [ "$(drv_of "$PF1")" != qdma-pf ]; then
    run_sh "echo $PF1 > /sys/bus/pci/drivers/qdma-pf/bind" || true
    sleep 1
    [ "$(drv_of "$PF1")" = qdma-pf ] || die "PF1 is not bound to qdma-pf (now: $(drv_of "$PF1")); see dmesg"
fi
QDEV=$(qdma_name "$PF1")
info "QDMA device name: $QDEV"

# ---- 6. queues -------------------------------------------------------------
step "MM queue(s) on $QDEV"
if [ "$SPLIT" = 1 ]; then
    QUEUES="$QIDX:h2c $((QIDX + 1)):c2h"
else
    QUEUES="$QIDX:bi"
fi
# Recreate rather than probe the state: stop/del of an absent queue just fails.
for q in $QUEUES; do
    idx=${q%%:*}; dir=${q#*:}
    for d in bi h2c c2h; do
        if [ -e "/dev/$QDEV-MM-$idx" ] || [ "$DRY" = 1 ]; then
            run_quiet "$DMA_CTL" "$QDEV" q stop idx "$idx" dir "$d"
            run_quiet "$DMA_CTL" "$QDEV" q del idx "$idx" dir "$d"
        fi
    done
done
QMAX_FILE=/sys/bus/pci/devices/$PF1/qdma/qmax
CURQ=$(cat "$QMAX_FILE" 2>/dev/null || echo "?")
NEED=$((QIDX + 2))
if [ "$CURQ" = "?" ] || [ "$CURQ" -lt "$NEED" ] 2>/dev/null; then
    [ "$QMAX" -lt "$NEED" ] && QMAX=$NEED
    run_sh "echo $QMAX > $QMAX_FILE" || die "setting qmax failed; see dmesg"
else
    info "qmax already $CURQ"
fi
for q in $QUEUES; do
    idx=${q%%:*}; dir=${q#*:}
    run "$DMA_CTL" "$QDEV" q add idx "$idx" mode mm dir "$dir" || die "q add idx $idx dir $dir failed"
    run "$DMA_CTL" "$QDEV" q start idx "$idx" dir "$dir" || die "q start idx $idx dir $dir failed"
done
[ "$DRY" = 1 ] || sleep 0.5

# ---- 7. permissions --------------------------------------------------------
step "Device access for ${OWNER:-everyone}"
NODES=""
for q in $QUEUES; do NODES="$NODES /dev/$QDEV-MM-${q%%:*}"; done
BAR=/sys/bus/pci/devices/$PF1/resource2
for f in $NODES $BAR; do
    if [ -n "$OWNER" ]; then
        run chown "$OWNER" "$f"; run chmod 0600 "$f"
    else
        run chmod 0666 "$f"
    fi
done

# ---- 8. summary --------------------------------------------------------------
step "Status"
info "PF0 $PF0 -> $(drv_of "$PF0")   PF1 ${PF1:-?} -> $(drv_of "${PF1:-x}")"
[ "$DRY" = 1 ] || info "qmax $(cat "$QMAX_FILE" 2>/dev/null)"
for f in $NODES $BAR; do info "$(ls -l "$f" 2>/dev/null || echo "$f (not present)")"; done
if [ "$DRY" = 0 ]; then
    "$DMA_CTL" dev list 2>/dev/null | sed 's/^/    /'
    "$DMA_CTL" "$QDEV" q list 0 "$NEED" 2>/dev/null | sed 's/^/    /'
    ami_tool overview 2>/dev/null | sed 's/^/    /' | head -20
    dmesg | grep -iE 'qdma|ami' | tail -5 | sed 's/^/    dmesg: /'
fi
echo
echo "Host environment for <spec>_v80 / hc_v80_probe:"
echo "  export HC_QDMA_BDF=${PF1:-$PF1_EXPECTED}"
if [ "$SPLIT" = 1 ]; then
    echo "  export HC_QDMA_H2C=/dev/$QDEV-MM-$QIDX HC_QDMA_C2H=/dev/$QDEV-MM-$((QIDX + 1))"
elif [ "$QIDX" != 0 ]; then
    echo "  export HC_QDMA_QUEUE=$QIDX"
fi
echo "First check: <software build>/driver/hc_v80_probe   (overwrites test regions of HBM)"
