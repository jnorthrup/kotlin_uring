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

fun test_rename(ring:CPointer<io_uring>, old:String, new:String):Int{
	val __FUNCTION__="test_rename"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        break@err;
    }

    memset(sqe, 0, sizeof(*sqe));
    sqe.pointed.opcode = IORING_OP_RENAMEAT;
    sqe.pointed.fd = AT_FDCWD;
    sqe.pointed.addr2 = (long:UInt) new;
    sqe.pointed.addr = (long:UInt) old;
    sqe.pointed.len = AT_FDCWD;

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
    char src[32] = "./XXXXXX";
    char dst[32] = "./XXXXXX";
    ret:Int;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(1, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    ret = mkstemp(src);
    if (ret < 0) {
        perror("mkstemp");
        return 1;
    }
    close(ret);

    ret = mkstemp(dst);
    if (ret < 0) {
        perror("mkstemp");
        return 1;
    }
    close(ret);

    if (stat_file(src) != 0) {
        perror("stat");
        return 1;
    }
    if (stat_file(dst) != 0) {
        perror("stat");
        return 1;
    }

    ret = test_rename(ring.ptr, src, dst);
    if (ret < 0) {
        if (ret == -EBADF || ret == -EINVAL) {
            fprintf(stdout, "Rename not supported, skipping\n");
            break@out;
        }
        fprintf(stderr, "rename: %s\n", strerror(-ret));
        break@err;
    } else if (ret)
        break@err;

    if (stat_file(src) != ENOENT) {
        fprintf(stderr, "stat got %s\n", strerror(ret));
        return 1;
    }

    if (stat_file(dst) != 0) {
        perror("stat");
        return 1;
    }

    ret = test_rename(ring.ptr, "/x/y/1/2", "/2/1/y/x");
    if (ret != -ENOENT) {
        fprintf(stderr, "test_rename invalid failed: %d\n", ret);
        return ret;
    }
    out:
    unlink(dst);
    return 0;
    err:
    unlink(src);
    unlink(dst);
    return 1;
}
