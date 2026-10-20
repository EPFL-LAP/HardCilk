#!/usr/bin/env bash
# Undo v80_bringup.sh: stop and delete the MM queues on the V80 QDMA PF1, and
# optionally unload qdma-pf.
#
#   sudo ./v80_teardown.sh [--dry-run] [--queue IDX] [--unload]
#
# The card, its flash and the ami driver are left alone.
set -uo pipefail
DRY=0; QIDX=0; UNLOAD=0
HC_V80_BUILD_DIR=${HC_V80_BUILD_DIR:-/home/shahawy/v80_ws/agent_runs/software/hostdrv}
while [ $# -gt 0 ]; do
    case "$1" in
        --dry-run) DRY=1 ;;
        --queue) QIDX=${2:?}; shift ;;
        --unload) UNLOAD=1 ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
    shift
done
run() { echo "    + $*"; [ "$DRY" = 1 ] && return 0; "$@"; }
run_quiet() { echo "    + $* (errors ignored)"; [ "$DRY" = 1 ] && return 0; "$@" >/dev/null 2>&1 || true; }
[ "$DRY" = 0 ] && [ "$(id -u)" != 0 ] && { echo "run as root, or use --dry-run" >&2; exit 1; }
[ "$DRY" = 1 ] && echo "*** DRY RUN: nothing will be changed ***"

DMA_CTL=$HC_V80_BUILD_DIR/qdma/linux-kernel/bin/dma-ctl
[ -x "$DMA_CTL" ] || DMA_CTL=$(command -v dma-ctl || echo dma-ctl)

PF1=""
for d in /sys/bus/pci/devices/*; do
    [ "$(cat "$d/vendor" 2>/dev/null)" = 0x10ee ] && [ "$(cat "$d/device" 2>/dev/null)" = 0x50b5 ] && { PF1=$(basename "$d"); break; }
done
if [ -n "$PF1" ]; then
    b=${PF1#*:}; bus=$((16#${b%%:*})); b=${b#*:}; dev=$((16#${b%%.*})); fn=$((16#${b#*.}))
    QDEV=$(printf 'qdma%05x' $(( (bus << 12) | (dev << 4) | fn )))
    echo "=== Queues on $QDEV ($PF1)"
    for idx in "$QIDX" $((QIDX + 1)); do
        [ -e "/dev/$QDEV-MM-$idx" ] || [ "$DRY" = 1 ] || continue
        for dir in bi h2c c2h; do
            run_quiet "$DMA_CTL" "$QDEV" q stop idx "$idx" dir "$dir"
            run_quiet "$DMA_CTL" "$QDEV" q del idx "$idx" dir "$dir"
        done
    done
    ls /dev/"$QDEV"-* 2>/dev/null | sed 's/^/    still present: /'
else
    echo "=== No 10ee:50b5 function: no queues to remove"
fi
if [ "$UNLOAD" = 1 ]; then
    echo "=== Unloading qdma-pf"
    if [ -d /sys/module/qdma_pf ] || [ "$DRY" = 1 ]; then run rmmod qdma_pf; else echo "    not loaded"; fi
fi
echo "done"
