/* SPDX-License-Identifier: MIT */
/*
 * Description: test io_uring fallocate
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <sys/types.h>
//include <sys/stat.h>
//include <sys/resource.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

static no_fallocate:Int;

fun test_fallocate_rlimit(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_fallocate_rlimit"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    rlim:rlimit;
    char buf[32];
    fd:Int, ret;

    if (getrlimit(RLIMIT_FSIZE, rlim.ptr) < 0) {
        perror("getrlimit");
        return 1;
    }
    rlim.rlim_cur = 64 * 1024;
    rlim.rlim_max = 64 * 1024;
    if (setrlimit(RLIMIT_FSIZE, rlim.ptr) < 0) {
        perror("setrlimit");
        return 1;
    }

    sprintf(buf, "./XXXXXX");
    fd = mkstemp(buf);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    unlink(buf);

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_fallocate(sqe, fd, 0, 0, 128 * 1024);

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

    if (cqe.pointed.res == -EINVAL) {
        fprintf(stdout, "Fallocate not supported, skipping\n");
        no_fallocate = 1;
        break@out;
    } else if (cqe.pointed.res != -EFBIG) {
        fprintf(stderr, "Expected -EFBIG: %d\n", cqe.pointed.res);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);
    out:
    return 0;
    err:
    return 1;
}

fun test_fallocate(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_fallocate"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    st:stat;
    char buf[32];
    fd:Int, ret;

    sprintf(buf, "./XXXXXX");
    fd = mkstemp(buf);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    unlink(buf);

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_fallocate(sqe, fd, 0, 0, 128 * 1024);

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

    if (cqe.pointed.res == -EINVAL) {
        fprintf(stdout, "Fallocate not supported, skipping\n");
        no_fallocate = 1;
        break@out;
    }
    if (cqe.pointed.res) {
        fprintf(stderr, "cqe.pointed.res=%d\n", cqe.pointed.res);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);

    if (fstat(fd, st.ptr) < 0) {
        perror("stat");
        break@err;
    }

    if (st.st_size != 128 * 1024) {
        fprintf(stderr, "Size mismatch: %llu\n",
                (long:ULong ) st.st_size);
        break@err;
    }

    out:
    return 0;
    err:
    return 1;
}

fun test_fallocate_fsync(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_fallocate_fsync"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    st:stat;
    char buf[32];
    fd:Int, ret, i;

    if (no_fallocate)
        return 0;

    sprintf(buf, "./XXXXXX");
    fd = mkstemp(buf);
    if (fd < 0) {
        perror("open");
        return 1;
    }
    unlink(buf);

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_fallocate(sqe, fd, 0, 0, 128 * 1024);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_fsync(sqe, fd, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }
        if (cqe.pointed.res) {
            fprintf(stderr, "cqe.pointed.res=%d,data=%" PRIu64 "\n", cqe.pointed.res,
                    (uint64_t) cqe.pointed.user_data);
            break@err;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    if (fstat(fd, st.ptr) < 0) {
        perror("stat");
        break@err;
    }

    if (st.st_size != 128 * 1024) {
        fprintf(stderr, "Size mismatch: %llu\n",
                (long:ULong ) st.st_size);
        break@err;
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
        fprintf(stderr, "ring setup failed\n");
        return 1;
    }

    ret = test_fallocate(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_fallocate failed\n");
        return ret;
    }

    ret = test_fallocate_fsync(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_fallocate_fsync failed\n");
        return ret;
    }

    ret = test_fallocate_rlimit(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_fallocate_rlimit failed\n");
        return ret;
    }

    return 0;
}
