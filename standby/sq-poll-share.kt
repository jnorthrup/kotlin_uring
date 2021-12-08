/* SPDX-License-Identifier: MIT */
/*
 * Description: test SQPOLL with IORING_SETUP_ATTACH_WQ
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

fun wait_io(ring:CPointer<io_uring>, nr_ios:Int):Int{
	val __FUNCTION__="wait_io"

    cqe:CPointer<io_uring_cqe>;

    while (nr_ios) {
        ret:Int = io_uring_wait_cqe(ring, cqe.ptr);

        if (ret == -EAGAIN) {
            continue;
        } else if (ret) {
            fprintf(stderr, "io_uring_wait_cqe failed %i\n", ret);
            return 1;
        }
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

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    rings:io_uring[NR_RINGS];
    rets:Int[NR_RINGS];
    ios:ULong ;
    i:Int, ret, fd;
    char:CPointer<ByteVar>fname

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

    for (i in 0 until  NR_RINGS) {
        p:io_uring_params = {};

        p.flags = IORING_SETUP_SQPOLL;
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

    ios = 0;
    while (ios < (FILE_SIZE / BS)) {
        for (i in 0 until  NR_RINGS) {
            ret = queue_io(rings.ptr[i], fd, BUFFERS);
            if (ret < 0)
                break@err;
            rets[i] = ret;
        }
        for (i in 0 until  NR_RINGS) {
            if (wait_io(rings.ptr[i], rets[i]))
                break@err;
        }
        ios += BUFFERS;
    }

    return 0;
    err:
    return 1;
}
