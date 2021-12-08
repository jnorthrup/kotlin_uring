/* SPDX-License-Identifier: MIT */
/*
 * Description: test io_uring poll cancel handling
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <inttypes.h>
//include <sys/poll.h>
//include <sys/wait.h>
//include <sys/signal.h>

//include "liburing.h"

struct poll_data {
    is_poll:UInt;
    is_cancel:UInt;
};

fun sig_alrm(sig:Int):Unit{
	val __FUNCTION__="sig_alrm"

    fprintf(stderr, "Timed out!\n");
    exit(1);
}

fun test_poll_cancel(void):Int{
	val __FUNCTION__="test_poll_cancel"

    ring:io_uring;
    pipe1:Int[2];
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    pd:CPointer<poll_data>, pds[2];
    act:sigaction;
    ret:Int;

    if (pipe(pipe1) != 0) {
        perror("pipe");
        return 1;
    }

    ret = io_uring_queue_init(2, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    memset(act.ptr, 0, sizeof(act));
    act.sa_handler = sig_alrm;
    act.sa_flags = SA_RESTART;
    sigaction(SIGALRM, act.ptr, NULL);
    alarm(1);

    sqe = io_uring_get_sqe(ring.ptr);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        return 1;
    }

    io_uring_prep_poll_add(sqe, pipe1[0], POLLIN);

    pds[0].is_poll = 1;
    pds[0].is_cancel = 0;
    io_uring_sqe_set_data(sqe, pds.ptr[0]);

    ret = io_uring_submit(ring.ptr);
    if (ret <= 0) {
        fprintf(stderr, "sqe submit failed\n");
        return 1;
    }

    sqe = io_uring_get_sqe(ring.ptr);
    if (!sqe) {
        fprintf(stderr, "get sqe failed\n");
        return 1;
    }

    pds[1].is_poll = 0;
    pds[1].is_cancel = 1;
    io_uring_prep_poll_remove(sqe, (__u64) (uintptr_t) pds.ptr[0]);
    io_uring_sqe_set_data(sqe, pds.ptr[1]);

    ret = io_uring_submit(ring.ptr);
    if (ret <= 0) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        return 1;
    }

    ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait cqe failed: %d\n", ret);
        return 1;
    }

    pd = io_uring_cqe_get_data(cqe);
    if (pd.pointed.is_poll && cqe.pointed.res != -ECANCELED) {
        fprintf(stderr, "sqe (add=%d/remove=%d) failed with %ld\n",
                pd.pointed.is_poll, pd.pointed.is_cancel,
                (long) cqe.pointed.res);
        return 1;
    } else if (pd.pointed.is_cancel && cqe.pointed.res) {
        fprintf(stderr, "sqe (add=%d/remove=%d) failed with %ld\n",
                pd.pointed.is_poll, pd.pointed.is_cancel,
                (long) cqe.pointed.res);
        return 1;
    }
    io_uring_cqe_seen(ring.ptr, cqe);

    ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
    if (ret < 0) {
        fprintf(stderr, "wait_cqe: %d\n", ret);
        return 1;
    }

    pd = io_uring_cqe_get_data(cqe);
    if (pd.pointed.is_poll && cqe.pointed.res != -ECANCELED) {
        fprintf(stderr, "sqe (add=%d/remove=%d) failed with %ld\n",
                pd.pointed.is_poll, pd.pointed.is_cancel,
                (long) cqe.pointed.res);
        return 1;
    } else if (pd.pointed.is_cancel && cqe.pointed.res) {
        fprintf(stderr, "sqe (add=%d/remove=%d) failed with %ld\n",
                pd.pointed.is_poll, pd.pointed.is_cancel,
                (long) cqe.pointed.res);
        return 1;
    }

    close(pipe1[0]);
    close(pipe1[1]);
    io_uring_cqe_seen(ring.ptr, cqe);
    io_uring_queue_exit(ring.ptr);
    return 0;
}


fun __test_poll_cancel_with_timeouts(void):Int{
	val __FUNCTION__="__test_poll_cancel_with_timeouts"

    ts:__kernel_timespec = {.tv_sec = 10,};
    ring:io_uring, ring2;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, off_nr = 1000;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    ret = io_uring_queue_init(1, ring2.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    /* test timeout-offset triggering path during cancellation */
    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_timeout(sqe, ts.ptr, off_nr, 0);

    /* poll ring2 to trigger cancellation on exit() */
    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_poll_add(sqe, ring2.ring_fd, POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;

    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);

    ret = io_uring_submit(ring.ptr);
    if (ret != 3) {
        fprintf(stderr, "sqe submit failed\n");
        return 1;
    }

    /* just drop all rings/etc. intact, exit() will clean them up */
    return 0;
}

fun test_poll_cancel_with_timeouts(void):Int{
	val __FUNCTION__="test_poll_cancel_with_timeouts"

    ret:Int;
    p:pid_t;

    p = fork();
    if (p == -1) {
        fprintf(stderr, "fork() failed\n");
        return 1;
    }

    if (p == 0) {
        ret = __test_poll_cancel_with_timeouts();
        exit(ret);
    } else {
        wstatus:Int;

        if (waitpid(p, wstatus.ptr, 0) == (pid_t) -1) {
            perror("waitpid()");
            return 1;
        }
        if (!WIFEXITED(wstatus) || WEXITSTATUS(wstatus)) {
            fprintf(stderr, "child failed %i\n", WEXITSTATUS(wstatus));
            return 1;
        }
    }
    return 0;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ret:Int;

    if (argc > 1)
        return 0;

    ret = test_poll_cancel();
    if (ret) {
        fprintf(stderr, "test_poll_cancel failed\n");
        return -1;
    }

    ret = test_poll_cancel_with_timeouts();
    if (ret) {
        fprintf(stderr, "test_poll_cancel_with_timeouts failed\n");
        return -1;
    }

    return 0;
}
