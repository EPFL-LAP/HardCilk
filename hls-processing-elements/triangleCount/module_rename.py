import re

with open("vertex_map.sv", "r") as f:
    code = f.read()

# 1. Prefix module names in module declarations
code = re.sub(r'\bmodule\s+(\w+)', r'module _\1', code)

# 2. Prefix module names in instantiations (multiline-safe)
# Match: start of line, <module_name> <instance_name> (
code = re.sub(
    r'(?m)^(\s*)(?!_)(\w+)\s+(\w+)\s*\(\s*(?://.*)?$',
    r'\1_\2 \3(',
    code
)

code = re.sub("_module", "module", code)

with open("verilog-chext/vertex_map.sv", "w") as f:
    f.write(code)
