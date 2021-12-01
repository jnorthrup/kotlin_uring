/* SPDX-License-Identifier: MIT */
/*
 * Description: test CQ peek-batch
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

fun queue_n_nops(ring:CPointer<io_uring>, n:Int, offset:Int):Int{
	val __FUNCTION__="queue_n_nops"

    sqe:CPointer<io_uring_sqe>;
    i:Int, ret;

    for (i in 0 until  n) {
        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            printf("get sqe failed\n");
            break@err;
        }

        io_uring_prep_nop(sqe);
        sqe.pointed.user_data = i + offset;
    }

    ret = io_uring_submit(ring);
    if (ret < n) {
        printf("Submitted only %d\n", ret);
        break@err;
    } else if (ret < 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    return 0;
    err:
    return 1;
}

#define CHECK_BATCH(ring, got, cqes, count, expected) do {\
    got = io_uring_peek_batch_cqe((ring), cqes, count);\
    if (got != expected) {\
        printf("Got %d CQs, expected %d\n", got, expected);\
        break@err;\
    }\
} while(0)

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    cqes:CPointer<io_uring_cqe>[8];
    ring:io_uring;
    ret:Int, i;
    got:UInt;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(4, ring.ptr, 0);
    if (ret) {
        printf("ring setup failed\n");
        return 1;

    }

    CHECK_BATCH(ring.ptr, got, cqes, 4, 0);
    if (queue_n_nops(ring.ptr, 4, 0))
        break@err;

    CHECK_BATCH(ring.ptr, got, cqes, 4, 4);
    for (i in 0 until  4) {
        if (i != cqes[i]->user_data) {
            printf("Got user_data %" PRIu64 ", expected %d\n",
                   (uint64_t) cqes[i]->user_data, i);
            break@err;
        }
    }

    if (queue_n_nops(ring.ptr, 4, 4))
        break@err;

    io_uring_cq_advance(ring.ptr, 4);
    CHECK_BATCH(ring.ptr, got, cqes, 4, 4);
    for (i in 0 until  4) {
        if (i + 4 != cqes[i]->user_data) {
            printf("Got user_data %" PRIu64 ", expected %d\n",
                   (uint64_t) cqes[i]->user_data, i + 4);
            break@err;
        }
    }

    io_uring_cq_advance(ring.ptr, 8);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err:
    io_uring_queue_exit(ring.ptr);
    return 1;
}
