#!/bin/bash

ysyxid=000000

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

function rename {
  filename=./build/ysyx_${ysyxid}.v
  cp ./build/RealTop.v ${filename}
  sed -i 's/io_master_aw_ready/io_master_awready/g' ${filename}
  sed -i 's/io_master_aw_valid/io_master_awvalid/g' ${filename}
  sed -i 's/io_master_aw_bits_/io_master_aw/g' ${filename}
  sed -i 's/io_master_w_ready/io_master_wready/g' ${filename}
  sed -i 's/io_master_w_valid/io_master_wvalid/g' ${filename}
  sed -i 's/io_master_w_bits_/io_master_w/g' ${filename}
  sed -i 's/io_master_b_ready/io_master_bready/g' ${filename}
  sed -i 's/io_master_b_valid/io_master_bvalid/g' ${filename}
  sed -i 's/io_master_b_bits_/io_master_b/g' ${filename}
  sed -i 's/io_master_ar_ready/io_master_arready/g' ${filename}
  sed -i 's/io_master_ar_valid/io_master_arvalid/g' ${filename}
  sed -i 's/io_master_ar_bits_/io_master_ar/g' ${filename}
  sed -i 's/io_master_r_ready/io_master_rready/g' ${filename}
  sed -i 's/io_master_r_valid/io_master_rvalid/g' ${filename}
  sed -i 's/io_master_r_bits_/io_master_r/g' ${filename}
  sed -i 's/io_slave_aw_ready/io_slave_awready/g' ${filename}
  sed -i 's/io_slave_aw_valid/io_slave_awvalid/g' ${filename}
  sed -i 's/io_slave_aw_bits_/io_slave_aw/g' ${filename}
  sed -i 's/io_slave_w_ready/io_slave_wready/g' ${filename}
  sed -i 's/io_slave_w_valid/io_slave_wvalid/g' ${filename}
  sed -i 's/io_slave_w_bits_/io_slave_w/g' ${filename}
  sed -i 's/io_slave_b_ready/io_slave_bready/g' ${filename}
  sed -i 's/io_slave_b_valid/io_slave_bvalid/g' ${filename}
  sed -i 's/io_slave_b_bits_/io_slave_b/g' ${filename}
  sed -i 's/io_slave_ar_ready/io_slave_arready/g' ${filename}
  sed -i 's/io_slave_ar_valid/io_slave_arvalid/g' ${filename}
  sed -i 's/io_slave_ar_bits_/io_slave_ar/g' ${filename}
  sed -i 's/io_slave_r_ready/io_slave_rready/g' ${filename}
  sed -i 's/io_slave_r_valid/io_slave_rvalid/g' ${filename}
  sed -i 's/io_slave_r_bits_/io_slave_r/g' ${filename}
  sed -i "s/ysyx_${ysyxid}_RealTop/ysyx_${ysyxid}/g" ${filename}
}

while getopts 'qcrt:w:n' OPT; do
  case $OPT in
    q)
      quick_tests;;
    c)
      cpu_tests;;
    r)
      riscv_tests;;
    t)
      example="$OPTARG"
      ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin;;
    w)
      example="$OPTARG"
      ./build/emu -i ../am-kernels/tests/cpu-tests/build/${example}-riscv64-mycpu.bin -b 0 --dump-wave --wave-path=./build/wave.vcd;;
    n)
      rename;;
    ?)
      echo "Error: missing arguments"
  esac
done
