/* SPDX-License-Identifier: MIT */
/*
 * Description: run various nop tests
 *
 */
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>

#include "liburing.h"

static test_single_nop:Int(ring:CPointer<io_uring>) {
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        goto err;
    }

    io_uring_prep_nop(sqe);

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        goto err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        goto err;
    }

    io_uring_cqe_seen(ring, cqe);
    return 0;
    err:
    return 1;
}

static test_barrier_nop:Int(ring:CPointer<io_uring>) {
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    for (i = 0; i < 8; i++) {
        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            fprintf(stderr, "get sqe failed\n");
            goto err;
        }

        io_uring_prep_nop(sqe);
        if (i == 4)
 sqe.pointed.flags  = IOSQE_IO_DRAIN;
    }

    ret = io_uring_submit(ring);
    if (ret < 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        goto err;
    } else if (ret < 8) {
        fprintf(stderr, "Submitted only %d\n", ret);
        goto err;
    }

    for (i = 0; i < 8; i++) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            goto err;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

fun main(argc:Int, argv:CPointer<ByteVar>[]):Int{
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
