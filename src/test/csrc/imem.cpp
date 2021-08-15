#include "imem.h"

IMem::IMem()  {
  imem[0] = 0x06400413;   // addi s0, x0, 100
  imem[1] = 0x0c840493;   // addi s1, s0, 200
  imem[2] = 0x00940433;   // add  s0, s0, s1
  imem[4] = 0x06400413;   // addi s0, x0, 100
  imem[5] = 0x0c840493;   // addi s1, s0, 200
  imem[6] = 0x00940433;   // add  s0, s0, s1
}

uint32_t IMem::GetInst(uint32_t pc) {
  if (pc > IMEM_SZ)
    return 0;
  return imem[pc];
}
