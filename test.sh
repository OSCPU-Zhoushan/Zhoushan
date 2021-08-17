#!/bin/bash

cpu_tests=`cat cpu_tests.txt`
# riscv_tests=`cat riscv_tests.txt`

for example in ${cpu_tests[@]}
do
  echo ======================================= ${example} start
  ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin
  echo ======================================= ${example} finish
done

# for example in ${riscv_tests[@]}
# do
#   echo ======================================= ${example} start
#   ./build/emu -i ../riscv-tests//build/${example}-riscv64-mycpu.bin
#   echo ======================================= ${example} finish
# done
wait
