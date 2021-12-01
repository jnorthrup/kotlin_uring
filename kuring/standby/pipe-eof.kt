/*
 * Test that closed pipe reads returns 0, instead of waiting for more
 * data.
 */
//include <errno.h>
//include <stdio.h>
//include <string.h>
//include <unistd.h>
//include <pthread.h>
//include <string.h>
//include "liburing.h"

#define BUFSIZE    512

struct data {
    char:CPointer<ByteVar>str
    fds:Int[2];
};

fun t(void:CPointer<ByteVar>data:CPointer<void>{
	val __FUNCTION__="t"

    d:CPointer<data> = data;
    ret:Int;

    strcpy(d.pointed.str, "This is a test string");
    ret = write(d.pointed.fds[1], d.pointed.str, strlen(d.pointed.str));
    close(d.pointed.fds[1]);
    if (ret < 0)
        perror("write");

    return NULL;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    static char buf[BUFSIZE];
    ring:io_uring;
    thread:pthread_t;
    d:data;
    ret:Int;

    if (pipe(d.fds) < 0) {
        perror("pipe");
        return 1;
    }
    d.str = buf;

    io_uring_queue_init(8, ring.ptr, 0);

    pthread_create(thread.ptr, NULL, t, d.ptr);

    while (1) {
        sqe:CPointer<io_uring_sqe>;
        cqe:CPointer<io_uring_cqe>;

        sqe = io_uring_get_sqe(ring.ptr);
        io_uring_prep_read(sqe, d.fds[0], buf, BUFSIZE, 0);
        ret = io_uring_submit(ring.ptr);
        if (ret != 1) {
            fprintf(stderr, "submit: %d\n", ret);
            return 1;
        }
        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret) {
            fprintf(stderr, "wait: %d\n", ret);
            return 1;
        }

        if (cqe.pointed.res < 0) {
            fprintf(stderr, "Read error: %s\n", strerror(-cqe.pointed.res));
            return 1;
        }
        if (cqe.pointed.res == 0)
            break;
        io_uring_cqe_seen(ring.ptr, cqe);
    }

    pthread_join(thread, NULL);
    io_uring_queue_exit(ring.ptr);
    return 0;
}
