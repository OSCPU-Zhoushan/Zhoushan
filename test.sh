#!/bin/bash

#examples=`cat test_list.txt`
examples=(load-store)

for example in ${examples[@]}
do
  echo ======================================= ${example} start
  ./build/emu -C 100000 -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin
  echo ======================================= ${example} finish
done
wait
