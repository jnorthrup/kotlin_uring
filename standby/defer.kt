/* SPDX-License-Identifier: MIT */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/uio.h>
//include <stdbool.h>

//include "helpers.h"
//include "liburing.h"

#define RING_SIZE 128

struct test_context {
    ring:CPointer<io_uring>;
    sqes:CPointerVarOf<CPointer<io_uring_sqe>>;
    cqes:CPointer<io_uring_cqe>;
    nr:Int;
};

fun free_context(ctx:CPointer<test_context>):Unit{
	val __FUNCTION__="free_context"

    free(ctx.pointed.sqes);
    free(ctx.pointed.cqes);
    memset(ctx, 0, sizeof(*ctx));
}

fun init_context(ctx:CPointer<test_context>, struct io_uring *ring, nr:Int):Int{
	val __FUNCTION__="init_context"

    sqe:CPointer<io_uring_sqe>;
    i:Int;

    memset(ctx, 0, sizeof(*ctx));
    ctx.pointed.nr = nr;
    ctx.pointed.ring = ring;
    ctx.pointed.sqes = t_malloc(sizeof:CPointer<nr>(*ctx.pointed.sqes));
    ctx.pointed.cqes = t_malloc(sizeof:CPointer<nr>(*ctx.pointed.cqes));

    if (!ctx.pointed.sqes || !ctx.pointed.cqes)
        break@err;

    for (i in 0 until  nr) {
        sqe = io_uring_get_sqe(ring);
        if (!sqe)
            break@err;
        io_uring_prep_nop(sqe);
        sqe.pointed.user_data = i;
        ctx.pointed.sqes[i] = sqe;
    }

    return 0;
    err:
    free_context(ctx);
    printf("init context failed\n");
    return 1;
}

fun wait_cqes(ctx:CPointer<test_context>):Int{
	val __FUNCTION__="wait_cqes"

    ret:Int, i;
    cqe:CPointer<io_uring_cqe>;

    for (i in 0 until  ctx.pointed.nr) {
        ret = io_uring_wait_cqe(ctx.pointed.ring, cqe.ptr);

        if (ret < 0) {
            printf("wait_cqes: wait completion %d\n", ret);
            return 1;
        }
        memcpy(ctx.ptr.pointed.cqes[i], cqe, sizeof(*cqe));
        io_uring_cqe_seen(ctx.pointed.ring, cqe);
    }

    return 0;
}

fun test_cancelled_userdata(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_cancelled_userdata"

    ctx:test_context;
    ret:Int, i, nr = 100;

    if (init_context(ctx.ptr, ring, nr))
        return 1;

    for (i in 0 until  nr)
        ctx.sqes[i]->flags |= IOSQE_IO_LINK;

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    if (wait_cqes(ctx.ptr))
        break@err;

    for (i in 0 until  nr) {
        if (i != ctx.cqes[i].user_data) {
            printf("invalid user data\n");
            break@err;
        }
    }

    free_context(ctx.ptr);
    return 0;
    err:
    free_context(ctx.ptr);
    return 1;
}

fun test_thread_link_cancel(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_thread_link_cancel"

    ctx:test_context;
    ret:Int, i, nr = 100;

    if (init_context(ctx.ptr, ring, nr))
        return 1;

    for (i in 0 until  nr)
        ctx.sqes[i]->flags |= IOSQE_IO_LINK;

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    if (wait_cqes(ctx.ptr))
        break@err;

    for (i in 0 until  nr) {
        fail:Boolean = false;

        if (i == 0)
            fail = (ctx.cqes[i].res != -EINVAL);
        else
            fail = (ctx.cqes[i].res != -ECANCELED);

        if (fail) {
            printf("invalid status\n");
            break@err;
        }
    }

    free_context(ctx.ptr);
    return 0;
    err:
    free_context(ctx.ptr);
    return 1;
}

fun test_drain_with_linked_timeout(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_drain_with_linked_timeout"

    const nr:Int = 3;
    ts:__kernel_timespec = {.tv_sec = 1, .tv_nsec = 0,};
    ctx:test_context;
    ret:Int, i;

    if (init_context(ctx.ptr, ring, nr * 2))
        return 1;

    for (i in 0 until  nr) {
        io_uring_prep_timeout(ctx.sqes[i:CPointer<2>], ts.ptr, 0, 0);
        ctx.sqes[i:CPointer<2>]->flags |=  IOSQE_IO_LINK or IOSQE_IO_DRAIN ;
        io_uring_prep_link_timeout(ctx.sqes[i:CPointer<2> + 1], ts.ptr, 0);
    }

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    if (wait_cqes(ctx.ptr))
        break@err;

    free_context(ctx.ptr);
    return 0;
    err:
    free_context(ctx.ptr);
    return 1;
}

fun run_drained(ring:CPointer<io_uring>, nr:Int):Int{
	val __FUNCTION__="run_drained"

    ctx:test_context;
    ret:Int, i;

    if (init_context(ctx.ptr, ring, nr))
        return 1;

    for (i in 0 until  nr)
        ctx.sqes[i]->flags |= IOSQE_IO_DRAIN;

    ret = io_uring_submit(ring);
    if (ret <= 0) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    if (wait_cqes(ctx.ptr))
        break@err;

    free_context(ctx.ptr);
    return 0;
    err:
    free_context(ctx.ptr);
    return 1;
}

fun test_overflow_hung(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_overflow_hung"

    sqe:CPointer<io_uring_sqe>;
    ret:Int, nr = 10;

    while (*ring.pointed.cq.koverflow != 1000) {
        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            printf("get sqe failed\n");
            return 1;
        }

        io_uring_prep_nop(sqe);
        ret = io_uring_submit(ring);
        if (ret <= 0) {
            printf("sqe submit failed: %d\n", ret);
            return 1;
        }
    }

    return run_drained(ring, nr);
}

fun test_dropped_hung(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_dropped_hung"

    nr:Int = 10;

    *ring.pointed.sq.kdropped = 1000;
    return run_drained(ring, nr);
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring, poll_ring, sqthread_ring;
    p:io_uring_params;
    ret:Int;

    if (argc > 1)
        return 0;

    memset(p.ptr, 0, sizeof(p));
    ret = io_uring_queue_init_params(RING_SIZE, ring.ptr, p.ptr);
    if (ret) {
        printf("ring setup failed %i\n", ret);
        return 1;
    }

    ret = io_uring_queue_init(RING_SIZE, poll_ring.ptr, IORING_SETUP_IOPOLL);
    if (ret) {
        printf("poll_ring setup failed\n");
        return 1;
    }


    ret = test_cancelled_userdata(poll_ring.ptr);
    if (ret) {
        printf("test_cancelled_userdata failed\n");
        return ret;
    }

    if (!(p. features and IORING_FEAT_NODROP )) {
        ret = test_overflow_hung(ring.ptr);
        if (ret) {
            printf("test_overflow_hung failed\n");
            return ret;
        }
    }

    ret = test_dropped_hung(ring.ptr);
    if (ret) {
        printf("test_dropped_hung failed\n");
        return ret;
    }

    ret = test_drain_with_linked_timeout(ring.ptr);
    if (ret) {
        printf("test_drain_with_linked_timeout failed\n");
        return ret;
    }

    ret = t_create_ring(RING_SIZE, sqthread_ring.ptr,
                         IORING_SETUP_SQPOLL or IORING_SETUP_IOPOLL );
    if (ret == T_SETUP_SKIP)
        return 0;
    else if (ret < 0)
        return 1;

    ret = test_thread_link_cancel(sqthread_ring.ptr);
    if (ret) {
        printf("test_thread_link_cancel failed\n");
        return ret;
    }

    return 0;
}
