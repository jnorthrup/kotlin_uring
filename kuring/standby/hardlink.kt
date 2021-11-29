/* SPDX-License-Identifier: MIT */
/*
 * Description: test io_uring linkat handling
 */
//include <fcntl.h>
//include <stdio.h>
//include <string.h>
//include <sys/stat.h>
//include <sys/types.h>
//include <unistd.h>

//include "liburing.h"


fun do_linkat(ring:CPointer<io_uring>, oldname:String, newname:String):Int{
	val __FUNCTION__="do_linkat"

    ret:Int;
    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "sqe get failed\n");
        break@err;
    }
    io_uring_prep_linkat(sqe, AT_FDCWD, oldname, AT_FDCWD, newname, 0);

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

fun files_linked_ok(fn1:String, fn2:String):Int{
	val __FUNCTION__="files_linked_ok"

    s1:stat, s2;

    if (stat(fn1, s1.ptr)) {
        fprintf(stderr, "stat(%s): %s\n", fn1, strerror(errno));
        return 0;
    }
    if (stat(fn2, s2.ptr)) {
        fprintf(stderr, "stat(%s): %s\n", fn2, strerror(errno));
        return 0;
    }
    if (s1.st_dev != s2.st_dev || s1.st_ino != s2.st_ino) {
        fprintf(stderr, "linked files have different device / inode numbers\n");
        return 0;
    }
    if (s1.st_nlink != 2 || s2.st_nlink != 2) {
        fprintf(stderr, "linked files have unexpected links count\n");
        return 0;
    }
    return 1;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    static target:String = "io_uring-linkat-test-target";
    static linkname:String = "io_uring-linkat-test-link";
    ret:Int;
    ring:io_uring;

    if (argc > 1)
        return 0;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "queue init failed: %d\n", ret);
        return ret;
    }

    ret = open(target,  O_CREAT or O_RDWR  | O_EXCL, 0600);
    if (ret < 0) {
        perror("open");
        break@err;
    }
    if (write(ret, "linktest", 8) != 8) {
        close(ret);
        break@err1;
    }
    close(ret);

    ret = do_linkat(ring.ptr, target, linkname);
    if (ret < 0) {
        if (ret == -EBADF || ret == -EINVAL) {
            fprintf(stdout, "linkat not supported, skipping\n");
            break@out;
        }
        fprintf(stderr, "linkat: %s\n", strerror(-ret));
        break@err1;
    } else if (ret) {
        break@err1;
    }

    if (!files_linked_ok(linkname, target))
        break@err2;

    ret = do_linkat(ring.ptr, target, linkname);
    if (ret != -EEXIST) {
        fprintf(stderr, "test_linkat linkname already exists failed: %d\n", ret);
        break@err2;
    }

    ret = do_linkat(ring.ptr, target, "surely/this/does/not/exist");
    if (ret != -ENOENT) {
        fprintf(stderr, "test_linkat no parent failed: %d\n", ret);
        break@err2;
    }

    out:
    unlinkat(AT_FDCWD, linkname, 0);
    unlinkat(AT_FDCWD, target, 0);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err2:
    unlinkat(AT_FDCWD, linkname, 0);
    err1:
    unlinkat(AT_FDCWD, target, 0);
    err:
    io_uring_queue_exit(ring.ptr);
    return 1;
}

