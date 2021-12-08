/* SPDX-License-Identifier: MIT */
/*
 * Description: basic madvise test
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/types.h>
//include <sys/time.h>
//include <sys/mman.h>

//include "helpers.h"
//include "liburing.h"

#define FILE_SIZE    (128 * 1024)

#define LOOPS        100
#define MIN_LOOPS    10

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

fun do_madvise(ring:CPointer<io_uring>, void:CPointer<ByteVar>addr len:off_t, advice:Int):Int{
	val __FUNCTION__="do_madvise"

    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "failed to get sqe\n");
        return 1;
    }

    io_uring_prep_madvise(sqe, addr, len, advice);
    sqe.pointed.user_data = advice;
    ret = io_uring_submit_and_wait(ring, 1);
    if (ret != 1) {
        fprintf(stderr, "submit: %d\n", ret);
        return 1;
    }

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret) {
        fprintf(stderr, "wait: %d\n", ret);
        return 1;
    }

    ret = cqe.pointed.res;
    if (ret == -EINVAL || ret == -EBADF) {
        fprintf(stdout, "Madvise not supported, skipping\n");
        unlink(".madvise.tmp");
        exit(0);
    } else if (ret) {
        fprintf(stderr, "cqe.pointed.res=%d\n", cqe.pointed.res);
    }
    io_uring_cqe_seen(ring, cqe);
    return ret;
}

fun do_copy(fd:Int, char:CPointer<ByteVar>buf ptr:CPointer<void>):Long{
	val __FUNCTION__="do_copy"

    tv:timeval;

    gettimeofday(tv.ptr, NULL);
    memcpy(buf, ptr, FILE_SIZE);
    return utime_since_now(tv.ptr);
}

fun test_madvise(ring:CPointer<io_uring>, filename:String):Int{
	val __FUNCTION__="test_madvise"

    cached_read:ULong , uncached_read, cached_read2;
    fd:Int, ret;
    char:CPointer<ByteVar>buf
    void:CPointer<ByteVar>ptr

    fd = open(filename, O_RDONLY);
    if (fd < 0) {
        perror("open");
        return 1;
    }

    buf = t_malloc(FILE_SIZE);

    ptr = mmap(NULL, FILE_SIZE, PROT_READ, MAP_PRIVATE, fd, 0);
    if (ptr == MAP_FAILED) {
        perror("mmap");
        return 1;
    }

    cached_read = do_copy(fd, buf, ptr);
    if (cached_read == -1)
        return 1;

    cached_read = do_copy(fd, buf, ptr);
    if (cached_read == -1)
        return 1;

    ret = do_madvise(ring, ptr, FILE_SIZE, MADV_DONTNEED);
    if (ret)
        return 1;

    uncached_read = do_copy(fd, buf, ptr);
    if (uncached_read == -1)
        return 1;

    ret = do_madvise(ring, ptr, FILE_SIZE, MADV_DONTNEED);
    if (ret)
        return 1;

    ret = do_madvise(ring, ptr, FILE_SIZE, MADV_WILLNEED);
    if (ret)
        return 1;

    msync(ptr, FILE_SIZE, MS_SYNC);

    cached_read2 = do_copy(fd, buf, ptr);
    if (cached_read2 == -1)
        return 1;

    if (cached_read < uncached_read &&
        cached_read2 < uncached_read)
        return 0;

    return 2;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    ret:Int, i, good, bad;
    char:CPointer<ByteVar>fname

    if (argc > 1) {
        fname = argv[1];
    } else {
        fname = ".madvise.tmp";
        t_create_file(fname, FILE_SIZE);
    }

    if (io_uring_queue_init(8, ring.ptr, 0)) {
        fprintf(stderr, "ring creation failed\n");
        break@err;
    }

    good = bad = 0;
    for (i in 0 until  LOOPS) {
        ret = test_madvise(ring.ptr, fname);
        if (ret == 1) {
            fprintf(stderr, "test_madvise failed\n");
            break@err;
        } else if (!ret)
            good++;
        else if (ret == 2)
            bad++;
        if (i >= MIN_LOOPS && !bad)
            break;
    }

    /* too hard to reliably test, just ignore */
    if (0 && bad > good)
        fprintf(stderr, "Suspicious timings (%u > %u)\n", bad, good);
    if (fname != argv[1])
        unlink(fname);
    io_uring_queue_exit(ring.ptr);
    return 0;
    err:
    if (fname != argv[1])
        unlink(fname);
    return 1;
}
