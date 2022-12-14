/* SPDX-License-Identifier: MIT */
/*
 * Description: test if personalities work
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

#define FNAME    "/tmp/.tmp.access"
#define USE_UID    1000

static no_personality:Int;

fun open_file(ring:CPointer<io_uring>, cred_id:Int, with_link:Int):Int{
	val __FUNCTION__="open_file"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i, to_submit = 1;

    if (with_link) {
        sqe = io_uring_get_sqe(ring);
        io_uring_prep_nop(sqe);
        sqe.pointed.flags |= IOSQE_IO_LINK;
        sqe.pointed.user_data = 1;
        to_submit++;
    }

    sqe = io_uring_get_sqe(ring);
    io_uring_prep_openat(sqe, -1, FNAME, O_RDONLY, 0);
    sqe.pointed.user_data = 2;

    if (cred_id != -1)
        sqe.pointed.personality = cred_id;

    ret = io_uring_submit(ring);
    if (ret != to_submit) {
        fprintf(stderr, "submit got: %d\n", ret);
        break@err;
    }

    for (i in 0 until  to_submit) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }

        ret = cqe.pointed.res;
        io_uring_cqe_seen(ring, cqe);
    }
    err:
    return ret;
}

fun test_personality(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_personality"

    ret:Int, cred_id;

    ret = io_uring_register_personality(ring);
    if (ret < 0) {
        if (ret == -EINVAL) {
            fprintf(stdout, "Personalities not supported, skipping\n");
            no_personality = 1;
            break@out;
        }
        fprintf(stderr, "register_personality: %d\n", ret);
        break@err;
    }
    cred_id = ret;

    /* create file only owner can open */
    ret = open(FNAME,  O_RDONLY or O_CREAT , 0600);
    if (ret < 0) {
        perror("open");
        break@err;
    }
    close(ret);

    /* verify we can open it */
    ret = open_file(ring, -1, 0);
    if (ret < 0) {
        fprintf(stderr, "current open got: %d\n", ret);
        break@err;
    }

    if (seteuid(USE_UID) < 0) {
        fprintf(stdout, "Can't switch to UID %u, skipping\n", USE_UID);
        break@out;
    }

    /* verify we can't open it with current credentials */
    ret = open_file(ring, -1, 0);
    if (ret != -EACCES) {
        fprintf(stderr, "open got: %d\n", ret);
        break@err;
    }

    /* verify we can open with registered credentials */
    ret = open_file(ring, cred_id, 0);
    if (ret < 0) {
        fprintf(stderr, "credential open: %d\n", ret);
        break@err;
    }
    close(ret);

    /* verify we can open with registered credentials and as a link */
    ret = open_file(ring, cred_id, 1);
    if (ret < 0) {
        fprintf(stderr, "credential open: %d\n", ret);
        break@err;
    }

    if (seteuid(0))
        perror("seteuid");

    ret = io_uring_unregister_personality(ring, cred_id);
    if (ret) {
        fprintf(stderr, "register_personality: %d\n", ret);
        break@err;
    }

    out:
    unlink(FNAME);
    return 0;
    err:
    unlink(FNAME);
    return 1;
}

fun test_invalid_personality(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_invalid_personality"

    ret:Int;

    ret = open_file(ring, 2, 0);
    if (ret != -EINVAL) {
        fprintf(stderr, "invalid personality got: %d\n", ret);
        break@err;
    }
    return 0;
    err:
    return 1;
}

fun test_invalid_unregister(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_invalid_unregister"

    ret:Int;

    ret = io_uring_unregister_personality(ring, 2);
    if (ret != -EINVAL) {
        fprintf(stderr, "invalid personality unregister got: %d\n", ret);
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

    if (geteuid()) {
        fprintf(stderr, "Not root, skipping\n");
        return 0;
    }

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    ret = test_personality(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_personality failed\n");
        return ret;
    }
    if (no_personality)
        return 0;

    ret = test_invalid_personality(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_invalid_personality failed\n");
        return ret;
    }

    ret = test_invalid_unregister(ring.ptr);
    if (ret) {
        fprintf(stderr, "test_invalid_unregister failed\n");
        return ret;
    }

    return 0;
}
