package test.stdout

import kotlinx.cinterop.*
import linux_uring.*
import platform.posix.fprintf
import simple.CZero.nz
import linux_uring.memcmp as linux_uringMemcmp
import linux_uring.memcpy as linux_uringMemcpy
import linux_uring.strerror as strerror1
import platform.posix.stderr as posixStderr
import platform.posix.strlen as posixStrlen

/* SPDX-License-Identifier: MIT */
/*
 * Description: check that STDOUT write works
 */
//#include <errno.h>
//#include <stdio.h>
//#include <unistd.h>
//#include <stdlib.h>
//#include <string.h>
//#include <fcntl.h>
//
//#include "helpers.h"
//#include "liburing.h"
class Stdout : NativeFreeablePlacement by nativeHeap {

    fun test_pipe_io_fixed(ring: CPointer<io_uring>): Int = memScoped {
        val str: String = "This is a fixed pipe test\n"
        val vecs: CPointer<iovec> = allocArray<iovec>(2)
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val buffer = ByteArray(128)
        val fds = IntArray(2)



        vecs[0].iov_base = malloc(4096)
        linux_uringMemcpy(vecs[0].iov_base, str.cstr, posixStrlen(str))
        vecs[0].iov_len = posixStrlen(str)

        if (pipe(fds.refTo(0)) < 0) {
            perror("pipe")
            return 1
        }

        var ret = io_uring_register_buffers(ring, vecs, 1)
        if (ret.nz) {
            fprintf(posixStderr, "Failed to register buffers: %d\n", ret)
            return 1
        }

        var sqe: CPointer<io_uring_sqe> = io_uring_get_sqe(ring)!!
        io_uring_prep_write_fixed(
            sqe, fds[1], vecs[0].iov_base,
            vecs[0].iov_len.toUInt(), 0, 0
        )
        sqe.pointed.user_data = 1uL

        sqe = io_uring_get_sqe(ring)!!

        vecs[1].iov_base = buffer.refTo(0).getPointer(this)
        vecs[1].iov_len = buffer.size.toULong()
        io_uring_prep_readv(sqe, fds[0], vecs[1].ptr, 1, 0)
        sqe.pointed.user_data = 2u

        var goto: end? = null
        do {
            ret = io_uring_submit(ring)
            if (ret < 0) {
                fprintf(posixStderr, "sqe submit failed: %d\n", ret)
                goto = end.err;break
            } else if (ret != 2) {
                fprintf(posixStderr, "Submitted only %d\n", ret)
                goto = end.err;break
            }

            for (i in 0 until 2) {
                ret = io_uring_wait_cqe(ring, cqe.ptr)
                if (ret < 0) {
                    fprintf(posixStderr, "wait completion %d\n", ret)
                    goto = end.err;break
                }
                val pointed = cqe.pointed!!
                if (pointed.res < 0) {
                    fprintf(
                        posixStderr, "I/O write error on %lu: %s\n",
                        pointed.user_data,
                        strerror1(-pointed.res)
                    )
                    goto = end.err;break
                }
                if (pointed.res != posixStrlen(str).toInt()) {
                    fprintf(
                        posixStderr, "Got %d bytes, wanted %d on %lu\n",
                        pointed.res, strlen(str),
                        pointed.user_data
                    )
                    goto = end.err;break
                }
                if ((pointed.user_data == 2UL) && linux_uringMemcmp(
                        str.cstr,
                        buffer.toCValues(), posixStrlen(str)
                    ).nz
                ) {
                    fprintf(posixStderr, "read data mismatch\n")
                    goto = end.err;break
                }
                io_uring_cqe_seen(ring, cqe.value)
            }
            if (goto != null) break
            io_uring_unregister_buffers(ring)
        } while (false)
        return if (goto == null) 0 else 1
    }

    fun test_stdout_io_fixed(ring: CPointer<io_uring>): Int = memScoped {
        val str = "This is a fixed pipe test\n"
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val vecs: iovec = alloc()
        var ret: Int


        vecs.iov_base = malloc(4096)
        println("memaligned")
        val __n = posixStrlen(str)
        val __src = str.cstr
        val linuxUringmemcpy = linux_uringMemcpy(vecs.iov_base, __src, __n)
        println("memcp'd")
        vecs.iov_len = __n


        ret = io_uring_register_buffers(ring, vecs.ptr, 1)
        if (ret.nz) {
            fprintf(posixStderr, "Failed to register buffers: %d\n", ret)
            return 1
        }
        println("buffers registered")
        val sqe = io_uring_get_sqe(ring)!!

        io_uring_prep_write_fixed(sqe, linux_uring.STDOUT_FILENO, vecs.iov_base, vecs.iov_len.toUInt(), 0, 0)
        println("io_uring_prep_write_fixed done")
        var goto: end? = null
        ret = io_uring_submit(ring)
        do {
            if (ret < 0) {
                fprintf(posixStderr, "sqe submit failed: %d\n", ret)
                goto = end.err;break
            } else if (ret < 1) {
                fprintf(posixStderr, "Submitted only %d\n", ret)
                goto = end.err;break
            }

            ret = io_uring_wait_cqe(ring, cqe.ptr)
            if (ret < 0) {
                fprintf(posixStderr, "wait completion %d\n", ret)
                goto = end.err;break
            }
            val pointed = cqe.pointed!!
            if (pointed.res < 0) {
                fprintf(posixStderr, "STDOUT write error: %s\n", strerror1(-pointed.res))
                goto = end.err;break
            }
            if (pointed.res != vecs.iov_len.toInt()) {
                fprintf(posixStderr, "Got %d write, wanted %d\n", pointed.res, vecs.iov_len)
                goto = end.err;break
            }
            io_uring_cqe_seen(ring, cqe.value)
            io_uring_unregister_buffers(ring)
        } while (false)

        return if (null == goto) 0 else 1
    }

    fun test_stdout_io(ring: CPointer<io_uring>): Int = memScoped {
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val s = "This is a pipe test\n"
        val vecs: iovec = alloc {
            iov_base = s.cstr.ptr
            iov_len = posixStrlen(s)
        }
        val sqe = io_uring_get_sqe(ring)!!
        var goto: end? = null
        do {
            io_uring_prep_writev(sqe, linux_uring.STDOUT_FILENO, vecs.ptr, 1, 0)

            var ret = io_uring_submit(ring)
            if (ret < 0) {
                fprintf(posixStderr, "sqe submit failed: %d\n", ret)
                goto = end.err;break
            } else if (ret < 1) {
                fprintf(posixStderr, "Submitted only %d\n", ret)
                goto = end.err;break
            }

            ret = io_uring_wait_cqe(ring, cqe.ptr)
            if (ret < 0) {
                fprintf(posixStderr, "wait completion %d\n", ret)
                goto = end.err;break
            }
            val pointed = cqe.pointed!!
            if (pointed.res < 0) {
                fprintf(
                    posixStderr, "STDOUT write error: %s\n",
                    strerror1(-pointed.res)
                )
                goto = end.err;break
            }
            if (pointed.res != vecs.iov_len.toInt()) {
                fprintf(
                    posixStderr, "Got %d write, wanted %d\n", pointed.res,
                    vecs.iov_len
                )
                goto = end.err;break
            }
            io_uring_cqe_seen(ring, cqe.value)
        } while (false)
        return if (goto == null) 0 else 1
    }

    fun main(): Int {
        val ring: io_uring = alloc()
        var ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret.nz) {
            fprintf(posixStderr, "ring setup failed\n")
            exit(1)
        }

        ret = test_stdout_io(ring.ptr)
        if (ret.nz) {
            fprintf(posixStderr, "test_pipe_io failed\n")
            exit(ret)
        }

        ret = test_stdout_io_fixed(ring.ptr)
        if (ret.nz) {
            fprintf(posixStderr, "test_pipe_io_fixed failed\n")
            return ret
        }

        ret = test_pipe_io_fixed(ring.ptr)
        if (ret.nz) {
            fprintf(posixStderr, "test_pipe_io_fixed failed\n")
            return ret
        }

        return 0
    }

    companion object {
        enum class end { err }
    }
}

fun main() {
    exit(Stdout().main())
}
