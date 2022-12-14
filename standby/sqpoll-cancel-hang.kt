//include <fcntl.h>
//include <signal.h>
//include <stdint.h>
//include <stdlib.h>
//include <string.h>
//include <sys/mman.h>
//include <sys/wait.h>
//include <time.h>
//include <unistd.h>
//include "liburing.h"
//include "../src/syscall.h"

fun current_time_ms(void):uint64_t{
	val __FUNCTION__="current_time_ms"

    ts:timespec;
    if (clock_gettime(CLOCK_MONOTONIC, ts.ptr))
        exit(1);
    return (uint64_t) ts.tv_sec * 1000 + (uint64_t) ts.tv_nsec / 1000000;
}

#define SIZEOF_IO_URING_SQE 64
#define SIZEOF_IO_URING_CQE 16
#define SQ_TAIL_OFFSET 64
#define SQ_RING_MASK_OFFSET 256
#define SQ_RING_ENTRIES_OFFSET 264
#define CQ_RING_ENTRIES_OFFSET 268
#define CQ_CQES_OFFSET 320

#define IORING_OFF_SQES 0x10000000ULL

fun kill_and_wait(pid:Int, status:CPointer<Int>):Unit{
	val __FUNCTION__="kill_and_wait"

    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    while (waitpid(-1, status, __WALL) != pid) {
    }
}

#define WAIT_FLAGS __WALL

uint64_t r[3] = {0xffffffffffffffff, 0x0, 0x0};

static syz_io_uring_setup:Long(volatile a0:Long, volatile a1:Long,
        volatile a2:Long, volatile a3:Long, volatile a4:Long, volatile long
a5) {
    entries:uint32_t = (uint32_t) a0;
    setup_params:CPointer<io_uring_params> = (struct io_uring_params *) a1;
    void:CPointer<ByteVar>vma1= (void *) a2;
    void:CPointer<ByteVar>vma2= (void *) a3;
    ring_ptr_out:CPointerVarOf<CPointer<ByteVar>> = (void **) a4;
    sqes_ptr_out:CPointerVarOf<CPointer<ByteVar>> = (void **) a5;
    fd_io_uring:uint32_t = __sys_io_uring_setup(entries, setup_params);
    sq_ring_sz:uint32_t = setup_params.pointed.sq_off.array +
                          setup_params.pointed.sizeof:CPointer<sq_entries>(uint32_t);
    cq_ring_sz:uint32_t = setup_params.pointed.cq_off.cqes +
                          setup_params.pointed.SIZEOF_IO_URING_CQE:CPointer<cq_entries>;
    ring_sz:uint32_t = sq_ring_sz > cq_ring_sz ? sq_ring_sz : cq_ring_sz;
    *ring_ptr_out = mmap(vma1, ring_sz,  PROT_READ or PROT_WRITE ,
                          MAP_SHARED or MAP_POPULATE  | MAP_FIXED, fd_io_uring,
                         IORING_OFF_SQ_RING);
    sqes_sz:uint32_t = setup_params.pointed.SIZEOF_IO_URING_SQE:CPointer<sq_entries>;
    *sqes_ptr_out = mmap(vma2, sqes_sz,  PROT_READ or PROT_WRITE ,
                          MAP_SHARED or MAP_POPULATE  | MAP_FIXED, fd_io_uring, IORING_OFF_SQES);
    return fd_io_uring;
}

static syz_io_uring_submit:Long(volatile a0:Long, volatile a1:Long,
        volatile a2:Long, volatile a3:Long) {
    char:CPointer<ByteVar>ring_ptr= (char *) a0;
    char:CPointer<ByteVar>sqes_ptr= (char *) a1;
    char:CPointer<ByteVar>sqe= (char *) a2;
    sqes_index:uint32_t = (uint32_t) a3;
    sq_ring_entries:uint32_t = *(uint32_t *) (ring_ptr + SQ_RING_ENTRIES_OFFSET);
    cq_ring_entries:uint32_t = *(uint32_t *) (ring_ptr + CQ_RING_ENTRIES_OFFSET);
    sq_array_off:uint32_t = (CQ_CQES_OFFSET + cq_ring_entries *
                                              SIZEOF_IO_URING_CQE + 63) & ~63;
    if (sq_ring_entries)
        sqes_index %= sq_ring_entries;
    char:CPointer<ByteVar>sqe_dest= sqes_ptr + SIZEOF_IO_URING_SQE:CPointer<sqes_index>;
    memcpy(sqe_dest, sqe, SIZEOF_IO_URING_SQE);
    sq_ring_mask:uint32_t = *(uint32_t *) (ring_ptr + SQ_RING_MASK_OFFSET);
    sq_tail_ptr:CPointer<uint32_t> = (uint32_t *) (ring_ptr + SQ_TAIL_OFFSET);
    sq_tail:uint32_t = * sq_tail_ptr and sq_ring_mask ;
    sq_tail_next:uint32_t = *sq_tail_ptr + 1;
    sq_array:CPointer<uint32_t> = (uint32_t *) (ring_ptr + sq_array_off);
    *(sq_array + sq_tail) = sqes_index;
    __atomic_store_n(sq_tail_ptr, sq_tail_next, __ATOMIC_RELEASE);
    return 0;
}


fun trigger_bug(void):Unit{
	val __FUNCTION__="trigger_bug"

    res:intptr_t = 0;
    *(uint32_t *) 0x20000204 = 0;
    *(uint32_t *) 0x20000208 = 2;
    *(uint32_t *) 0x2000020c = 0;
    *(uint32_t *) 0x20000210 = 0;
    *(uint32_t *) 0x20000218 = -1;
    memset((void *) 0x2000021c, 0, 12);
    res = -1;
    res = syz_io_uring_setup(0x7987, 0x20000200, 0x20400000, 0x20ffd000, 0x200000c0, 0x200001c0);
    if (res != -1) {
        r[0] = res;
        r[1] = *(uint64_t *) 0x200000c0;
        r[2] = *(uint64_t *) 0x200001c0;
    }
    *(uint8_t *) 0x20000180 = 0xb;
    *(uint8_t *) 0x20000181 = 1;
    *(uint16_t *) 0x20000182 = 0;
    *(uint32_t *) 0x20000184 = 0;
    *(uint64_t *) 0x20000188 = 4;
    *(uint64_t *) 0x20000190 = 0x20000140;
    *(uint64_t *) 0x20000140 = 0x77359400;
    *(uint64_t *) 0x20000148 = 0;
    *(uint32_t *) 0x20000198 = 1;
    *(uint32_t *) 0x2000019c = 0;
    *(uint64_t *) 0x200001a0 = 0;
    *(uint16_t *) 0x200001a8 = 0;
    *(uint16_t *) 0x200001aa = 0;
    memset((void *) 0x200001ac, 0, 20);
    syz_io_uring_submit(r[1], r[2], 0x20000180, 1);
    *(uint32_t *) 0x20000544 = 0;
    *(uint32_t *) 0x20000548 = 0x36;
    *(uint32_t *) 0x2000054c = 0;
    *(uint32_t *) 0x20000550 = 0;
    *(uint32_t *) 0x20000558 = r[0];
    memset((void *) 0x2000055c, 0, 12);

}

fun main(void):Int{
	val __FUNCTION__="main"

    mmap((void *) 0x20000000ul, 0x1000000ul, 7ul, 0x32ul, -1, 0ul);
    pid:Int = fork();
    if (pid < 0)
        exit(1);
    if (pid == 0) {
        trigger_bug();
        exit(0);
    }
    status:Int = 0;
    start:uint64_t = current_time_ms();
    for (;;) {
        if (current_time_ms() - start < 1000) {
            continue;
        }
        kill_and_wait(pid, status.ptr);
        break;
    }
    return 0;
}



