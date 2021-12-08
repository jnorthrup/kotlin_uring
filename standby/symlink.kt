/* SPDX-License-Identifier: MIT */
/*
 * Description: test io_uring symlinkat handling
 */
//include <fcntl.h>
//include <stdio.h>
//include <string.h>
//include <sys/stat.h>
//include <sys/types.h>
//include <unistd.h>

//include "liburing.h"


fun do_symlinkat(ring:CPointer<io_uring>, oldname:String, newname:String):Int{
	val __FUNCTION__="do_symlinkat"

    ret:Int;
    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "sqe get failed\n");
        break@err;
    }
    io_uring_prep_symlinkat(sqe, oldname, AT_FDCWD, newname);

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

fun test_link_contents(linkname:String, expected_contents:String):Int{
	val __FUNCTION__="test_link_contents"

    char buf[128];
    ret:Int = readlink(linkname, buf, 127);
    if (ret < 0) {
        perror("readlink");
        return ret;
    }
    buf[ret] = 0;
    if (strncmp(buf, expected_contents, 128)) {
        fprintf(stderr, "link contents differs from expected: '%s' vs '%s'",
                buf, expected_contents);
        return -1;
    }
    return 0;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    static target:String = "io_uring-symlinkat-test-target";
    static linkname:String = "io_uring-symlinkat-test-link";
    ret:Int;
    ring:io_uring;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "queue init failed: %d\n", ret);
        return ret;
    }

    ret = do_symlinkat(ring.ptr, target, linkname);
    if (ret < 0) {
        if (ret == -EBADF || ret == -EINVAL) {
            fprintf(stdout, "symlinkat not supported, skipping\n");
            break@out;
        }
        fprintf(stderr, "symlinkat: %s\n", strerror(-ret));
        break@err;
    } else if (ret) {
        break@err;
    }

    ret = test_link_contents(linkname, target);
    if (ret < 0)
        break@err1;

    ret = do_symlinkat(ring.ptr, target, linkname);
    if (ret != -EEXIST) {
        fprintf(stderr, "test_symlinkat linkname already exists failed: %d\n", ret);
        break@err1;
    }

    ret = do_symlinkat(ring.ptr, target, "surely/this/does/not/exist");
    if (ret != -ENOENT) {
        fprintf(stderr, "test_symlinkat no parent failed: %d\n", ret);
        break@err1;
    }

    out:
    unlinkat(AT_FDCWD, linkname, 0);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err1:
    unlinkat(AT_FDCWD, linkname, 0);
    err:
    io_uring_queue_exit(ring.ptr);
    return 1;
}
