#!/bin/bash

function quick_tests {
  quick_tests=(add-longlong add bit bubble-sort fact fib hello-str \
load-store max quick-sort recursion select-sort string sum unalign)
  for example in ${quick_tests[@]}
  do
    echo ======================================= ${example} start
    ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin
    echo ======================================= ${example} finish
  done
  wait
}

function cpu_tests {
  cpu_tests=`cat cpu_tests.txt`
  for example in ${cpu_tests[@]}
  do
    echo ======================================= ${example} start
    ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin
    echo ======================================= ${example} finish
  done
  wait
}

function riscv_tests {
  riscv_tests=`cat riscv_tests.txt`
  for example in ${riscv_tests[@]}
  do
    echo ======================================= ${example} start
    ./build/emu -i ../riscv-tests//build/${example}-riscv64-mycpu.bin
    echo ======================================= ${example} finish
  done
  wait
}

while getopts 'qcrt:' OPT; do
  case $OPT in
    q)
      quick_tests;;
    c)
      cpu_tests;;
    r)
      riscv_tests;;
    t)
      example="$OPTARG"
      ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin -b 0 --dump-wave;;
    ?)
      echo "Error: missing arguments"
  esac
done
