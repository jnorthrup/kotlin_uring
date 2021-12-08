/* SPDX-License-Identifier: MIT */
/*
 * Based on a test case from Josef Grieb - test that we can exit without
 * hanging if we have the task file table pinned by a request that is linked
 * to another request that doesn't finish.
 */
//include <errno.h>
//include <fcntl.h>
//include <netinet/in.h>
//include <stdio.h>
//include <stdlib.h>
//include <string.h>
//include <strings.h>
//include <sys/socket.h>
//include <unistd.h>
//include <poll.h>
//include "liburing.h"

#define BACKLOG 512

#define PORT 9100

static ring:io_uring;

fun add_poll(ring:CPointer<io_uring>, fd:Int):Unit{
	val __FUNCTION__="add_poll"

    sqe:CPointer<io_uring_sqe>;

    sqe = io_uring_get_sqe(ring);
    io_uring_prep_poll_add(sqe, fd, POLLIN);
    sqe.pointed.flags |= IOSQE_IO_LINK;
}

fun add_accept(ring:CPointer<io_uring>, fd:Int):Unit{
	val __FUNCTION__="add_accept"

    sqe:CPointer<io_uring_sqe>;

    sqe = io_uring_get_sqe(ring);
    io_uring_prep_accept(sqe, fd, 0, 0,  SOCK_NONBLOCK or SOCK_CLOEXEC );
}

fun setup_io_uring(void):Int{
	val __FUNCTION__="setup_io_uring"

    ret:Int;

    ret = io_uring_queue_init(16, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "Unable to setup io_uring: %s\n", strerror(-ret));
        return 1;
    }

    return 0;
}

fun alarm_sig(sig:Int):Unit{
	val __FUNCTION__="alarm_sig"

    exit(0);
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    serv_addr:sockaddr_in;
    cqe:CPointer<io_uring_cqe>;
    ret:Int, sock_listen_fd;
    const val:Int = 1;
    i:Int;

    if (argc > 1)
        return 0;

    sock_listen_fd = socket(AF_INET,  SOCK_STREAM or SOCK_NONBLOCK , 0);
    if (sock_listen_fd < 0) {
        perror("socket");
        return 1;
    }

    setsockopt(sock_listen_fd, SOL_SOCKET, SO_REUSEADDR, val.ptr, sizeof(val));

    memset(serv_addr.ptr, 0, sizeof(serv_addr));
    serv_addr.sin_family = AF_INET;
    serv_addr.sin_addr.s_addr = INADDR_ANY;

    for (i in 0 until  100) {
        serv_addr.sin_port = htons(PORT + i);

        ret = bind(sock_listen_fd, (struct sockaddr *) serv_addr.ptr, sizeof(serv_addr));
        if (!ret)
            break;
        if (errno != EADDRINUSE) {
            fprintf(stderr, "bind: %s\n", strerror(errno));
            return 1;
        }
        if (i == 99) {
            printf("Gave up on finding a port, skipping\n");
            break@out;
        }
    }

    if (listen(sock_listen_fd, BACKLOG) < 0) {
        perror("Error listening on socket\n");
        return 1;
    }

    if (setup_io_uring())
        return 1;

    add_poll(ring.ptr, sock_listen_fd);
    add_accept(ring.ptr, sock_listen_fd);

    ret = io_uring_submit(ring.ptr);
    if (ret != 2) {
        fprintf(stderr, "submit=%d\n", ret);
        return 1;
    }

    signal(SIGALRM, alarm_sig);
    alarm(1);

    ret = io_uring_wait_cqe(ring.ptr, cqe.ptr);
    if (ret) {
        fprintf(stderr, "wait_cqe=%d\n", ret);
        return 1;
    }

    out:
    io_uring_queue_exit(ring.ptr);
    return 0;
}
