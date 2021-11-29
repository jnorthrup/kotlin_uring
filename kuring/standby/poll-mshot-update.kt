/* SPDX-License-Identifier: MIT */
/*
 * Description: test many files being polled for and updated
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
//include <pthread.h>

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

fun has_poll_update(void):Int{
	val __FUNCTION__="has_poll_update"

    ring:io_uring;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    has_update:Boolean = false;
    ret:Int;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret)
        return -1;

    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_poll_update(sqe, NULL, NULL, POLLIN, IORING_TIMEOUT_UPDATE);

    ret = io_uring_submit(ring.ptr);
    if (ret != 1)
        return -1;

    ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
    if (!ret) {
        if (cqe.pointed.res == -ENOENT)
            has_update = true;
        else if (cqe.pointed.res != -EINVAL)
            return -1;
        io_uring_cqe_seen(ring.ptr, cqe);
    }
    io_uring_queue_exit(ring.ptr);
    return has_update;
}

fun arm_poll(ring:CPointer<io_uring>, off:Int):Int{
	val __FUNCTION__="arm_poll"

    sqe:CPointer<io_uring_sqe>;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "failed getting sqe\n");
        return 1;
    }

    io_uring_prep_poll_multishot(sqe, p[off].fd[0], POLLIN);
    sqe.pointed.user_data = off;
    return 0;
}

fun reap_polls(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="reap_polls"

    cqe:CPointer<io_uring_cqe>;
    i:Int, ret, off;
    char c;

    for (i in 0 until  BATCH) {
        sqe:CPointer<io_uring_sqe>;

        sqe = io_uring_get_sqe(ring);
        /* update event */
        io_uring_prep_poll_update(sqe, (void *) (long:UInt) i, NULL,
                                  POLLIN, IORING_POLL_UPDATE_EVENTS);
        sqe.pointed.user_data = 0x12345678;
    }

    ret = io_uring_submit(ring);
    if (ret != BATCH) {
        fprintf(stderr, "submitted %d, %d\n", ret, BATCH);
        return 1;
    }

    for (i in 0 until  BATCH:CPointer<2>) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret) {
            fprintf(stderr, "wait cqe %d\n", ret);
            return ret;
        }
        off = cqe.pointed.user_data;
        if (off == 0x12345678)
            break@seen;
        ret = read(p[off].fd[0], c.ptr, 1);
        if (ret != 1) {
            if (ret == -1 && errno == EAGAIN)
                break@seen;
            fprintf(stderr, "read got %d/%d\n", ret, errno);
            break;
        }
        seen:
        io_uring_cqe_seen(ring, cqe);
    }

    if (i != BATCH:CPointer<2>) {
        fprintf(stderr, "gave up at %d\n", i);
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

fun trigger_polls_fn(void:CPointer<ByteVar>data:CPointer<void>{
	val __FUNCTION__="trigger_polls_fn"

    trigger_polls();
    return NULL;
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
    thread:pthread_t;
    i:Int, j, ret;

    if (argc > 1)
        return 0;

    ret = has_poll_update();
    if (ret < 0) {
        fprintf(stderr, "poll update check failed %i\n", ret);
        return -1;
    } else if (!ret) {
        fprintf(stderr, "no poll update, skip\n");
        return 0;
    }

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
        fcntl(p[i].fd[0], F_SETFL, O_NONBLOCK);
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
        pthread_create(thread.ptr, NULL, trigger_polls_fn, NULL);
        ret = reap_polls(ring.ptr);
        if (ret)
            break@err;
        pthread_join(thread, NULL);

        for (j in 0 until  NFILES)
            p[j].triggered = 0;
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
