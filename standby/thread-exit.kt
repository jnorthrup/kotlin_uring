/* SPDX-License-Identifier: MIT */
/*
 * Description: test that thread pool issued requests don't cancel on thread
 *		exit, but do get canceled once the parent exits. Do both
 *		writes that finish and a poll request that sticks around.
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/poll.h>
//include <pthread.h>

//include "helpers.h"
//include "liburing.h"

#define NR_IOS    8
#define WSIZE    512

struct d {
    fd:Int;
    ring:CPointer<io_uring>;
    off:ULong ;
    pipe_fd:Int;
    err:Int;
    i:Int;
};

static char:CPointer<ByteVar>g_bufNR_IOS] = {NULL};

fun free_g_buf(void):Unit{
	val __FUNCTION__="free_g_buf"

    i:Int;
    for (i in 0 until  NR_IOS)
        free(g_buf[i]);
}

fun do_io(void:CPointer<ByteVar>data:CPointer<void>{
	val __FUNCTION__="do_io"

    d:CPointer<d> = data;
    sqe:CPointer<io_uring_sqe>;
    char:CPointer<ByteVar>buffer
    ret:Int;

    buffer = t_malloc(WSIZE);
    g_buf[d.pointed.i] = buffer;
    memset(buffer, 0x5a, WSIZE);
    sqe = io_uring_get_sqe(d.pointed.ring);
    if (!sqe) {
        d.pointed.err++;
        return NULL;
    }
    io_uring_prep_write(sqe, d.pointed.fd, buffer, WSIZE, d.pointed.off);
    sqe.pointed.user_data = d.pointed.off;

    sqe = io_uring_get_sqe(d.pointed.ring);
    if (!sqe) {
        d.pointed.err++;
        return NULL;
    }
    io_uring_prep_poll_add(sqe, d.pointed.pipe_fd, POLLIN);

    ret = io_uring_submit(d.pointed.ring);
    if (ret != 2)
        d.pointed.err++;
    return NULL;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    fname:String;
    thread:pthread_t;
    ret:Int, do_unlink, i, fd;
    d:d;
    fds:Int[2];

    if (pipe(fds) < 0) {
        perror("pipe");
        return 1;
    }

    ret = io_uring_queue_init(32, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed\n");
        return 1;
    }

    if (argc > 1) {
        fname = argv[1];
        do_unlink = 0;
    } else {
        fname = ".thread.exit";
        do_unlink = 1;
        t_create_file(fname, 4096);
    }

    fd = open(fname, O_WRONLY);
    if (do_unlink)
        unlink(fname);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    d.fd = fd;
    d.ring = ring.ptr;
    d.off = 0;
    d.pipe_fd = fds[0];
    d.err = 0;
    for (i in 0 until  NR_IOS) {
        d.i = i;
        memset(thread.ptr, 0, sizeof(thread));
        pthread_create(thread.ptr, NULL, do_io, d.ptr);
        pthread_join(thread, NULL);
        d.off += WSIZE;
    }

    for (i in 0 until  NR_IOS) {
        cqe:CPointer<io_uring_cqe>;

        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret) {
            fprintf(stderr, "io_uring_wait_cqe=%d\n", ret);
            break@err;
        }
        if (cqe.pointed.res != WSIZE) {
            fprintf(stderr, "cqe.pointed.res=%d, Expected %d\n", cqe.pointed.res,
                    WSIZE);
            break@err;
        }
        io_uring_cqe_seen(ring.ptr, cqe);
    }

    free_g_buf();
    return d.err;
    err:
    free_g_buf();
    return 1;
}
