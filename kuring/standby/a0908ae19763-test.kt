/* SPDX-License-Identifier: MIT */
// autogenerated by syzkaller (https://github.com/google/syzkaller)

#include <endian.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/mman.h>
#include <unistd.h>

#include "liburing.h"
#include "../src/syscall.h"

uint64_t r[1] = {0xffffffffffffffff};

int main(int argc, char *argv[]) {
    if (argc > 1)
        return 0;
    mmap((void *) 0x20000000, 0x1000000, 3, 0x32, -1, 0);
    intptr_t res = 0;
    *(uint32_t *) 0x20000080 = 0;
    *(uint32_t *) 0x20000084 = 0;
    *(uint32_t *) 0x20000088 = 0;
    *(uint32_t *) 0x2000008c = 0;
    *(uint32_t *) 0x20000090 = 0;
    *(uint32_t *) 0x20000094 = 0;
    *(uint32_t *) 0x20000098 = 0;
    *(uint32_t *) 0x2000009c = 0;
    *(uint32_t *) 0x200000a0 = 0;
    *(uint32_t *) 0x200000a4 = 0;
    *(uint32_t *) 0x200000a8 = 0;
    *(uint32_t *) 0x200000ac = 0;
    *(uint32_t *) 0x200000b0 = 0;
    *(uint32_t *) 0x200000b4 = 0;
    *(uint32_t *) 0x200000b8 = 0;
    *(uint32_t *) 0x200000bc = 0;
    *(uint32_t *) 0x200000c0 = 0;
    *(uint32_t *) 0x200000c4 = 0;
    *(uint64_t *) 0x200000c8 = 0;
    *(uint32_t *) 0x200000d0 = 0;
    *(uint32_t *) 0x200000d4 = 0;
    *(uint32_t *) 0x200000d8 = 0;
    *(uint32_t *) 0x200000dc = 0;
    *(uint32_t *) 0x200000e0 = 0;
    *(uint32_t *) 0x200000e4 = 0;
    *(uint32_t *) 0x200000e8 = 0;
    *(uint32_t *) 0x200000ec = 0;
    *(uint64_t *) 0x200000f0 = 0;
    res = __sys_io_uring_setup(0xa4, (struct io_uring_params *) 0x20000080);
    if (res != -1)
        r[0] = res;
    *(uint32_t *) 0x20000280 = -1;
    __sys_io_uring_register(r[0], 2, (const void *) 0x20000280, 1);
    return 0;
}
