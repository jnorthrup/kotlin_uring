/* SPDX-License-Identifier: MIT */
/*
 * Description: Basic IO cancel test
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/types.h>
//include <sys/time.h>
//include <sys/wait.h>
//include <sys/poll.h>

//include "helpers.h"
//include "liburing.h"

#define FILE_SIZE    (128 * 1024)
#define BS        4096
#define BUFFERS        (FILE_SIZE / BS)

static vecs:CPointer<iovec>;

static utime_since:ULong (const s:CPointer<timeval>,
        const e:CPointer<timeval>) {
    sec:Long, usec;

    sec = e.pointed.tv_sec - s.pointed.tv_sec;
    usec = (e.pointed.tv_usec - s.pointed.tv_usec);
    if (sec > 0 && usec < 0) {
        sec--;
        usec += 1000000;
    }

    sec *= 1000000;
    return sec + usec;
}

unsigned utime_since_now:Long(tv:CPointer<timeval>) {
    end:timeval;

    gettimeofday(end.ptr, NULL);
    return utime_since(tv, end.ptr);
}

fun start_io(ring:CPointer<io_uring>, fd:Int, do_write:Int):Int{
	val __FUNCTION__="start_io"

    sqe:CPointer<io_uring_sqe>;
    i:Int, ret;

    for (i in 0 until  BUFFERS) {
        offset:off_t;

        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            fprintf(stderr, "sqe get failed\n");
            break@err;
        }
        offset = BS * (rand() % BUFFERS);
        if (do_write) {
            io_uring_prep_writev(sqe, fd, vecs.ptr[i], 1, offset);
        } else {
            io_uring_prep_readv(sqe, fd, vecs.ptr[i], 1, offset);
        }
        sqe.pointed.user_data = i + 1;
    }

    ret = io_uring_submit(ring);
    if (ret != BUFFERS) {
        fprintf(stderr, "submit got %d, wanted %d\n", ret, BUFFERS);
        break@err;
    }

    return 0;
    err:
    return 1;
}

fun wait_io(ring:CPointer<io_uring>, nr_io:UInt, do_partial:Int):Int{
	val __FUNCTION__="wait_io"

    cqe:CPointer<io_uring_cqe>;
    i:Int, ret;

    for (i in 0 until  nr_io) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret) {
            fprintf(stderr, "wait_cqe=%d\n", ret);
            break@err;
        }
        if (do_partial && cqe.pointed.user_data) {
            if (!(cqe.pointed. user_data and 1 )) {
                if (cqe.pointed.res != BS) {
                    fprintf(stderr, "IO %d wasn't cancelled but got error %d\n", (unsigned) cqe.pointed.user_data, cqe.pointed.res);
                    break@err;
                }
            }
        }
        io_uring_cqe_seen(ring, cqe);
    }
    return 0;
    err:
    return 1;

}

fun do_io(ring:CPointer<io_uring>, fd:Int, do_write:Int):Int{
	val __FUNCTION__="do_io"

    if (start_io(ring, fd, do_write))
        return 1;
    if (wait_io(ring, BUFFERS, 0))
        return 1;
    return 0;
}

fun start_cancel(ring:CPointer<io_uring>, do_partial:Int, async_cancel:Int):Int{
	val __FUNCTION__="start_cancel"

    sqe:CPointer<io_uring_sqe>;
    i:Int, ret, submitted = 0;

    for (i in 0 until  BUFFERS) {
        if (do_partial && ( i and 1 ))
            continue;
        sqe = io_uring_get_sqe(ring);
        if (!sqe) {
            fprintf(stderr, "sqe get failed\n");
            break@err;
        }
        io_uring_prep_cancel(sqe, i + 1, 0);
        if (async_cancel)
            sqe.pointed.flags |= IOSQE_ASYNC;
        sqe.pointed.user_data = 0;
        submitted++;
    }

    ret = io_uring_submit(ring);
    if (ret != submitted) {
        fprintf(stderr, "submit got %d, wanted %d\n", ret, submitted);
        break@err;
    }
    return 0;
    err:
    return 1;
}

/*
 * Test cancels. If 'do_partial' is set, then we only attempt to cancel half of
 * the submitted IO. This is done to verify that cancelling one piece of IO doesn't
 * impact others.
 */
static test_io_cancel:Int(file:String, do_write:Int, do_partial:Int,
        async_cancel:Int) {
    ring:io_uring;
    start_tv:timeval;
    usecs:ULong ;
    to_wait:UInt;
    fd:Int, ret;

    fd = open(file,  O_RDWR or O_DIRECT );
    if (fd < 0) {
        perror("file open");
        break@err;
    }

    ret = io_uring_queue_init(BUFFERS:CPointer<4>, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        break@err;
    }

    if (do_io(ring.ptr, fd, do_write))
        break@err;
    gettimeofday(start_tv.ptr, NULL);
    if (do_io(ring.ptr, fd, do_write))
        break@err;
    usecs = utime_since_now(start_tv.ptr);

    if (start_io(ring.ptr, fd, do_write))
        break@err;
    /* sleep for 1/3 of the total time, to allow some to start/complete */
    usleep(usecs / 3);
    if (start_cancel(ring.ptr, do_partial, async_cancel))
        break@err;
    to_wait = BUFFERS;
    if (do_partial)
        to_wait += BUFFERS / 2;
    else
        to_wait += BUFFERS;
    if (wait_io(ring.ptr, to_wait, do_partial))
        break@err;

    io_uring_queue_exit(ring.ptr);
    close(fd);
    return 0;
    err:
    if (fd != -1)
        close(fd);
    return 1;
}

fun test_dont_cancel_another_ring(void):Int{
	val __FUNCTION__="test_dont_cancel_another_ring"

    ring1:io_uring, ring2;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    char buffer[128];
    ret:Int, fds[2];
    ts:__kernel_timespec = {.tv_sec = 0, .tv_nsec = 100000000,};

    ret = io_uring_queue_init(8, ring1.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        return 1;
    }
    ret = io_uring_queue_init(8, ring2.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        return 1;
    }
    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }

    sqe = io_uring_get_sqe(ring1.ptr);
    if (!sqe) {
        fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__);
        return 1;
    }
    io_uring_prep_read(sqe, fds[0], buffer, 10, 0);
    sqe.pointed.flags |= IOSQE_ASYNC;
    sqe.pointed.user_data = 1;

    ret = io_uring_submit(ring1.ptr);
    if (ret != 1) {
        fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret);
        return 1;
    }

    /* make sure it doesn't cancel requests of the other ctx */
    sqe = io_uring_get_sqe(ring2.ptr);
    if (!sqe) {
        fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__);
        return 1;
    }
    io_uring_prep_cancel(sqe, 1, 0);
    sqe.pointed.user_data = 2;

    ret = io_uring_submit(ring2.ptr);
    if (ret != 1) {
        fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret);
        return 1;
    }

    ret = io_uring_wait_cqe(ring2.ptr, cqe.ptr);
    if (ret) {
        fprintf(stderr, "wait_cqe=%d\n", ret);
        return 1;
    }
    if (cqe.pointed.user_data != 2 || cqe.pointed.res != -ENOENT) {
        fprintf(stderr, "error: cqe %i: res=%i, but expected -ENOENT\n",
                (int) cqe.pointed.user_data, (int) cqe.pointed.res);
        return 1;
    }
    io_uring_cqe_seen(ring2.ptr, cqe);

    ret = io_uring_wait_cqe_timeout(ring1.ptr, cqe.ptr, ts.ptr);
    if (ret != -ETIME) {
        fprintf(stderr, "read got cancelled or wait failed\n");
        return 1;
    }
    io_uring_cqe_seen(ring1.ptr, cqe);

    close(fds[0]);
    close(fds[1]);
    io_uring_queue_exit(ring1.ptr);
    io_uring_queue_exit(ring2.ptr);
    return 0;
}

fun test_cancel_req_across_fork(void):Int{
	val __FUNCTION__="test_cancel_req_across_fork"

    ring:io_uring;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    char buffer[128];
    ret:Int, i, fds[2];
    p:pid_t;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        return 1;
    }
    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }
    sqe = io_uring_get_sqe(ring.ptr);
    if (!sqe) {
        fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__);
        return 1;
    }
    io_uring_prep_read(sqe, fds[0], buffer, 10, 0);
    sqe.pointed.flags |= IOSQE_ASYNC;
    sqe.pointed.user_data = 1;

    ret = io_uring_submit(ring.ptr);
    if (ret != 1) {
        fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret);
        return 1;
    }

    p = fork();
    if (p == -1) {
        fprintf(stderr, "fork() failed\n");
        return 1;
    }

    if (p == 0) {
        sqe = io_uring_get_sqe(ring.ptr);
        if (!sqe) {
            fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__);
            return 1;
        }
        io_uring_prep_cancel(sqe, 1, 0);
        sqe.pointed.user_data = 2;

        ret = io_uring_submit(ring.ptr);
        if (ret != 1) {
            fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret);
            return 1;
        }

        for (i in 0 until  2) {
            ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
            if (ret) {
                fprintf(stderr, "wait_cqe=%d\n", ret);
                return 1;
            }
            when  (cqe.pointed.user_data)  {
                1 -> 
                    if (cqe.pointed.res != -EINTR &&
                        cqe.pointed.res != -ECANCELED) {
                        fprintf(stderr, "%i %i\n", (int) cqe.pointed.user_data, cqe.pointed.res);
                        exit(1);
                    }
                    break;
                2 -> 
                    if (cqe.pointed.res != -EALREADY && cqe.pointed.res) {
                        fprintf(stderr, "%i %i\n", (int) cqe.pointed.user_data, cqe.pointed.res);
                        exit(1);
                    }
                    break;
                default:
                    fprintf(stderr, "%i %i\n", (int) cqe.pointed.user_data, cqe.pointed.res);
                    exit(1);
            }

            io_uring_cqe_seen(ring.ptr, cqe);
        }
        exit(0);
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

    close(fds[0]);
    close(fds[1]);
    io_uring_queue_exit(ring.ptr);
    return 0;
}

fun test_cancel_inflight_exit(void):Int{
	val __FUNCTION__="test_cancel_inflight_exit"

    ts:__kernel_timespec = {.tv_sec = 1, .tv_nsec = 0,};
    ring:io_uring;
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;
    p:pid_t;

    ret = io_uring_queue_init(8, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        return 1;
    }
    p = fork();
    if (p == -1) {
        fprintf(stderr, "fork() failed\n");
        return 1;
    }

    if (p == 0) {
        sqe = io_uring_get_sqe(ring.ptr);
        io_uring_prep_poll_add(sqe, ring.ring_fd, POLLIN);
        sqe.pointed.user_data = 1;
        sqe.pointed.flags |= IOSQE_IO_LINK;

        sqe = io_uring_get_sqe(ring.ptr);
        io_uring_prep_timeout(sqe, ts.ptr, 0, 0);
        sqe.pointed.user_data = 2;

        sqe = io_uring_get_sqe(ring.ptr);
        io_uring_prep_timeout(sqe, ts.ptr, 0, 0);
        sqe.pointed.user_data = 3;

        ret = io_uring_submit(ring.ptr);
        if (ret != 3) {
            fprintf(stderr, "io_uring_submit() failed %s, ret %i\n", __FUNCTION__, ret);
            exit(1);
        }
        exit(0);
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

    for (i in 0 until  3) {
        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret) {
            fprintf(stderr, "wait_cqe=%d\n", ret);
            return 1;
        }
        if ((cqe.pointed.user_data == 1 && cqe.pointed.res != -ECANCELED) ||
            (cqe.pointed.user_data == 2 && cqe.pointed.res != -ECANCELED) ||
            (cqe.pointed.user_data == 3 && cqe.pointed.res != -ETIME)) {
            fprintf(stderr, "%i %i\n", (int) cqe.pointed.user_data, cqe.pointed.res);
            return 1;
        }
        io_uring_cqe_seen(ring.ptr, cqe);
    }

    io_uring_queue_exit(ring.ptr);
    return 0;
}

fun test_sqpoll_cancel_iowq_requests(void):Int{
	val __FUNCTION__="test_sqpoll_cancel_iowq_requests"

    ring:io_uring;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, fds[2];
    char buffer[16];

    ret = io_uring_queue_init(8, ring.ptr, IORING_SETUP_SQPOLL);
    if (ret) {
        fprintf(stderr, "ring create failed: %d\n", ret);
        return 1;
    }
    if (pipe(fds)) {
        perror("pipe");
        return 1;
    }
    /* pin both pipe ends via io-wq */
    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_read(sqe, fds[0], buffer, 10, 0);
    sqe.pointed.flags |=  IOSQE_ASYNC or IOSQE_IO_LINK ;
    sqe.pointed.user_data = 1;

    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_write(sqe, fds[1], buffer, 10, 0);
    sqe.pointed.flags |= IOSQE_ASYNC;
    sqe.pointed.user_data = 2;
    ret = io_uring_submit(ring.ptr);
    if (ret != 2) {
        fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret);
        return 1;
    }

    /* wait for sqpoll to kick in and submit before exit */
    sleep(1);
    io_uring_queue_exit(ring.ptr);

    /* close the write end, so if ring is cancelled properly read() fails*/
    close(fds[1]);
    ret = read(fds[0], buffer, 10);
    close(fds[0]);
    return 0;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    fname:String = ".io-cancel-test";
    i:Int, ret;

    if (argc > 1)
        return 0;

    if (test_dont_cancel_another_ring()) {
        fprintf(stderr, "test_dont_cancel_another_ring() failed\n");
        return 1;
    }

    if (test_cancel_req_across_fork()) {
        fprintf(stderr, "test_cancel_req_across_fork() failed\n");
        return 1;
    }

    if (test_cancel_inflight_exit()) {
        fprintf(stderr, "test_cancel_inflight_exit() failed\n");
        return 1;
    }

    if (test_sqpoll_cancel_iowq_requests()) {
        fprintf(stderr, "test_sqpoll_cancel_iowq_requests() failed\n");
        return 1;
    }

    t_create_file(fname, FILE_SIZE);

    vecs = t_create_buffers(BUFFERS, BS);

    for (i in 0 until  8) {
        write:Int = ( i and 1 ) != 0;
        partial:Int = ( i and 2 ) != 0;
        async:Int = ( i and 4 ) != 0;

        ret = test_io_cancel(fname, write, partial, async);
        if (ret) {
            fprintf(stderr, "test_io_cancel %d %d %d failed\n",
                    write, partial, async);
            break@err;
        }
    }

    unlink(fname);
    return 0;
    err:
    unlink(fname);
    return 1;
}
