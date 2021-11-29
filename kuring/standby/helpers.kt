/* SPDX-License-Identifier: MIT */
/*
 * Description: Helpers for tests.
 */
//include <stdlib.h>
//include <assert.h>
//include <string.h>
//include <stdio.h>
//include <fcntl.h>
//include <unistd.h>
//include <sys/types.h>

//include "helpers.h"
//include "liburing.h"

/*
 * Helper for allocating memory in tests.
 */
fun t_malloc(size:size_t):CPointer<void>{
	val __FUNCTION__="t_malloc"

    void:CPointer<ByteVar>ret
    ret = malloc(size);
    assert(ret);
    return ret;
}

/*
 * Helper for allocating size bytes aligned on a boundary.
 */
fun t_posix_memalign(memptr:CPointerVarOf<CPointer<ByteVar>>, alignment:size_t, size:size_t):Unit{
	val __FUNCTION__="t_posix_memalign"

    ret:Int;
    ret = posix_memalign(memptr, alignment, size);
    assert(!ret);
}

/*
 * Helper for allocating space for an array of nmemb elements
 * with size bytes for each element.
 */
fun t_calloc(nmemb:size_t, size:size_t):CPointer<void>{
	val __FUNCTION__="t_calloc"

    void:CPointer<ByteVar>ret
    ret = calloc(nmemb, size);
    assert(ret);
    return ret;
}

/*
 * Helper for creating file and write @size byte buf with 0xaa value in the file.
 */
fun t_create_file(file:String, size:size_t):Unit{
	val __FUNCTION__="t_create_file"

    ret:ssize_t;
    char:CPointer<ByteVar>buf
    fd:Int;

    buf = t_malloc(size);
    memset(buf, 0xaa, size);

    fd = open(file,  O_WRONLY or O_CREAT , 0644);
    assert(fd >= 0);

    ret = write(fd, buf, size);
    fsync(fd);
    close(fd);
    free(buf);
    assert(ret == size);
}

/*
 * Helper for creating @buf_num number of iovec
 * with @buf_size bytes buffer of each iovec.
 */
struct t_create_buffers:CPointer<iovec>(buf_num:size_t, buf_size:size_t) {
    vecs:CPointer<iovec>;
    i:Int;

    vecs = t_malloc(sizeof:CPointer<buf_num>(struct iovec));
    for (i in 0 until  buf_num) {
        t_posix_memalign(vecs.ptr[i].iov_base, buf_size, buf_size);
        vecs[i].iov_len = buf_size;
    }
    return vecs;
}

/*
 * Helper for setting up an io_uring instance, skipping if the given user isn't
 * allowed to.
 */
enum t_setup_ret t_create_ring_params(depth:Int, ring:CPointer<io_uring>,
        p:CPointer<io_uring_params>) {
    ret:Int;

    ret = io_uring_queue_init_params(depth, ring, p);
    if (!ret)
        return T_SETUP_OK;
    if ((p.pointed. flags and IORING_SETUP_SQPOLL ) && ret == -EPERM && geteuid()) {
        fprintf(stdout, "SQPOLL skipped for regular user\n");
        return T_SETUP_SKIP;
    }

    fprintf(stderr, "queue_init: %s\n", strerror(-ret));
    return ret;
}

enum t_setup_ret t_create_ring(depth:Int, ring:CPointer<io_uring>,
        flags:UInt) {
    p:io_uring_params = {};

    p.flags = flags;
    return t_create_ring_params(depth, ring, p.ptr);
}

enum t_setup_ret t_register_buffers(ring:CPointer<io_uring>,
        const iovecs:CPointer<iovec>,
        nr_iovecs:UInt) {
    ret:Int;

    ret = io_uring_register_buffers(ring, iovecs, nr_iovecs);
    if (!ret)
        return T_SETUP_OK;

    if ((ret == -EPERM || ret == -ENOMEM) && geteuid()) {
        fprintf(stdout, "too large non-root buffer registration, skip\n");
        return T_SETUP_SKIP;
    }

    fprintf(stderr, "buffer register failed: %s\n", strerror(-ret));
    return ret;
}
