/* SPDX-License-Identifier: MIT */
/*
 * Description: Single depth submit+wait poll hang test
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "helpers.h"
//include "liburing.h"

#define BLOCKS    4096

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    sqe:CPointer<io_uring_sqe>;
    cqe:CPointer<io_uring_cqe>;
    iov:iovec;
    char buf[32];
    offset:off_t;
    blocks:UInt;
    ret:Int, fd;

    if (argc > 1)
        return 0;

    t_posix_memalign(iov.iov_base.ptr, 4096, 4096);
    iov.iov_len = 4096;

    ret = io_uring_queue_init(1, ring.ptr, IORING_SETUP_IOPOLL);
    if (ret) {
        fprintf(stderr, "ring setup failed\n");
        return 1;

    }

    sprintf(buf, "./XXXXXX");
    fd = mkostemp(buf,  O_WRONLY or O_DIRECT  | O_CREAT);
    if (fd < 0) {
        perror("mkostemp");
        return 1;
    }

    offset = 0;
    blocks = BLOCKS;
    do {
        sqe = io_uring_get_sqe(ring.ptr);
        if (!sqe) {
            fprintf(stderr, "get sqe failed\n");
            break@err;
        }
        io_uring_prep_writev(sqe, fd, iov.ptr, 1, offset);
        ret = io_uring_submit_and_wait(ring.ptr, 1);
        if (ret < 0) {
            fprintf(stderr, "submit_and_wait: %d\n", ret);
            break@err;
        }
        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret < 0) {
            fprintf(stderr, "wait completion: %d\n", ret);
            break@err;
        }
        if (cqe.pointed.res != 4096) {
            if (cqe.pointed.res == -EOPNOTSUPP)
                break@skipped;
            break@err;
        }
        io_uring_cqe_seen(ring.ptr, cqe);
        offset += 4096;
    } while (--blocks);

    close(fd);
    unlink(buf);
    return 0;
    err:
    close(fd);
    unlink(buf);
    return 1;
    skipped:
    fprintf(stderr, "Polling not supported in current dir, test skipped\n");
    close(fd);
    unlink(buf);
    return 0;
}
