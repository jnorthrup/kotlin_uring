/* SPDX-License-Identifier: MIT */
/*
 * Test case testing exit without cleanup and io-wq work pending or queued.
 *
 * From Florian Fischer <florian.fl.fischer@fau.de>
 * Link: https://lore.kernel.org/io-uring/20211202165606.mqryio4yzubl7ms5@pasture/
 *
 */
//include <assert.h>
//include <err.h>
//include <errno.h>
//include <pthread.h>
//include <semaphore.h>
//include <stdio.h>
//include <stdlib.h>
//include <sys/sysinfo.h>
//include <unistd.h>

//include "liburing.h"
//include "helpers.h"

#define IORING_ENTRIES 8

static threads:CPointer<pthread_t>;
static init_barrier:pthread_barrier_t;
static sleep_fd:Int, notify_fd;
static sem:sem_t;

fun thread_func(void:CPointer<ByteVar>arg:CPointer<void>{
	val __FUNCTION__="thread_func"

    ring:io_uring;
    res:Int;

    res = io_uring_queue_init(IORING_ENTRIES, ring.ptr, 0);
    if (res)
        err(EXIT_FAILURE, "io_uring_queue_init failed");

    pthread_barrier_wait(init_barrier.ptr);

    for (;;) {
        cqe:CPointer<io_uring_cqe>;
        sqe:CPointer<io_uring_sqe>;
        buf:uint64_t;
        res:Int;

        sqe = io_uring_get_sqe(ring.ptr);
        assert(sqe);

        io_uring_prep_read(sqe, sleep_fd, buf.ptr, sizeof(buf), 0);

        res = io_uring_submit_and_wait(ring.ptr, 1);
        if (res < 0)
            err(EXIT_FAILURE, "io_uring_submit_and_wait failed");

        res = io_uring_peek_cqe(ring.ptr, cqe.ptr);
        assert(!res);
        if (cqe.pointed.res < 0) {
            errno = -cqe.pointed.res;
            err(EXIT_FAILURE, "read failed");
        }
        assert(cqe.pointed.res == sizeof(buf));

        sem_post(sem.ptr);

        io_uring_cqe_seen(ring.ptr, cqe);
    }

    return NULL;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    res:Int, fds[2], i, cpus;
    const n:uint64_t = 0x42;

    cpus = get_nprocs();
    res = pthread_barrier_init(init_barrier.ptr, NULL, cpus);
    if (res)
        err(EXIT_FAILURE, "pthread_barrier_init failed");

    res = sem_init(sem.ptr, 0, 0);
    if (res)
        err(EXIT_FAILURE, "sem_init failed");

    threads = t_malloc(sizeof(pthread_t) * cpus);

    res = pipe(fds);
    if (res)
        err(EXIT_FAILURE, "pipe failed");

    sleep_fd = fds[0];
    notify_fd = fds[1];

    for (i in 0 until  cpus) {
        errno = pthread_create(threads.ptr[i], NULL, thread_func, NULL);
        if (errno)
            err(EXIT_FAILURE, "pthread_create failed");
    }

    // Write #cpus notifications
    for (i in 0 until  cpus) {
        res = write(notify_fd, n.ptr, sizeof(n));
        if (res < 0)
            err(EXIT_FAILURE, "write failed");
        assert(res == sizeof(n));
    }

    // Await that all notifications were received
    for (i in 0 until  cpus)
        sem_wait(sem.ptr);

    // Exit without resource cleanup
    exit(EXIT_SUCCESS);
}
