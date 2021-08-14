BUILD_DIR = ./build
# Change this directory before make
ZHOUSHAN_HOME = /home/lishi/Zhoushan

VERILATOR_FLAGS = -cc --exe -Os -x-assign 0 \
	--assert --trace

VERILATOR_INPUT = $(ZHOUSHAN_HOME)/build/Top.v $(ZHOUSHAN_HOME)/src/test/csrc/main.cpp

default: run

verilog:
	mkdir -p $(BUILD_DIR)
	mill -i Zhoushan.runMain zhoushan.TopMain -td $(BUILD_DIR)

run: verilog
	@echo
	@echo "-- VERILATE ----------------"
	verilator $(VERILATOR_FLAGS) $(VERILATOR_INPUT)
	@echo

	@echo "-- BUILD -------------------"
	$(MAKE) -j -C obj_dir -f VTop.mk
	@echo

	@echo "-- RUN ---------------------"
	@rm -rf logs
	@mkdir -p logs
	obj_dir/VTop +trace
	@echo
	
	@echo "-- DONE --------------------"
	@echo "To see waveforms, open vlt_dump.vcd in a waveform viewer"
	@echo

help:
	mill -i Zhoushan.runMain TopMain --help

compile:
	mill -i __.compile

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

clean:
	-rm -rf $(BUILD_DIR)
	-rm -rf obj_dir
	-rm -rf logs

.PHONY: verilog help reformat checkformat clean
