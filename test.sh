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

while getopts 'crt:w:ls' OPT; do
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
    l)
      python3 script/lint.py;;
    s)
      python3 script/lint.py --soc;;
    ?)
      echo "Error: unknown arguments"
  esac
done
