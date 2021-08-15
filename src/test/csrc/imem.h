#ifndef IMEM_H
#define IMEM_H

#include <stdint.h>
#define IMEM_SZ 6

class IMem {
public:
  IMem();

  uint32_t GetInst(uint32_t pc);

private:
  uint64_t imem[IMEM_SZ];
};

#endif // IMEM_H
