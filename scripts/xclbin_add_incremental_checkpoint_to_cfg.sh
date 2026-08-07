#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <input_cfg> <checkpoint_dcp> <output_cfg>" >&2
    exit 2
fi

input_cfg="$1"
checkpoint_dcp="$2"
output_cfg="$3"

mkdir -p "$(dirname "$output_cfg")"

awk -v checkpoint="$checkpoint_dcp" '
function emit_prop() {
    if (!inserted) {
        print "prop=run.impl_1.INCREMENTAL_CHECKPOINT=" checkpoint
        inserted = 1
    }
}

BEGIN {
    in_vivado = 0
    saw_vivado = 0
    inserted = 0
}

/^[[:space:]]*prop[[:space:]]*=[[:space:]]*run\.impl_1\.INCREMENTAL_CHECKPOINT[[:space:]]*=/ {
    next
}

/^[[:space:]]*\[vivado\][[:space:]]*$/ {
    saw_vivado = 1
    in_vivado = 1
    print
    next
}

/^[[:space:]]*\[[^]]+\][[:space:]]*$/ {
    if (in_vivado) {
        emit_prop()
        in_vivado = 0
    }
    print
    next
}

{
    print
}

END {
    if (in_vivado) {
        emit_prop()
    } else if (!saw_vivado) {
        print ""
        print "[vivado]"
        emit_prop()
    }
}
' "$input_cfg" > "$output_cfg"
