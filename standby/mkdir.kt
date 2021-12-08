/* SPDX-License-Identifier: MIT */
/*
 * Description: test io_uring mkdirat handling
 */
//include <fcntl.h>
//include <stdio.h>
//include <string.h>
//include <sys/stat.h>
//include <sys/types.h>
//include <unistd.h>

//include "liburing.h"

fun do_mkdirat(ring:CPointer<io_uring>, fn:String):Int{
	val __FUNCTION__="do_mkdirat"

    ret:Int;
    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "sqe get failed\n");
        break@err;
    }
    io_uring_prep_mkdirat(sqe, AT_FDCWD, fn, 0700);

    ret = io_uring_submit(ring);
    if (ret != 1) {
        fprintf(stderr, "submit failed: %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqes(ring, cqe.ptr, 1, 0, 0);
    if (ret) {
        fprintf(stderr, "wait_cqe failed: %d\n", ret);
        break@err;
    }
    ret = cqe.pointed.res;
    io_uring_cqe_seen(ring, cqe);
    return ret;
    err:
    return 1;
}

fun stat_file(fn:String):Int{
	val __FUNCTION__="stat_file"

    sb:stat;

    if (!stat(fn, sb.ptr))
        return 0;

    return errno;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    static fn:String = "io_uring-mkdirat-test";
    ret:Int;
    ring:io_uring;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "queue init failed: %d\n", ret);
        return ret;
    }

    ret = do_mkdirat(ring.ptr, fn);
    if (ret < 0) {
        if (ret == -EBADF || ret == -EINVAL) {
            fprintf(stdout, "mkdirat not supported, skipping\n");
            break@out;
        }
        fprintf(stderr, "mkdirat: %s\n", strerror(-ret));
        break@err;
    } else if (ret) {
        break@err;
    }

    if (stat_file(fn)) {
        perror("stat");
        break@err;
    }

    ret = do_mkdirat(ring.ptr, fn);
    if (ret != -EEXIST) {
        fprintf(stderr, "do_mkdirat already exists failed: %d\n", ret);
        break@err1;
    }

    ret = do_mkdirat(ring.ptr, "surely/this/wont/exist");
    if (ret != -ENOENT) {
        fprintf(stderr, "do_mkdirat no parent failed: %d\n", ret);
        break@err1;
    }

    out:
    unlinkat(AT_FDCWD, fn, AT_REMOVEDIR);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err1:
    unlinkat(AT_FDCWD, fn, AT_REMOVEDIR);
    err:
    io_uring_queue_exit(ring.ptr);
    return 1;
}
