/* SPDX-License-Identifier: MIT */
/*
 * Description: run various nop tests
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

fun test_single_nop(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_nop"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }

    io_uring_prep_nop(sqe);

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        break@err;
    }

    io_uring_cqe_seen(ring, cqe);
    return 0;
    err:
    return 1;
}

fun test_barrier_nop(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_barrier_nop"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    for (i in 0 until  8) {
        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            fprintf(stderr, "get sqe failed\n");
            break@err;
        }

        io_uring_prep_nop(sqe);
        if (i == 4)
            sqe.pointed.flags = IOSQE_IO_DRAIN;
    }

    ret = io_uring_submit(ring);
    if (ret < 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    } else if (ret < 8) {
        fprintf(stderr, "Submitted only %d\n", ret);
        break@err;
    }

    for (i in 0 until  8) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    ret:Int;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    ret = test_single_nop(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_single_nop failed\n");
        return ret;
    }

    ret = test_barrier_nop(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_barrier_nop failed\n");
        return ret;
    }

    return 0;
}
