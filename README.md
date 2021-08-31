# Zhoushan Core

Open Source Chip Project by University (OSCPU) - Team Zhoushan

## Dependency

1. [AM](https://github.com/OSCPU-Zhoushan/abstract-machine), branch: zhoushan

1. [AM-Kernels](https://github.com/NJU-ProjectN/am-kernels), branch: master

1. [NEMU](https://github.com/OpenXiangShan/NEMU), branch: master

## Getting Started

First, download all the dependency repositories and set the environment variables as follows.

```bash
export NOOP_HOME=<...>/Zhoushan
export NEMU_HOME=<...>/NEMU
export AM_HOME=<...>/abstract-machine
export DRAMSIM3_HOME=<...>/Zhoushan/DRAMsim3
```

Then, config and make `NEMU` and `DRAMsim3`.

To generate Verilog:

```
make
```

To build and run the binary for emulation and difftest:

```
make emu
./build/emu -i path/to/risc-v/binary.bin
```

## Development Notes

### Cache & Memory Convention

1. `IF` stage AXI ID is 1

1. `MEM` stage AXI ID is 2

### Git Convention

1. Branch for dev: `dev/XxxYyy`. Example: `dev/ScalarPipeline`.

1. Only merge stable version (passing all tests) to `develop` branch after being permitted by Li Shi.

1. Never push or merge to `master` branch directly. Make a pull request.

### Naming Convention

Filename & class/object name & constant: CamelCase (even though it contains abbreviation, e.g., we write `FpgaTop` rather than `FPGATop`). Example:

```scala
// SimTop.scala

class SimTop extends Module {
  ...
}

// Settings.scala

object Settings {
  ...
  val ClintAddrSize = 0x10000
}
```

Function name: camelCase. Example:

```scala
def signExt32_64(x: UInt) : UInt = Cat(Fill(32, x(31)), x)
```

Wire, register, instance, io name: some_name. Example:

```scala
// Core.scala

...
val ex_rs1_from_cm = ...
...
val dt_ic = Module(new DifftestInstrCommit)
...
```
