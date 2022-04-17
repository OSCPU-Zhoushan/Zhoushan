# Zhoushan Core

Open Source Chip Project by University (OSCPU)

Zhoushan is a 2-way superscalar out-of-order core, compatible with RV64I ISA.

Main developer: Li Shi (SJTU/CMU ECE)

## Zhoushan in 2022

I plan to relaunch this project in 2022 summer or fall, and the future works include

1. [Microarchitecture] Redesign the cache & bus

1. [Microarchitecture] Support fast branch misprediction recovery

1. [Microarchitecture] Develop a non-blocking cache and load & store queue

1. [RISC-V ISA] Support S & U privilege modes and MMU (TLB and Sv39 page table)

1. [RISC-V ISA] Boot an OS, e.g., Linux

1. [Physical] Analyze the critical path and optimize the pipeline design

1. [Verification] Port to FPGA

If you are interested to join, feel free to contact me (lishi@andrew.cmu.edu).

## Dependency

1. [AM](https://github.com/OSCPU-Zhoushan/abstract-machine), branch: zhoushan

1. [AM-Kernels](https://github.com/NJU-ProjectN/am-kernels), branch: master

1. [NEMU](https://github.com/OpenXiangShan/NEMU), branch: master

1. [ESPRESSO](https://github.com/classabbyamp/espresso-logic), branch: master

## Getting Started

First, download all the dependency repositories and set the environment variables as follows.

```bash
export NOOP_HOME=<...>/Zhoushan
export NEMU_HOME=<...>/NEMU
export AM_HOME=<...>/abstract-machine
export DRAMSIM3_HOME=<...>/Zhoushan/DRAMsim3
```

Then, config and make `NEMU` and `DRAMsim3`, and install `ESPRESSO`.

To generate Verilog:

```
make
```

To build and run the binary for emulation and difftest:

```
make emu
./build/emu -i path/to/risc-v/binary.bin
```

To run functional tests:

```
./test.sh -c
./test.sh -r
```

## Development Notes

### Bus ID Convention

1. Bus ID is set in `ZhoushanConfig`.

1. Bus ID must start from 1, and should not skip any index. Bus ID must match the order of `CrossbarNto1`.

### Git Convention

1. Branch for dev: `dev/XxxYyy`. Example: `dev/ScalarPipeline`, `dev/Superscalar`.

1. Only merge stable version (passing all tests) to `develop` branch after being permitted by Li Shi.

1. Never push or merge to `master` branch directly. Make a pull request.

### Naming Convention

Filename & class/object/trait name & constant: CamelCase (even though it contains abbreviation, e.g., we write `BhtWidth` rather than `BHTWidth`). Example:

```scala
// SimTop.scala

class SimTop extends Module {
  ...
}

// ZhoushanConfig.scala

trait ZhoushanConfig {
  // MMIO Address Map
  val ClintAddrBase = 0x02000000
  ...
}
```

Function name: camelCase. Example:

```scala
def getFlag(x: UInt): Bool = x(addr_width - 1).asBool()
```

Wire, register, instance, io name: some_name. Example:

```scala
val this_wire = ...
val this_module = Module(new ThisModule)
```
