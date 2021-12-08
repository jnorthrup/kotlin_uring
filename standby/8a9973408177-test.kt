/* SPDX-License-Identifier: MIT */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

fun register_file(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="register_file"

    char buf[32];
    ret:Int, fd;

    sprintf(buf, "./XXXXXX");
    fd = mkstemp(buf);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    ret = io_uring_register_files(ring, fd.ptr, 1);
    if (ret) {
        fprintf(stderr, "file register %d\n", ret);
        return 1;
    }

    ret = io_uring_unregister_files(ring);
    if (ret) {
        fprintf(stderr, "file register %d\n", ret);
        return 1;
    }

    unlink(buf);
    close(fd);
    return 0;
}

fun test_single_fsync(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_fsync"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    char buf[32];
    fd:Int, ret;

    sprintf(buf, "./XXXXXX");
    fd = mkstemp(buf);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    io_uring_prep_fsync(sqe, fd, 0);

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        printf("wait completion %d\n", ret);
        break@err;
    }

    io_uring_cqe_seen(ring, cqe);
    unlink(buf);
    return 0;
    err:
    unlink(buf);
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
        printf("ring setup failed\n");
        return 1;
    }

    ret = register_file(ring.ptr);
    if (ret)
        return ret;
    ret = test_single_fsync(ring.ptr);
    if (ret) {
        printf("test_single_fsync failed\n");
        return ret;
    }

    return 0;
}
