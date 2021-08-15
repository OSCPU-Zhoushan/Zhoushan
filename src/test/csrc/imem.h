#ifndef IMEM_H
#define IMEM_H

#include <stdint.h>
#define IMEM_SZ 11

class IMem {
public:
  IMem() {
    imem[0] = 0x01e0006f;   // 00          jal   x0, main
    imem[1] = 0x00b00333;   // 04 intmul:  add   t1, x0, a1
    imem[2] = 0x004000ef;   // 08          jal   ra, loop1
    imem[3] = 0x00a383b3;   // 0c loop1:   add   t2, t2, a0
    imem[4] = 0xfff30313;   // 10          addi  t1, t1, -1
    imem[5] = 0x00030a63;   // 14          beq   t1, x0, exit
    imem[6] = 0xff5ff06f;   // 18          jal   x0, loop1
    imem[7] = 0x07800513;   // 1c main:    addi  a0, x0, 120   # first parameter in the multiplication
    imem[8] = 0x00600593;   // 20          addi  a1, x0, 6     # second parameter in the multiplication
    imem[9] = 0xfe1fffef;   // 24          jal   ra, intmul
    imem[10] = 0x0000006f;  // 28 exit:    jal   x0, exit  
  }

  uint32_t GetInst(uint32_t pc);

private:
  uint64_t imem[IMEM_SZ];
};

#endif // IMEM_H
