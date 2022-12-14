/* SPDX-License-Identifier: MIT */
/*
 * Description: check that STDOUT write works
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "helpers.h"
//include "liburing.h"

fun test_pipe_io_fixed(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_pipe_io_fixed"

    str:String = "This is a fixed pipe test\n";
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    vecs:iovec[2];
    char buffer[128];
    i:Int, ret, fds[2];

    t_posix_memalign(vecs.ptr[0].iov_base, 4096, 4096);
    memcpy(vecs[0].iov_base, str, strlen(str));
    vecs[0].iov_len = strlen(str);

    if (pipe(fds) < 0) {
        perror("pipe");
        return 1;
    }

    ret = io_uring_register_buffers(ring, vecs, 1);
    if (ret) {
        fprintf(stderr, "Failed to register buffers: %d\n", ret);
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_write_fixed(sqe, fds[1], vecs[0].iov_base,
                              vecs[0].iov_len, 0, 0);
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    vecs[1].iov_base = buffer;
    vecs[1].iov_len = sizeof(buffer);
    io_uring_prep_readv(sqe, fds[0], vecs.ptr[1], 1, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret < 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    } else if (ret != 2) {
        fprintf(stderr, "Submitted only %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }
        if (cqe.pointed.res < 0) {
            fprintf(stderr, "I/O write error on %lu: %s\n",
                    (long:UInt) cqe.pointed.user_data,
                    strerror(-cqe.pointed.res));
            break@err;
        }
        if (cqe.pointed.res != strlen(str)) {
            fprintf(stderr, "Got %d bytes, wanted %d on %lu\n",
                    cqe.pointed.res, (int) strlen(str),
                    (long:UInt) cqe.pointed.user_data);
            break@err;
        }
        if (cqe.pointed.user_data == 2 && memcmp(str, buffer, strlen(str))) {
            fprintf(stderr, "read data mismatch\n");
            break@err;
        }
        io_uring_cqe_seen(ring, cqe);
    }
    io_uring_unregister_buffers(ring);
    return 0;
    err:
    return 1;
}

fun test_stdout_io_fixed(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_stdout_io_fixed"

    str:String = "This is a fixed pipe test\n";
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    vecs:iovec;
    ret:Int;

    t_posix_memalign(vecs.ptr.iov_base, 4096, 4096);
    memcpy(vecs.iov_base, str, strlen(str));
    vecs.iov_len = strlen(str);

    ret = io_uring_register_buffers(ring, vecs.ptr, 1);
    if (ret) {
        fprintf(stderr, "Failed to register buffers: %d\n", ret);
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_write_fixed(sqe, STDOUT_FILENO, vecs.iov_base, vecs.iov_len, 0, 0);

    ret = io_uring_submit(ring);
    if (ret < 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    } else if (ret < 1) {
        fprintf(stderr, "Submitted only %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        break@err;
    }
    if (cqe.pointed.res < 0) {
        fprintf(stderr, "STDOUT write error: %s\n", strerror(-cqe.pointed.res));
        break@err;
    }
    if (cqe.pointed.res != vecs.iov_len) {
        fprintf(stderr, "Got %d write, wanted %d\n", cqe.pointed.res, (int) vecs.iov_len);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);
    io_uring_unregister_buffers(ring);
    return 0;
    err:
    return 1;
}

fun test_stdout_io(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_stdout_io"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    vecs:iovec;
    ret:Int;

    vecs.iov_base = "This is a pipe test\n";
    vecs.iov_len = strlen(vecs.iov_base);

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_writev(sqe, STDOUT_FILENO, vecs.ptr, 1, 0);

    ret = io_uring_submit(ring);
    if (ret < 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    } else if (ret < 1) {
        fprintf(stderr, "Submitted only %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        break@err;
    }
    if (cqe.pointed.res < 0) {
        fprintf(stderr, "STDOUT write error: %s\n",
                strerror(-cqe.pointed.res));
        break@err;
    }
    if (cqe.pointed.res != vecs.iov_len) {
        fprintf(stderr, "Got %d write, wanted %d\n", cqe.pointed.res,
                (int) vecs.iov_len);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);

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
        fprintf(stderr, "ring setup failed\n");
        return 1;
    }

    ret = test_stdout_io(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_pipe_io failed\n");
        return ret;
    }

    ret = test_stdout_io_fixed(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_pipe_io_fixed failed\n");
        return ret;
    }

    ret = test_pipe_io_fixed(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_pipe_io_fixed failed\n");
        return ret;
    }

    return 0;
}
