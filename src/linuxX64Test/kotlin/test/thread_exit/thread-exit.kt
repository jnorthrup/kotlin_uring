package test.thread_exit

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.t_create_file
import linux_uring.include.t_malloc
import platform.posix.POLLIN
import simple.CZero.nz
import simple.d
import simple.m
import test.thread_exit.ThreadExit.Companion.WSIZE

/* SPDX-License-Identifier: MIT */
/*
 * Description: test that thread pool issued requests don't cancel on thread
 *		exit, but do get canceled once the parent exits. Do both
 *		writes that finish and a poll request that sticks around.
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/poll.h>
//include <pthread.h>

//include "helpers.h"
//include "liburing.h"


data class d
(    var fd: Int = 0,
    var ring: io_uring = nativeHeap.alloc(),
    var off: ULong = 0UL,
    var pipe_fd: Int = 0,
    var err: Int = 0,
    var i: Int = 0,
)

fun do_io(dp1: COpaquePointer?): COpaquePointer? = memScoped {
    val dp = dp1!!.asStableRef<Pair<Array<CPointer<out CPointed>?>, d>>()
    val (g_buf, d) = dp.get()
    m d "$d"
    val buffer = t_malloc(WSIZE.toULong())
    g_buf[d.i] = buffer
    memset(buffer, 0x5a, WSIZE.toULong())
    var sqe = io_uring_get_sqe(d.ring.ptr)
    if (null == sqe) {
        d.err++
        return null
    }
    io_uring_prep_write(sqe, d.fd, buffer, WSIZE.toUInt(), d.off)
    sqe.pointed.user_data = d.off

    sqe = io_uring_get_sqe(d.ring.ptr)
    if (null == sqe) {
        d.err++
        return null
    }
    io_uring_prep_poll_add(sqe, d.pipe_fd, POLLIN)

    val ret = io_uring_submit(d.ring.ptr)
    if (ret != 2)
        d.err++
    return null
}

class ThreadExit : NativeFreeablePlacement by nativeHeap {
    val g_buf: Array<CPointer<out CPointed>?> = arrayOfNulls<COpaquePointer?>(NR_IOS)

    fun free_g_buf() = memScoped {
        for (i in 0 until NR_IOS) g_buf[i]?.let { free(it); }
    }


    fun main(argc: Int, argv: Array<String>): Int = memScoped {
        val ring: io_uring = alloc()
        //     lateinit var  fname:String
        val thread: pthread_tVar = alloc()
        var ret: Int
        val do_unlink: Int
        val fd: Int
        val d = d()
        val fds = IntArray(2)

        if (pipe(fds.refTo(0)) < 0) {
            perror("pipe")
            return 1
        }

        ret = io_uring_queue_init(32, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "ring setup failed\n")
            return 1
        }

        val fname: String
        if (argc > 1) {
            fname = argv[1]
            do_unlink = 0
        } else {
            fname = ".thread.exit"
            do_unlink = 1
            t_create_file(fname, 4096.toULong())
        }

        fd = open(fname, O_WRONLY)
        if (do_unlink.nz)
            unlink(fname)
        if (fd < 0) {
            perror("open")
            return 1
        }

        d.fd = fd
        d.ring = ring
        d.off = 0.toULong()
        d.pipe_fd = fds[0]
        d.err = 0
        for (i in 0 until NR_IOS) {
            d.i = i
            bzero(thread.ptr, sizeOf<pthread_tVar>().toULong())
            val dp = StableRef.create(g_buf to d).asCPointer()
            val __start_routine: CPointer<CFunction<(COpaquePointer? /* = kotlinx.cinterop.CPointer<out kotlinx.cinterop.CPointed>? */) -> CPointer<out CPointed>?>> =
                staticCFunction(::do_io)
            pthread_create(thread.ptr, null, __start_routine, dp)
            pthread_join(thread.value, null)
            d.off += WSIZE.toUInt()
        }
        err@ for (__err in 0..0) {
            val cqe: CPointerVar<io_uring_cqe> = alloc()
            for (i in 0 until NR_IOS) {

                ret = io_uring_wait_cqe(ring.ptr, cqe.ptr)
                if (ret.nz) {
                    fprintf(stderr, "io_uring_wait_cqe=%d\n", ret)
                    break@err
                }
                if (cqe.pointed!!.res != WSIZE) {
                    fprintf(stderr, "cqe.pointed.res=%d, Expected %d\n", cqe.pointed!!.res,
                        WSIZE)
                    break@err
                }
                io_uring_cqe_seen(ring.ptr, cqe.value)
            }

            free_g_buf()
            return d.err

        }
        free_g_buf()
        return 1
    }

    companion object {
        val NR_IOS = 8
        val WSIZE = 512
    }
}

fun main(args: Array<String>) {
    exit(ThreadExit().main(args.size, args))
}