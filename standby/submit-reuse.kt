/* SPDX-License-Identifier: MIT */
/*
 * Test reads that will punt to blocking context, with immediate overwrite
 * of iovec.pointed.iov_base to NULL. If the kernel doesn't properly handle
 * reuse of the iovec, we should get -EFAULT.
 */
//include <unistd.h>
//include <fcntl.h>
//include <stdio.h>
//include <string.h>
//include <stdlib.h>
//include <pthread.h>
//include <sys/time.h>

//include "helpers.h"
//include "liburing.h"

#define STR_SIZE    32768
#define FILE_SIZE    65536

struct thread_data {
    fd1:Int, fd2;
    volatile do_exit:Int;
};

fun flusher(void:CPointer<ByteVar>__data:CPointer<void>{
	val __FUNCTION__="flusher"

    data:CPointer<thread_data> = __data;
    i:Int = 0;

    while (!data.pointed.do_exit) {
        posix_fadvise(data.pointed.fd1, 0, FILE_SIZE, POSIX_FADV_DONTNEED);
        posix_fadvise(data.pointed.fd2, 0, FILE_SIZE, POSIX_FADV_DONTNEED);
        usleep(10);
        i++;
    }

    return NULL;
}

static char str1[STR_SIZE];
static char str2[STR_SIZE];

static ring:io_uring;

static no_stable:Int;

fun prep(fd:Int, char:CPointer<ByteVar>str split:Int, async:Int):Int{
	val __FUNCTION__="prep"

    sqe:CPointer<io_uring_sqe>;
    iovs:iovec[16];
    ret:Int, i;

    if (split) {
        vsize:Int = STR_SIZE / 16;
        void:CPointer<ByteVar>ptr= str;

        for (i in 0 until  16) {
            iovs[i].iov_base = ptr;
            iovs[i].iov_len = vsize;
            ptr += vsize;
        }
    } else {
        iovs[0].iov_base = str;
        iovs[0].iov_len = STR_SIZE;
    }

    sqe = io_uring_get_sqe(ring.ptr);
    io_uring_prep_readv(sqe, fd, iovs, split ? 16 : 1, 0);
    sqe.pointed.user_data = fd;
    if (async)
        sqe.pointed.flags = IOSQE_ASYNC;
    ret = io_uring_submit(ring.ptr);
    if (ret != 1) {
        fprintf(stderr, "submit got %d\n", ret);
        return 1;
    }
    if (split) {
        for (i in 0 until  16)
            iovs[i].iov_base = NULL;
    } else {
        iovs[0].iov_base = NULL;
    }
    return 0;
}

fun wait_nr(nr:Int):Int{
	val __FUNCTION__="wait_nr"

    i:Int, ret;

    for (i in 0 until  nr) {
        cqe:CPointer<io_uring_cqe>;

        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret)
            return ret;
        if (cqe.pointed.res < 0) {
            fprintf(stderr, "cqe.pointed.res=%d\n", cqe.pointed.res);
            return 1;
        }
        io_uring_cqe_seen(ring.ptr, cqe);
    }

    return 0;
}

static mtime_since:ULong (const s:CPointer<timeval>,
        const e:CPointer<timeval>) {
    sec:Long, usec;

    sec = e.pointed.tv_sec - s.pointed.tv_sec;
    usec = (e.pointed.tv_usec - s.pointed.tv_usec);
    if (sec > 0 && usec < 0) {
        sec--;
        usec += 1000000;
    }

    sec *= 1000;
    usec /= 1000;
    return sec + usec;
}

unsigned mtime_since_now:Long(tv:CPointer<timeval>) {
    end:timeval;

    gettimeofday(end.ptr, NULL);
    return mtime_since(tv, end.ptr);
}

fun test_reuse(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>, split:Int, async:Int):Int{
	val __FUNCTION__="test_reuse"

    data:thread_data;
    p:io_uring_params = {};
    fd1:Int, fd2, ret, i;
    tv:timeval;
    thread:pthread_t;
    char:CPointer<ByteVar>fname1= ".reuse.1";
    do_unlink:Int = 1;
    void:CPointer<ByteVar>tret

    ret = io_uring_queue_init_params(32, ring.ptr, p.ptr);
    if (ret) {
        fprintf(stderr, "io_uring_queue_init: %d\n", ret);
        return 1;
    }

    if (!(p. features and IORING_FEAT_SUBMIT_STABLE )) {
        fprintf(stdout, "FEAT_SUBMIT_STABLE not there, skipping\n");
        io_uring_queue_exit(ring.ptr);
        no_stable = 1;
        return 0;
    }

    if (argc > 1) {
        fname1 = argv[1];
        do_unlink = 0;
    } else {
        t_create_file(fname1, FILE_SIZE);
    }

    fd1 = open(fname1, O_RDONLY);
    if (do_unlink)
        unlink(fname1);
    if (fd1 < 0) {
        perror("open fname1");
        break@err;
    }

    t_create_file(".reuse.2", FILE_SIZE);
    fd2 = open(".reuse.2", O_RDONLY);
    unlink(".reuse.2");
    if (fd2 < 0) {
        perror("open .reuse.2");
        break@err;
    }

    data.fd1 = fd1;
    data.fd2 = fd2;
    data.do_exit = 0;
    pthread_create(thread.ptr, NULL, flusher, data.ptr);
    usleep(10000);

    gettimeofday(tv.ptr, NULL);
    for (i in 0 until  1000) {
        ret = prep(fd1, str1, split, async);
        if (ret) {
            fprintf(stderr, "prep1 failed: %d\n", ret);
            break@err;
        }
        ret = prep(fd2, str2, split, async);
        if (ret) {
            fprintf(stderr, "prep1 failed: %d\n", ret);
            break@err;
        }
        ret = wait_nr(2);
        if (ret) {
            fprintf(stderr, "wait_nr: %d\n", ret);
            break@err;
        }
        if (mtime_since_now(tv.ptr) > 5000)
            break;
    }

    data.do_exit = 1;
    pthread_join(thread, tret.ptr);

    close(fd2);
    close(fd1);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err:
    io_uring_queue_exit(ring.ptr);
    return 1;

}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ret:Int, i;

    for (i in 0 until  4) {
        split:Int, async;

        split = ( i and 1 ) != 0;
        async = ( i and 2 ) != 0;

        ret = test_reuse(argc, argv, split, async);
        if (ret) {
            fprintf(stderr, "test_reuse %d %d failed\n", split, async);
            return ret;
        }
        if (no_stable)
            break;
    }

    return 0;
}
