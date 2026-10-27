# Archhitecture Generator for HardCilk

## Task Descriptors
The /taskDescriptors directory should include the task descriptor for the generator to produce the architecture corresponding to the task parameters. The directory includes descriptor examples.

## TCL for vivado
The /vivadoTcl directory includes the tcl file that connects the generated system with the HLS PEs and the differnt vivado components.

## Fine-grained CPU integration

See [CPU_INTEGRATION.md](CPU_INTEGRATION.md) for optional JSON configuration,
generated stream adapters, slab allocation, and the complete Fibonacci RTL
integration examples.

The `-k` flag also generates CPU projects. Start with the
[Fibonacci CPU application template](cpu_templates/fibonacci/README.md), then
build the generated project with `make sim`.
