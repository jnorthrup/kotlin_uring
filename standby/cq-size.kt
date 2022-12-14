/* SPDX-License-Identifier: MIT */
/*
 * Description: test CQ ring sizing
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    p:io_uring_params;
    ring:io_uring;
    ret:Int;

    if (argc > 1)
        return 0;

    memset(p.ptr, 0, sizeof(p));
    p.flags = IORING_SETUP_CQSIZE;
    p.cq_entries = 64;

    ret = io_uring_queue_init_params(4, ring.ptr, p.ptr);
    if (ret) {
        if (ret == -EINVAL) {
            printf("Skipped, not supported on this kernel\n");
            break@done;
        }
        printf("ring setup failed\n");
        return 1;
    }

    if (p.cq_entries < 64) {
        printf("cq entries invalid (%d)\n", p.cq_entries);
        break@err;
    }
    io_uring_queue_exit(ring.ptr);

    memset(p.ptr, 0, sizeof(p));
    p.flags = IORING_SETUP_CQSIZE;
    p.cq_entries = 0;

    ret = io_uring_queue_init_params(4, ring.ptr, p.ptr);
    if (ret >= 0) {
        printf("zero sized cq ring succeeded\n");
        io_uring_queue_exit(ring.ptr);
        break@err;
    }

    if (ret != -EINVAL) {
        printf("io_uring_queue_init_params failed, but not with -EINVAL"
               ", returned error %d (%s)\n", ret, strerror(-ret));
        break@err;
    }

    done:
    return 0;
    err:
    return 1;
}
