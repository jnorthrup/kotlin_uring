package test.opath

import kotlinx.cinterop.*
import linux_uring.*

// SPDX-License-Identifier: MIT

// Test program for io_uring IORING_OP_CLOSE with O_PATH file.
// Author: Clayton Harris <bugs@claycon.org>, 2020-06-07

// linux                5.6.14-300.fc32.x86_64
// gcc                  10.1.1-1.fc32
// liburing.x86_64      0.5-1.fc32

// gcc -O2 -Wall -Wextra -std=c11 -o close_opath close_opath.c -luring
// ./close_opath testfilepath

//#include <errno.h>
//#include <fcntl.h>
//#include <liburing.h>
//#include <sys/stat.h>
//#include <stdio.h>
//#include <string.h>
//#include <unistd.h>


val _GNU_SOURCE = 1
val _FILE_OFFSET_BITS = 64

class oflgs_t(var oflags: Int, var flnames: String)

class OpathApp : NativeFreeablePlacement by nativeHeap {
    fun test_io_uring_close(ring: CPointer<io_uring>, fd: Int): Int {
        val cqe: CPointerVar<io_uring_cqe> = alloc()

        val sqe = io_uring_get_sqe(ring)!!

        io_uring_prep_close(sqe, fd)

        var ret = io_uring_submit(ring)
        if (ret < 0) {
            fprintf(
                stderr, "io_uring_submit() failed, errno %d: %s\n",
                -ret, strerror(-ret)
            )
            return ret
        }

        ret = io_uring_wait_cqe(ring, cqe.ptr)
        when {
            ret < 0 -> {
                fprintf(
                    stderr, "io_uring_wait_cqe() failed, errno %d: %s\n",
                    -ret, strerror(-ret)
                )
                return ret
            }
            else -> {
                val pointed = cqe.pointed!!
                ret = pointed.res
                io_uring_cqe_seen(ring, cqe.value)

                return if (ret < 0 && ret != -EOPNOTSUPP && ret != -EINVAL && ret != -EBADF) {
                    fprintf(stderr, "io_uring close() failed, errno %d: %s\n", -ret, strerror(-ret))
                    ret
                } else 0
            }
        }
    }

    fun open_file(path: String, oflgs: oflgs_t): Int {
        val fd = openat(AT_FDCWD, path, oflgs.oflags, 0)
        if (fd < 0) {
            val err: Int = errno
            fprintf(
                stderr, "openat(%s, %s) failed, errno %d: %s\n",
                path, oflgs.flnames, err, strerror(err)
            )
            return -err
        }
        return fd
    }

    val oflgs = arrayOf<oflgs_t>(
        oflgs_t(O_RDONLY, "O_RDONLY"),
        oflgs_t(__O_PATH, "O_PATH")
    )

    fun main(): Int {
        val fname: String = "."
        val ring: io_uring = alloc()


        var ret = io_uring_queue_init(2, ring.ptr, 0)
        if (ret < 0) {
            fprintf(
                stderr, "io_uring_queue_init() failed, errno %d: %s\n",
                -ret, strerror(-ret)
            )
            return 0x02
        }
        ret = 0
        for ((i, o) in oflgs.withIndex()) {


            val fd = open_file(fname, o)
            if (fd < 0) {
                ret = ret or 0x02
                break
            }

            /* Should always succeed */
            if (test_io_uring_close(ring.ptr, fd) < 0)
                ret = ret or (0x04 shl i)
        }

        io_uring_queue_exit(ring.ptr)
        return ret
    }
}

fun main(args: Array<String>) {

    exit(OpathApp().main())
}
