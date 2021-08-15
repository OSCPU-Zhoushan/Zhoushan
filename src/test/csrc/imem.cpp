#include "imem.h"

uint32_t IMem::GetInst(uint32_t pc) {
  pc = pc >> 2;
  if (pc > IMEM_SZ)
    return 0;
  return imem[pc];
}
