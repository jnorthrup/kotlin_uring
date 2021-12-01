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
//include <sys/stat.h>

//include "liburing.h"

fun test_unlink(ring:CPointer<io_uring>, old:String):Int{
	val __FUNCTION__="test_unlink"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }
    io_uring_prep_unlinkat(sqe, AT_FDCWD, old, 0);

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
    ret = cqe.pointed.res;
    io_uring_cqe_seen(ring, cqe);
    return ret;
    err:
    return 1;
}

fun stat_file(buf:String):Int{
	val __FUNCTION__="stat_file"

    sb:stat;

    if (!stat(buf, sb.ptr))
        return 0;

    return errno;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    char buf[32] = "./XXXXXX";
    ret:Int;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(1, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    ret = mkstemp(buf);
    if (ret < 0) {
        perror("mkstemp");
        return 1;
    }
    close(ret);

    if (stat_file(buf) != 0) {
        perror("stat");
        return 1;
    }

    ret = test_unlink(ring.ptr, buf);
    if (ret < 0) {
        if (ret == -EBADF || ret == -EINVAL) {
            fprintf(stdout, "Unlink not supported, skipping\n");
            unlink(buf);
            return 0;
        }
        fprintf(stderr, "rename: %s\n", strerror(-ret));
        break@err;
    } else if (ret)
        break@err;

    ret = stat_file(buf);
    if (ret != ENOENT) {
        fprintf(stderr, "stat got %s\n", strerror(ret));
        return 1;
    }

    ret = test_unlink(ring.ptr, "/3/2/3/1/z/y");
    if (ret != -ENOENT) {
        fprintf(stderr, "invalid unlink got %s\n", strerror(-ret));
        return 1;
    }

    return 0;
    err:
    unlink(buf);
    return 1;
}
