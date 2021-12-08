/* SPDX-License-Identifier: MIT */
/*
 * Description: test SQPOLL with IORING_SETUP_ATTACH_WQ and closing of
 * the original ring descriptor.
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/types.h>
//include <sys/poll.h>
//include <sys/eventfd.h>
//include <sys/resource.h>

//include "helpers.h"
//include "liburing.h"

#define FILE_SIZE    (128 * 1024 * 1024)
#define BS        4096
#define BUFFERS        64

#define NR_RINGS    4

static vecs:CPointer<iovec>;
static rings:io_uring[NR_RINGS];

fun wait_io(ring:CPointer<io_uring>, nr_ios:Int):Int{
	val __FUNCTION__="wait_io"

    cqe:CPointer<io_uring_cqe>;

    while (nr_ios) {
        io_uring_wait_cqe(ring, cqe.ptr);
        if (cqe.pointed.res != BS) {
            fprintf(stderr, "Unexpected ret %d\n", cqe.pointed.res);
            return 1;
        }
        io_uring_cqe_seen(ring, cqe);
        nr_ios--;
    }

    return 0;
}

fun queue_io(ring:CPointer<io_uring>, fd:Int, nr_ios:Int):Int{
	val __FUNCTION__="queue_io"

    off:ULong ;
    i:Int;

    i = 0;
    off = 0;
    while (nr_ios) {
        sqe:CPointer<io_uring_sqe>;

        sqe = io_uring_get_sqe(ring);
        if (!sqe)
            break;
        io_uring_prep_read(sqe, fd, vecs[i].iov_base, vecs[i].iov_len, off);
        nr_ios--;
        i++;
        off += BS;
    }

    io_uring_submit(ring);
    return i;
}

fun do_io(fd:Int, ring_start:Int, ring_end:Int):Int{
	val __FUNCTION__="do_io"

    i:Int, rets[NR_RINGS];
    ios:UInt = 0;

    while (ios < 32) {
        for (i in ring_start until  ring_end) {
            ret:Int = queue_io(rings.ptr[i], fd, BUFFERS);
            if (ret < 0)
                break@err;
            rets[i] = ret;
        }
        for (i in ring_start until  ring_end) {
            if (wait_io(rings.ptr[i], rets[i]))
                break@err;
        }
        ios += BUFFERS;
    }

    return 0;
    err:
    return 1;
}

fun test(fd:Int, do_dup_and_close:Int, close_ring:Int):Int{
	val __FUNCTION__="test"

    i:Int, ret, ring_fd;

    for (i in 0 until  NR_RINGS) {
        p:io_uring_params = {};

        p.flags = IORING_SETUP_SQPOLL;
        p.sq_thread_idle = 100;
        if (i) {
            p.wq_fd = rings[0].ring_fd;
            p.flags |= IORING_SETUP_ATTACH_WQ;
        }
        ret = io_uring_queue_init_params(BUFFERS, rings.ptr[i], p.ptr);
        if (ret) {
            fprintf(stderr, "queue_init: %d/%d\n", ret, i);
            break@err;
        }
        /* no sharing for non-fixed either */
        if (!(p. features and IORING_FEAT_SQPOLL_NONFIXED )) {
            fprintf(stdout, "No SQPOLL sharing, skipping\n");
            return 0;
        }
    }

    /* test all rings */
    if (do_io(fd, 0, NR_RINGS))
        break@err;

    /* dup and close original ring fd */
    ring_fd = dup(rings[0].ring_fd);
    if (close_ring)
        close(rings[0].ring_fd);
    rings[0].ring_fd = ring_fd;
    if (do_dup_and_close)
        break@done;

    /* test all but closed one */
    if (do_io(fd, 1, NR_RINGS))
        break@err;

    /* test closed one */
    if (do_io(fd, 0, 1))
        break@err;

    /* make sure thread is idle so we enter the kernel */
    usleep(200000);

    /* test closed one */
    if (do_io(fd, 0, 1))
        break@err;


    done:
    for (i in 0 until  NR_RINGS)
        io_uring_queue_exit(rings.ptr[i]);

    return 0;
    err:
    return 1;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    char:CPointer<ByteVar>fname
    ret:Int, fd;

    if (argc > 1) {
        fname = argv[1];
    } else {
        fname = ".basic-rw";
        t_create_file(fname, FILE_SIZE);
    }

    vecs = t_create_buffers(BUFFERS, BS);

    fd = open(fname,  O_RDONLY or O_DIRECT );
    if (fname != argv[1])
        unlink(fname);

    if (fd < 0) {
        perror("open");
        return -1;
    }

    ret = test(fd, 0, 0);
    if (ret) {
        fprintf(stderr, "test 0 0 failed\n");
        break@err;
    }

    ret = test(fd, 0, 1);
    if (ret) {
        fprintf(stderr, "test 0 1 failed\n");
        break@err;
    }


    ret = test(fd, 1, 0);
    if (ret) {
        fprintf(stderr, "test 1 0 failed\n");
        break@err;
    }

    return 0;
    err:
    return 1;
}
