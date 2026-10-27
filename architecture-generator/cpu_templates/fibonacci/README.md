# Fibonacci CPU application template

This directory owns the application JSON, worker logic, and host correctness
checks. It contains no generated RTL or build infrastructure. `fibonacci.json`
selects two CPUs; `fibonacci_global.json` selects three with cross-type spawning.
The same workers use generated routing constants for both architectures.

From the HardCilk `architecture-generator` directory:

```sh
sbt -batch 'runMain HardCilk.HardCilkEmitter -k -g --project-template cpu_templates/fibonacci --cpu-repo /path/to/elastic-bugra -o output/cpu-projects cpu_templates/fibonacci/fibonacci.json'
cd output/cpu-projects/fibonacci_hardcilk_output/software/cpu_fibonacci
make sim
```

For the global case, use `fibonacci_global.json`; the generated project is then
`fibonacci_global_hardcilk_output/software/cpu_fibonacci_global`. `-g` is optional:
without it, `make sim` emits runtime RTL on the first build. Set
`HDLSTUFF_PREFIX` when using a nondefault local dependency/tool prefix.

The template uses 256-bit tasks and 32-byte continuation records matching the HLS
and AIE Fibonacci examples. Four CPU threads share each firmware image but have
private stacks. The host tests 0, 1, 2, 5, 10, and 12 with randomized AXI timing.

For PE scaling, change `numProcessingElements` in a copy of the source JSON.
The generator replicates CPU instances and boot records without changes to the
application. A larger workload/PE count can require more exclusive slab grants;
change the application host initialization accordingly.
