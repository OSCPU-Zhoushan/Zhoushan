#!/bin/bash

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


while getopts 'crt:' OPT; do
  case $OPT in
    c)
      cpu_tests;;
    r)
      riscv_tests;;
    t)
      example="$OPTARG"
      ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin -b 0 -e 1000 --dump-wave;;
    ?)
      echo "Error: missing arguments"
  esac
done
