#!/bin/bash

ysyxid=000000
cpu_tests_dir=../am-kernels/tests/cpu-tests/build/
riscv_tests_dir=../riscv-tests/build/

function tests {
  bin_files=`eval "find $1 -mindepth 1 -maxdepth 1 -regex \".*\.\(bin\)\""`
  for bin_file in $bin_files; do
    file_name=`basename ${bin_file%.*}`
    printf "[%30s] " $file_name
    log_file=./build/$file_name.log
    ./build/emu -i $bin_file &> $log_file
    if (grep 'HIT GOOD TRAP' $log_file > /dev/null) then
      echo -e "\033[1;32mPASS!\033[0m"
      rm $log_file
    else
      echo -e "\033[1;31mFAIL!\033[0m see $log_file for more information"
    fi
  done
  wait
}

function rename {
  filename=./build/ysyx_${ysyxid}.v
  cp ./build/RealTop.v $filename
  sed -i 's/io_master_aw_ready/io_master_awready/g' $filename
  sed -i 's/io_master_aw_valid/io_master_awvalid/g' $filename
  sed -i 's/io_master_aw_bits_/io_master_aw/g' $filename
  sed -i 's/io_master_w_ready/io_master_wready/g' $filename
  sed -i 's/io_master_w_valid/io_master_wvalid/g' $filename
  sed -i 's/io_master_w_bits_/io_master_w/g' $filename
  sed -i 's/io_master_b_ready/io_master_bready/g' $filename
  sed -i 's/io_master_b_valid/io_master_bvalid/g' $filename
  sed -i 's/io_master_b_bits_/io_master_b/g' $filename
  sed -i 's/io_master_ar_ready/io_master_arready/g' $filename
  sed -i 's/io_master_ar_valid/io_master_arvalid/g' $filename
  sed -i 's/io_master_ar_bits_/io_master_ar/g' $filename
  sed -i 's/io_master_r_ready/io_master_rready/g' $filename
  sed -i 's/io_master_r_valid/io_master_rvalid/g' $filename
  sed -i 's/io_master_r_bits_/io_master_r/g' $filename
  sed -i 's/io_slave_aw_ready/io_slave_awready/g' $filename
  sed -i 's/io_slave_aw_valid/io_slave_awvalid/g' $filename
  sed -i 's/io_slave_aw_bits_/io_slave_aw/g' $filename
  sed -i 's/io_slave_w_ready/io_slave_wready/g' $filename
  sed -i 's/io_slave_w_valid/io_slave_wvalid/g' $filename
  sed -i 's/io_slave_w_bits_/io_slave_w/g' $filename
  sed -i 's/io_slave_b_ready/io_slave_bready/g' $filename
  sed -i 's/io_slave_b_valid/io_slave_bvalid/g' $filename
  sed -i 's/io_slave_b_bits_/io_slave_b/g' $filename
  sed -i 's/io_slave_ar_ready/io_slave_arready/g' $filename
  sed -i 's/io_slave_ar_valid/io_slave_arvalid/g' $filename
  sed -i 's/io_slave_ar_bits_/io_slave_ar/g' $filename
  sed -i 's/io_slave_r_ready/io_slave_rready/g' $filename
  sed -i 's/io_slave_r_valid/io_slave_rvalid/g' $filename
  sed -i 's/io_slave_r_bits_/io_slave_r/g' $filename
  sed -i "s/ysyx_${ysyxid}_RealTop/ysyx_${ysyxid}/g" $filename
}

while getopts 'crt:w:n' OPT; do
  case $OPT in
    c)
      tests $cpu_tests_dir;;
    r)
      tests $riscv_tests_dir;;
    t)
      example="$OPTARG"
      ./build/emu -i ${cpu_tests_dir}/${example}-riscv64-mycpu.bin;;
    w)
      example="$OPTARG"
      ./build/emu -i ${cpu_tests_dir}/${example}-riscv64-mycpu.bin -b 0 --dump-wave --wave-path=./build/wave.vcd;;
    n)
      rename;;
    ?)
      echo "Error: missing arguments"
  esac
done
