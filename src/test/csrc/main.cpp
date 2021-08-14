
#include <memory>
#include <verilated.h>
#include <verilated_vcd_c.h>
#include "VTop.h"

double sc_time_stamp() { return 0; }

int main(int argc, char** argv, char** env) {

  if (false && argc && argv && env) {}

  // Create logs/ directory in case we have traces to put under it
  Verilated::mkdir("logs");

  const std::unique_ptr<VerilatedContext> contextp(new VerilatedContext);
  contextp->debug(0);
  contextp->randReset(2);
  contextp->traceEverOn(true);
  contextp->commandArgs(argc, argv);

  const std::unique_ptr<VTop> top(new VTop(contextp.get(), "TOP"));

  top->clock = 0;
  top->reset = 1;

  Verilated::traceEverOn(true);
  const std::unique_ptr<VerilatedVcdC> tfp(new VerilatedVcdC);
  top->trace(tfp.get(), 99);  // Trace 99 levels of hierarchy
  tfp->open("logs/dump.vcd");

  // Simulate until $finish
  while (contextp->time() < 10) {
    contextp->timeInc(1);

    top->clock = !top->clock;
    top->reset = contextp->time() < 4 ? 1 : 0;

    top->io_a = contextp->time();
    top->io_b = contextp->time() + 1;

    top->eval();
    tfp->dump(contextp->time());
    VL_PRINTF("[%" VL_PRI64 "d] clock=%x reset=%x io_a=%x io_b=%x io_c=%x\n",
              contextp->time(), top->clock, top->reset, top->io_a, top->io_b, top->io_c);
  }

  tfp->close();
  top->final();

  return 0;
}
