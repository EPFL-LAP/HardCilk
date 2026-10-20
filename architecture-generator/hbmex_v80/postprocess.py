#!/usr/bin/env python3
"""Prefix every module of a Chisel-emitted SV file (so it cannot clash with the
HardCilk design's own Chisel modules: Queue*, Counter, Mux, IdSerialize, ...),
then convert it to Verilog-2005 with sv2v (Vivado module_ref needs Verilog).
usage: postprocess.py in.sv prefix out.v [sv2v -D...]"""
import re, subprocess, sys
src, prefix, out = sys.argv[1], sys.argv[2], sys.argv[3]
defines = sys.argv[4:]   # e.g. -DSYNTHESIS, or the sim zero-init set
s = open(src).read()
names = re.findall(r'^\s*module\s+([A-Za-z_][A-Za-z0-9_$]*)', s, re.M)
pat = re.compile(r'\b(' + '|'.join(sorted(map(re.escape, names), key=len, reverse=True)) + r')\b')
# rename definitions and instantiations; skip string literals and comments
def fix_line(line):
    code, sep, comment = line.partition('//')
    if '"' in code:
        parts = code.split('"')
        for i in range(0, len(parts), 2):
            parts[i] = pat.sub(lambda m: prefix + m.group(1), parts[i])
        code = '"'.join(parts)
    else:
        code = pat.sub(lambda m: prefix + m.group(1), code)
    return code + sep + comment
s2 = '\n'.join(fix_line(l) for l in s.split('\n'))
tmp = out + '.prefixed.sv'
open(tmp, 'w').write(s2)
v = subprocess.run(['/home/shahawy/.local/opt/hdlstuff/bin/sv2v', *defines, tmp], check=True, capture_output=True, text=True).stdout
open(out, 'w').write('`timescale 1ns / 1ps\n' + v)
print(f"{len(names)} modules prefixed with {prefix!r} -> {out} ({len(v)} bytes)")
