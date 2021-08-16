BUILD_DIR = ./build
# Change this directory before make
ZHOUSHAN_HOME = $(shell pwd)

VERILATOR_FLAGS = -cc --exe -Os -x-assign 0 \
	--assert --trace

CSRC_DIR = $(ZHOUSHAN_HOME)/src/test/csrc
VERILATOR_INPUT = $(ZHOUSHAN_HOME)/build/SimTop.v $(CSRC_DIR)/imem.cpp $(CSRC_DIR)/main.cpp

default: verilog

verilog:
	mkdir -p $(BUILD_DIR)
	mill -i Zhoushan.runMain zhoushan.TopMain -td $(BUILD_DIR)

emu: verilog
	cd $(ZHOUSHAN_HOME)/difftest && $(MAKE) emu

run: verilog
	@echo
	@echo "-- VERILATE ----------------"
	verilator $(VERILATOR_FLAGS) $(VERILATOR_INPUT)
	@echo

	@echo "-- BUILD -------------------"
	$(MAKE) -j -C obj_dir -f VSimTop.mk
	@echo

	@echo "-- RUN ---------------------"
	@rm -rf logs
	@mkdir -p logs
	obj_dir/VSimTop +trace
	@echo
	
	@echo "-- DONE --------------------"
	@echo "To see waveforms, open dump.vcd in a waveform viewer"
	@echo

help:
	mill -i Zhoushan.runMain zhoushan.TopMain --help

clean:
	-rm -rf $(BUILD_DIR)
	-rm -rf obj_dir
	-rm -rf logs

.PHONY: verilog help reformat checkformat clean
