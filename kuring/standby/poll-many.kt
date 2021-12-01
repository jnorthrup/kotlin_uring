/* SPDX-License-Identifier: MIT */
/*
 * Description: test many files being polled for
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <signal.h>
//include <sys/poll.h>
//include <sys/resource.h>
//include <fcntl.h>

//include "liburing.h"

#define    NFILES    5000
#define BATCH    500
#define NLOOPS    1000

#define RING_SIZE    512

struct p {
    fd:Int[2];
    triggered:Int;
};

static p:p[NFILES];

fun arm_poll(ring:CPointer<io_uring>, off:Int):Int{
	val __FUNCTION__="arm_poll"

    sqe:CPointer<io_uring_sqe>;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "failed getting sqe\n");
        return 1;
    }

    io_uring_prep_poll_add(sqe, p[off].fd[0], POLLIN);
    sqe.pointed.user_data = off;
    return 0;
}

fun reap_polls(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="reap_polls"

    cqe:CPointer<io_uring_cqe>;
    i:Int, ret, off;
    char c;

    for (i in 0 until  BATCH) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret) {
            fprintf(stderr, "wait cqe %d\n", ret);
            return ret;
        }
        off = cqe.pointed.user_data;
        p[off].triggered = 0;
        ret = read(p[off].fd[0], c.ptr, 1);
        if (ret != 1) {
            fprintf(stderr, "read got %d/%d\n", ret, errno);
            break;
        }
        if (arm_poll(ring, off))
            break;
        io_uring_cqe_seen(ring, cqe);
    }

    if (i != BATCH) {
        fprintf(stderr, "gave up at %d\n", i);
        return 1;
    }

    ret = io_uring_submit(ring);
    if (ret != BATCH) {
        fprintf(stderr, "submitted %d, %d\n", ret, BATCH);
        return 1;
    }

    return 0;
}

fun trigger_polls(void):Int{
	val __FUNCTION__="trigger_polls"

    char c = 89;
    i:Int, ret;

    for (i in 0 until  BATCH) {
        off:Int;

        do {
            off = rand() % NFILES;
            if (!p[off].triggered)
                break;
        } while (1);

        p[off].triggered = 1;
        ret = write(p[off].fd[1], c.ptr, 1);
        if (ret != 1) {
            fprintf(stderr, "write got %d/%d\n", ret, errno);
            return 1;
        }
    }

    return 0;
}

fun arm_polls(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="arm_polls"

    ret:Int, to_arm = NFILES, i, off;

    off = 0;
    while (to_arm) {
        this_arm:Int;

        this_arm = to_arm;
        if (this_arm > RING_SIZE)
            this_arm = RING_SIZE;

        for (i in 0 until  this_arm) {
            if (arm_poll(ring, off)) {
                fprintf(stderr, "arm failed at %d\n", off);
                return 1;
            }
            off++;
        }

        ret = io_uring_submit(ring);
        if (ret != this_arm) {
            fprintf(stderr, "submitted %d, %d\n", ret, this_arm);
            return 1;
        }
        to_arm -= this_arm;
    }

    return 0;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    params:io_uring_params = {};
    rlim:rlimit;
    i:Int, ret;

    if (argc > 1)
        return 0;

    if (getrlimit(RLIMIT_NOFILE, rlim.ptr) < 0) {
        perror("getrlimit");
        break@err_noring;
    }

    if (rlim.rlim_cur < (NFILES:CPointer<2> + 5)) {
        rlim.rlim_cur = (NFILES:CPointer<2> + 5);
        rlim.rlim_max = rlim.rlim_cur;
        if (setrlimit(RLIMIT_NOFILE, rlim.ptr) < 0) {
            if (errno == EPERM)
                break@err_nofail;
            perror("setrlimit");
            break@err_noring;
        }
    }

    for (i in 0 until  NFILES) {
        if (pipe(p[i].fd) < 0) {
            perror("pipe");
            break@err_noring;
        }
    }

    params.flags = IORING_SETUP_CQSIZE;
    params.cq_entries = 4096;
    ret = io_uring_queue_init_params(RING_SIZE, ring.ptr, params.ptr);
    if (ret) {
        if (ret == -EINVAL) {
            fprintf(stdout, "No CQSIZE, trying without\n");
            ret = io_uring_queue_init(RING_SIZE, ring.ptr, 0);
            if (ret) {
                fprintf(stderr, "ring setup failed: %d\n", ret);
                return 1;
            }
        }
    }

    if (arm_polls(ring.ptr))
        break@err;

    for (i in 0 until  NLOOPS) {
        trigger_polls();
        ret = reap_polls(ring.ptr);
        if (ret)
            break@err;
    }

    io_uring_queue_exit(ring.ptr);
    return 0;
    err:
    io_uring_queue_exit(ring.ptr);
    err_noring:
    fprintf(stderr, "poll-many failed\n");
    return 1;
    err_nofail:
    fprintf(stderr, "poll-many: not enough files available (and not root), "
                    "skipped\n");
    return 0;
}
