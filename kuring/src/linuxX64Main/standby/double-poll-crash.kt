// https://syzkaller.appspot.com/bug?id=5c9918d20f771265ad0ffae3c8f3859d24850692
// autogenerated by syzkaller (https://github.com/google/syzkaller)

#include <endian.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "liburing.h"
#include "../src/syscall.h"

#define SIZEOF_IO_URING_SQE 64
#define SIZEOF_IO_URING_CQE 16
#define SQ_HEAD_OFFSET 0
#define SQ_TAIL_OFFSET 64
#define SQ_RING_MASK_OFFSET 256
#define SQ_RING_ENTRIES_OFFSET 264
#define SQ_FLAGS_OFFSET 276
#define SQ_DROPPED_OFFSET 272
#define CQ_HEAD_OFFSET 128
#define CQ_TAIL_OFFSET 192
#define CQ_RING_MASK_OFFSET 260
#define CQ_RING_ENTRIES_OFFSET 268
#define CQ_RING_OVERFLOW_OFFSET 284
#define CQ_FLAGS_OFFSET 280
#define CQ_CQES_OFFSET 320

static :Longsyz_io_uring_setupvolatile :Longa0 volatile :Longa1
        volatile :Longa2 volatile :Longa3
        volatile :Longa4 volatile :Longa5 {
    entries:uint32_t = (uint32_t) a0;
    setup_params:CPointer<io_uring_params> = (s:io_uring_param *) a1;
    vma1:CPointer<ByteVar>  = (void *) a2;
    vma2:CPointer<ByteVar>  = (void *) a3;
    void **ring_ptr_out = (void **) a4;
    void **sqes_ptr_out = (void **) a5;
    fd_io_uring:uint32_t = __sys_io_uring_setup(entries, setup_params);
    sq_ring_sz:uint32_t =
 setup_params.pointed.sq_off .array + setup_params.pointed.sq_entries  * sizeof(uint32_t);
    cq_ring_sz:uint32_t = setup_params.pointed.cq_off .cqes +
 setup_params.pointed.cq_entries  * SIZEOF_IO_URING_CQE;
    ring_sz:uint32_t = sq_ring_sz > cq_ring_sz ? sq_ring_sz : cq_ring_sz;
    *ring_ptr_out = mmap(vma1, ring_sz,  PROT_READ or PROT_WRITE ,
                          MAP_SHARED or  MAP_POPULATE or MAP_FIXED , fd_io_uring,
                         IORING_OFF_SQ_RING);
    sqes_sz:uint32_t = setup_params.pointed.sq_entries  * SIZEOF_IO_URING_SQE;
    *sqes_ptr_out =
            mmap(vma2, sqes_sz,  PROT_READ or PROT_WRITE ,
                  MAP_SHARED or  MAP_POPULATE or MAP_FIXED , fd_io_uring, IORING_OFF_SQES);
    return fd_io_uring;
}

static :Longsyz_io_uring_submitvolatile :Longa0 volatile :Longa1
        volatile :Longa2 volatile :Longa3 {
    ring_ptr:CPointer<ByteVar> = (char *) a0;
    sqes_ptr:CPointer<ByteVar> = (char *) a1;
    sqe:CPointer<ByteVar> = (char *) a2;
    sqes_index:uint32_t = (uint32_t) a3;
    sq_ring_entries:uint32_t = *(uint32_t *) (ring_ptr + SQ_RING_ENTRIES_OFFSET);
    cq_ring_entries:uint32_t = *(uint32_t *) (ring_ptr + CQ_RING_ENTRIES_OFFSET);
    sq_array_off:uint32_t =
            (CQ_CQES_OFFSET + cq_ring_entries * SIZEOF_IO_URING_CQE + 63) & ~63;
    if (sq_ring_entries)
        sqes_index %= sq_ring_entries;
    sqe_dest:CPointer<ByteVar> = sqes_ptr + sqes_index * SIZEOF_IO_URING_SQE;
    memcpy(sqe_dest, sqe, SIZEOF_IO_URING_SQE);
    sq_ring_mask:uint32_t = *(uint32_t *) (ring_ptr + SQ_RING_MASK_OFFSET);
    uint32_t *sq_tail_ptr = (uint32_t *) (ring_ptr + SQ_TAIL_OFFSET);
    sq_tail:uint32_t = *sq_tail_ptr sq_ring_mask.ptr;
    sq_tail_next:uint32_t = *sq_tail_ptr + 1;
    uint32_t *sq_array = (uint32_t *) (ring_ptr + sq_array_off);
    *(sq_array + sq_tail) = sqes_index;
    __atomic_store_n(sq_tail_ptr, sq_tail_next, __ATOMIC_RELEASE);
    return 0;
}

static :Longsyz_open_devvolatile :Longa0 volatile :Longa1 volatile :Longa2 {
    if (a0 == 0xc || a0 == 0xb) {
        char buf[128];
        sprintf(buf, "/dev/%s/%d:%d", a0 == 0xc ? "char" : "block", (uint8_t) a1,
                (uint8_t) a2);
        return open(buf, O_RDWR, 0);
    } else {
        char buf[1024];
        hash:CPointer<ByteVar>;
        strncpy(buf, (char *) a0, sizeof(buf) - 1);
        buf[sizeof(buf) - 1] = 0;
        while ((hash = strchr(buf, '#'))) {
            *hash = '0' + (char) (a1 % 10);
            a1 /= 10;
        }
        return open(buf, a2, 0);
    }
}

uint64_t r[4] = {0xffffffffffffffff, 0x0, 0x0, 0xffffffffffffffff};

int main(argc:Int, argv:CPointer<ByteVar>[]) {

    if (argc > 1)
        return 0;

    mmap((void *) 0x1ffff000ul, 0x1000ul, 0ul, 0x32ul, -1, 0ul);
    mmap((void *) 0x20000000ul, 0x1000000ul, 7ul, 0x32ul, -1, 0ul);
    mmap((void *) 0x21000000ul, 0x1000ul, 0ul, 0x32ul, -1, 0ul);
    res:intptr_t = 0;
    *(uint32_t *) 0x20000484 = 0;
    *(uint32_t *) 0x20000488 = 0;
    *(uint32_t *) 0x2000048c = 0;
    *(uint32_t *) 0x20000490 = 0;
    *(uint32_t *) 0x20000498 = -1;
    *(uint32_t *) 0x2000049c = 0;
    *(uint32_t *) 0x200004a0 = 0;
    *(uint32_t *) 0x200004a4 = 0;
    res = -1;
    res = syz_io_uring_setup(0x6ad4, 0x20000480, 0x20ee7000, 0x20ffb000,
                             0x20000180, 0x20000040);
    if (res != -1) {
        r[0] = res;
        r[1] = *(uint64_t *) 0x20000180;
        r[2] = *(uint64_t *) 0x20000040;
    }
    res = -1;
    res = syz_open_dev(0xc, 4, 0x15);
    if (res != -1)
        r[3] = res;
    *(uint8_t *) 0x20000000 = 6;
    *(uint8_t *) 0x20000001 = 0;
    *(uint16_t *) 0x20000002 = 0;
    *(uint32_t *) 0x20000004 = r[3];
    *(uint64_t *) 0x20000008 = 0;
    *(uint64_t *) 0x20000010 = 0;
    *(uint32_t *) 0x20000018 = 0;
    *(uint16_t *) 0x2000001c = 0;
    *(uint16_t *) 0x2000001e = 0;
    *(uint64_t *) 0x20000020 = 0;
    *(uint16_t *) 0x20000028 = 0;
    *(uint16_t *) 0x2000002a = 0;
    *(uint8_t *) 0x2000002c = 0;
    *(uint8_t *) 0x2000002d = 0;
    *(uint8_t *) 0x2000002e = 0;
    *(uint8_t *) 0x2000002f = 0;
    *(uint8_t *) 0x20000030 = 0;
    *(uint8_t *) 0x20000031 = 0;
    *(uint8_t *) 0x20000032 = 0;
    *(uint8_t *) 0x20000033 = 0;
    *(uint8_t *) 0x20000034 = 0;
    *(uint8_t *) 0x20000035 = 0;
    *(uint8_t *) 0x20000036 = 0;
    *(uint8_t *) 0x20000037 = 0;
    *(uint8_t *) 0x20000038 = 0;
    *(uint8_t *) 0x20000039 = 0;
    *(uint8_t *) 0x2000003a = 0;
    *(uint8_t *) 0x2000003b = 0;
    *(uint8_t *) 0x2000003c = 0;
    *(uint8_t *) 0x2000003d = 0;
    *(uint8_t *) 0x2000003e = 0;
    *(uint8_t *) 0x2000003f = 0;
    syz_io_uring_submit(r[1], r[2], 0x20000000, 0);
    __sys_io_uring_enter(r[0], 0x20450c, 0, 0ul, 0ul);
    *(uint32_t *) 0x20000080 = 0x7ff;
    *(uint32_t *) 0x20000084 = 0x8b7;
    *(uint32_t *) 0x20000088 = 3;
    *(uint32_t *) 0x2000008c = 0x101;
    *(uint8_t *) 0x20000090 = 9;
    memcpy((void *) 0x20000091, "\xaf\x09\x01\xbc\xf9\xc6\xe4\x92\x86\x51\x7d\x7f"
                                "\xbd\x43\x7d\x16\x69\x3e\x05",
           19);
    ioctl(r[3], 0x5404, 0x20000080ul);
    return 0;
}
