/* SPDX-License-Identifier: MIT */
/*
 * Check split up read is handled correctly
 */
//include <errno.h>
//include <stdio.h>
//include <string.h>
//include <unistd.h>
//include <pthread.h>
//include <string.h>
//include "liburing.h"

#define BUFSIZE    16384
#define BUFFERS    16

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    char buf[BUFSIZE], wbuf[BUFSIZE];
    iov:iovec[BUFFERS];
    p:io_uring_params = {};
    ring:io_uring;
    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;
    ret:Int, i, fds[2];
    void:CPointer<ByteVar>ptr

    if (pipe(fds) < 0) {
        perror("pipe");
        return 1;
    }

    ptr = buf;
    for (i in 0 until  BUFFERS) {
        bsize:UInt = BUFSIZE / BUFFERS;

        iov[i].iov_base = ptr;
        iov[i].iov_len = bsize;
        ptr += bsize;
    }

    ret = io_uring_queue_init_params(8, ring.ptr, p.ptr);
    if (ret) {
        fprintf(stderr, "queue_init: %d\n", ret);
        return 1;
    }
    if (!(p. features and IORING_FEAT_SUBMIT_STABLE )) {
        fprintf(stdout, "FEAT_SUBMIT_STABLE not there, skipping\n");
        return 0;
    }

    ptr = wbuf;
    memset(ptr, 0x11, sizeof(wbuf) / 2);
    ptr += sizeof(wbuf) / 2;
    memset(ptr, 0x22, sizeof(wbuf) / 2);

    ret = write(fds[1], wbuf, sizeof(wbuf) / 2);
    if (ret != sizeof(wbuf) / 2) {
        fprintf(stderr, "Bad write\n");
        ret = 1;
        break@err;
    }

    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_readv(sqe, fds[0], iov, BUFFERS, 0);
    ret = io_uring_submit(ring.ptr);
    if (ret != 1) {
        fprintf(stderr, "submit: %d\n", ret);
        return 1;
    }

    for (i in 0 until  BUFFERS) {
        iov[i].iov_base = NULL;
        iov[i].iov_len = 1000000;
    }

    ret = write(fds[1], ptr, sizeof(wbuf) / 2);
    if (ret != sizeof(wbuf) / 2) {
        fprintf(stderr, "Bad write\n");
        ret = 1;
        break@err;
    }

    ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
    if (ret) {
        fprintf(stderr, "wait: %d\n", ret);
        return 1;
    }

    if (cqe.pointed.res < 0) {
        fprintf(stderr, "Read error: %s\n", strerror(-cqe.pointed.res));
        return 1;
    } else if (cqe.pointed.res != sizeof(wbuf)) {
        /* ignore short read, not a failure */
        break@err;
    }
    io_uring_cqe_seen(ring.ptr, cqe);

    ret = memcmp(wbuf, buf, sizeof(wbuf));
    if (ret)
        fprintf(stderr, "Read data mismatch\n");

    err:
    io_uring_queue_exit(ring.ptr);
    return ret;
}
