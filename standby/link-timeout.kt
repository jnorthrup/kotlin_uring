/* SPDX-License-Identifier: MIT */
/*
 * Description: run various linked timeout cases
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/poll.h>

//include "liburing.h"

fun test_fail_lone_link_timeouts(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_fail_lone_link_timeouts"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    ts.tv_sec = 1;
    ts.tv_nsec = 0;
    sqe.pointed.user_data = 1;
    sqe.pointed.flags |= IOSQE_IO_LINK;

    ret = io_uring_submit(ring);
    if (ret != 1) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret < 0) {
        printf("wait completion %d\n", ret);
        break@err;
    }

    if (cqe.pointed.user_data != 1) {
        fprintf(stderr, "invalid user data %d\n", cqe.pointed.res);
        break@err;
    }
    if (cqe.pointed.res != -EINVAL) {
        fprintf(stderr, "got %d, wanted -EINVAL\n", cqe.pointed.res);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);

    return 0;
    err:
    return 1;
}

fun test_fail_two_link_timeouts(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_fail_two_link_timeouts"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i, nr_wait;

    ts.tv_sec = 1;
    ts.tv_nsec = 0;

    /*
     * sqe_1: write destined to fail
     * use buf=NULL, to do that during the issuing stage
     */
    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_writev(sqe, 0, NULL, 1, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;


    /* sqe_2: valid linked timeout */
    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 2;
    sqe.pointed.flags |= IOSQE_IO_LINK;


    /* sqe_3: invalid linked timeout */
    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 3;

    /* sqe_4: invalid linked timeout */
    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 4;

    ret = io_uring_submit(ring);
    if (ret < 3) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }
    nr_wait = ret;

    for (i in 0 until  nr_wait) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }

        when  (cqe.pointed.user_data)  {
            1 -> 
                if (cqe.pointed.res != -EFAULT && cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "write got %d, wanted -EFAULT "
                                    "or -ECANCELED\n", cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Link timeout got %d, wanted -ECACNCELED\n", cqe.pointed.res);
                    break@err;
                }
                break;
            3 -> 
                /* fall through */
            4 -> 
                if (cqe.pointed.res != -ECANCELED && cqe.pointed.res != -EINVAL) {
                    fprintf(stderr, "Invalid link timeout got %d"
                                    ", wanted -ECACNCELED || -EINVAL\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

/*
 * Test linked timeout with timeout (timeoutception)
 */
fun test_single_link_timeout_ception(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_link_timeout_ception"

    ts1:__kernel_timespec, ts2;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts1.tv_sec = 1;
    ts1.tv_nsec = 0;
    io_uring_prep_timeout(sqe, ts1.ptr, -1U, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts2.tv_sec = 2;
    ts2.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts2.ptr, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret != 2) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
                /* newer kernels allow timeout links */
                if (cqe.pointed.res != -EINVAL && cqe.pointed.res != -ETIME) {
                    fprintf(stderr, "Timeout got %d, wanted "
                                    "-EINVAL or -ETIME\n", cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Link timeout got %d, wanted -ECANCELED\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

/*
 * Test linked timeout with NOP
 */
fun test_single_link_timeout_nop(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_link_timeout_nop"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    io_uring_prep_nop(sqe);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts.tv_sec = 1;
    ts.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret != 2) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
                if (cqe.pointed.res) {
                    fprintf(stderr, "NOP got %d, wanted 0\n", cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Link timeout got %d, wanted -ECACNCELED\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

/*
 * Test read that will not complete, with a linked timeout behind it that
 * has errors in the SQE
 */
fun test_single_link_timeout_error(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_link_timeout_error"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;
    iov:iovec;
    char buffer[128];

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    iov.iov_base = buffer;
    iov.iov_len = sizeof(buffer);
    io_uring_prep_readv(sqe, fds[0], iov.ptr, 1, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts.tv_sec = 1;
    ts.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    /* set invalid field, it'll get failed */
    sqe.pointed.ioprio = 89;
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret != 2) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Read got %d, wanted -ECANCELED\n",
                            cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -EINVAL) {
                    fprintf(stderr, "Link timeout got %d, wanted -EINVAL\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

/*
 * Test read that will complete, with a linked timeout behind it
 */
fun test_single_link_no_timeout(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_single_link_no_timeout"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;
    iov:iovec;
    char buffer[128];

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    iov.iov_base = buffer;
    iov.iov_len = sizeof(buffer);
    io_uring_prep_readv(sqe, fds[0], iov.ptr, 1, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts.tv_sec = 1;
    ts.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    iov.iov_base = buffer;
    iov.iov_len = sizeof(buffer);
    io_uring_prep_writev(sqe, fds[1], iov.ptr, 1, 0);
    sqe.pointed.user_data = 3;

    ret = io_uring_submit(ring);
    if (ret != 3) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  3) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
            3 -> 
                if (cqe.pointed.res != sizeof(buffer)) {
                    fprintf(stderr, "R/W got %d, wanted %d\n", cqe.pointed.res,
                            (int) sizeof(buffer));
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Link timeout %d, wanted -ECANCELED\n",
                            cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    return 0;
    err:
    return 1;
}

/*
 * Test read that will not complete, with a linked timeout behind it
 */
fun test_single_link_timeout(ring:CPointer<io_uring>, nsec:UInt):Int{
	val __FUNCTION__="test_single_link_timeout"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;
    iov:iovec;
    char buffer[128];

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    iov.iov_base = buffer;
    iov.iov_len = sizeof(buffer);
    io_uring_prep_readv(sqe, fds[0], iov.ptr, 1, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }

    ts.tv_sec = 0;
    ts.tv_nsec = nsec;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring);
    if (ret != 2) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  2) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
                if (cqe.pointed.res != -EINTR && cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Read got %d\n", cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -EALREADY && cqe.pointed.res != -ETIME &&
                    cqe.pointed.res != 0) {
                    fprintf(stderr, "Link timeout got %d\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    close(fds[0]);
    close(fds[1]);
    return 0;
    err:
    return 1;
}

fun test_timeout_link_chain1(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_timeout_link_chain1"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;
    iov:iovec;
    char buffer[128];

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    iov.iov_base = buffer;
    iov.iov_len = sizeof(buffer);
    io_uring_prep_readv(sqe, fds[0], iov.ptr, 1, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts.tv_sec = 0;
    ts.tv_nsec = 1000000;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.user_data = 3;

    ret = io_uring_submit(ring);
    if (ret != 3) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  3) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
                if (cqe.pointed.res != -EINTR && cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                /* FASTPOLL kernels can cancel successfully */
                if (cqe.pointed.res != -EALREADY && cqe.pointed.res != -ETIME) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            3 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
        }

        io_uring_cqe_seen(ring, cqe);
    }

    close(fds[0]);
    close(fds[1]);
    return 0;
    err:
    return 1;
}

fun test_timeout_link_chain2(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_timeout_link_chain2"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_poll_add(sqe, fds[0], POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts.tv_sec = 0;
    ts.tv_nsec = 1000000;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 3;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.user_data = 4;

    ret = io_uring_submit(ring);
    if (ret != 4) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  4) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            /* poll cancel really should return -ECANCEL... */
            1 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ETIME) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            3 -> 
            4 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    close(fds[0]);
    close(fds[1]);
    return 0;
    err:
    return 1;
}

fun test_timeout_link_chain3(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_timeout_link_chain3"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_poll_add(sqe, fds[0], POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts.tv_sec = 0;
    ts.tv_nsec = 1000000;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 3;

    /* POLL.pointed.TIMEOUT -> NOP */

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_poll_add(sqe, fds[0], POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 4;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts.tv_sec = 0;
    ts.tv_nsec = 1000000;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 5;

    /* poll on pipe + timeout */

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.user_data = 6;

    /* nop */

    ret = io_uring_submit(ring);
    if (ret != 6) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  6) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            2 -> 
                if (cqe.pointed.res != -ETIME) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            1 -> 
            3 -> 
            4 -> 
            5 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            6 -> 
                if (cqe.pointed.res) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    close(fds[0]);
    close(fds[1]);
    return 0;
    err:
    return 1;
}

fun test_timeout_link_chain4(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_timeout_link_chain4"

    ts:__kernel_timespec;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    fds:Int[2], ret, i;

    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_poll_add(sqe, fds[0], POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts.tv_sec = 0;
    ts.tv_nsec = 1000000;
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.user_data = 3;

    ret = io_uring_submit(ring);
    if (ret != 3) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  3) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            /* poll cancel really should return -ECANCEL... */
            1 -> 
                if (cqe.pointed.res) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            2 -> 
                if (cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
            3 -> 
                if (cqe.pointed.res != -ETIME) {
                    fprintf(stderr, "Req %" PRIu64 " got %d\n", (uint64_t) cqe.pointed.user_data,
                            cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    close(fds[0]);
    close(fds[1]);
    return 0;
    err:
    return 1;
}

fun test_timeout_link_chain5(ring:CPointer<io_uring>):Int{
	val __FUNCTION__="test_timeout_link_chain5"

    ts1:__kernel_timespec, ts2;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    io_uring_prep_nop(sqe);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts1.tv_sec = 1;
    ts1.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts1.ptr, 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.user_data = 2;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        printf("get sqe failed\n");
        break@err;
    }
    ts2.tv_sec = 2;
    ts2.tv_nsec = 0;
    io_uring_prep_link_timeout(sqe, ts2.ptr, 0);
    sqe.pointed.user_data = 3;

    ret = io_uring_submit(ring);
    if (ret != 3) {
        printf("sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  3) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret < 0) {
            printf("wait completion %d\n", ret);
            break@err;
        }
        when  (cqe.pointed.user_data)  {
            1 -> 
            2 -> 
                if (cqe.pointed.res && cqe.pointed.res != -ECANCELED) {
                    fprintf(stderr, "Request got %d, wanted -EINVAL "
                                    "or -ECANCELED\n",
                            cqe.pointed.res);
                    break@err;
                }
                break;
            3 -> 
                if (cqe.pointed.res != -ECANCELED && cqe.pointed.res != -EINVAL) {
                    fprintf(stderr, "Link timeout got %d, wanted -ECANCELED\n", cqe.pointed.res);
                    break@err;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
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

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        printf("ring setup failed\n");
        return 1;
    }

    ret = test_timeout_link_chain1(ring.ptr);
    if (ret) {
        printf("test_single_link_chain1 failed\n");
        return ret;
    }

    ret = test_timeout_link_chain2(ring.ptr);
    if (ret) {
        printf("test_single_link_chain2 failed\n");
        return ret;
    }

    ret = test_timeout_link_chain3(ring.ptr);
    if (ret) {
        printf("test_single_link_chain3 failed\n");
        return ret;
    }

    ret = test_timeout_link_chain4(ring.ptr);
    if (ret) {
        printf("test_single_link_chain4 failed\n");
        return ret;
    }

    ret = test_timeout_link_chain5(ring.ptr);
    if (ret) {
        printf("test_single_link_chain5 failed\n");
        return ret;
    }

    ret = test_single_link_timeout(ring.ptr, 10);
    if (ret) {
        printf("test_single_link_timeout 10 failed\n");
        return ret;
    }

    ret = test_single_link_timeout(ring.ptr, 100000ULL);
    if (ret) {
        printf("test_single_link_timeout 100000 failed\n");
        return ret;
    }

    ret = test_single_link_timeout(ring.ptr, 500000000ULL);
    if (ret) {
        printf("test_single_link_timeout 500000000 failed\n");
        return ret;
    }

    ret = test_single_link_no_timeout(ring.ptr);
    if (ret) {
        printf("test_single_link_no_timeout failed\n");
        return ret;
    }

    ret = test_single_link_timeout_error(ring.ptr);
    if (ret) {
        printf("test_single_link_timeout_error failed\n");
        return ret;
    }

    ret = test_single_link_timeout_nop(ring.ptr);
    if (ret) {
        printf("test_single_link_timeout_nop failed\n");
        return ret;
    }

    ret = test_single_link_timeout_ception(ring.ptr);
    if (ret) {
        printf("test_single_link_timeout_ception failed\n");
        return ret;
    }

    ret = test_fail_lone_link_timeouts(ring.ptr);
    if (ret) {
        printf("test_fail_lone_link_timeouts failed\n");
        return ret;
    }

    ret = test_fail_two_link_timeouts(ring.ptr);
    if (ret) {
        printf("test_fail_two_link_timeouts failed\n");
        return ret;
    }

    return 0;
}
