package test.shared_wq

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import linux_uring.*
import linux_uring.include.asString
import simple.CZero.nz
import simple.d
import simple.m

/* SPDX-License-Identifier: MIT */
/*
 * Description: test wq sharing
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>

//include "liburing.h"


fun test_attach_invalid(ringfd: Int): Int = memScoped {
    val __FUNCTION__ = "test_attach_invalid"

    val ring = alloc<io_uring>()

    val p: io_uring_params = alloc {

        flags = IORING_SETUP_ATTACH_WQ
        wq_fd = ringfd.toUInt()
        m d "$__FUNCTION__ io_uring_params = alloc ${this.asString()}"
    }
    val ret = io_uring_queue_init_params(1, ring.ptr, p.ptr)
    m d "$__FUNCTION__ $ret = io_uring_queue_init_params(1, ring.ptr, p.ptr) // ${ring.asString()}"

    err@ for (__err in 0..0) {
        if (ret != -EINVAL) {
            fprintf(stderr, "Attach to zero: %d\n", ret)
            break@err
        }
        return 0
    }/*err:*/
    return 1
}

fun test_attach(ringfd: Int): Int = memScoped {
    val __FUNCTION__ = "test_attach"
    val ring2: io_uring = alloc {}
    val p: io_uring_params = alloc {
        flags = IORING_SETUP_ATTACH_WQ
        wq_fd = ringfd.toUInt()
    }
    val ret = io_uring_queue_init_params(1, ring2.ptr, p.ptr)
    m d "$__FUNCTION__ $ret = io_uring_queue_init_params(1, ${ring2.asString()}, ${p.asString()})"
    if (ret == -EINVAL) {
        fprintf(stdout, "Sharing not supported, skipping\n")
        return 0
    } else if (ret.nz) {
        fprintf(stderr, "Attach to id: %d\n", ret)
        return 1
    }
    io_uring_queue_exit(ring2.ptr)
    return 0
}

fun main(args: Array<String>) {
    exit(main1())
}

fun main1(): Int = memScoped {
    val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"

    val ring: io_uring = alloc()


    var ret = io_uring_queue_init(8, ring.ptr, 0)
    m d "$__FUNCTION__ $ret = io_uring_queue_init(8, ring.ptr, 0)"
    if (ret.nz) {
        fprintf(stderr, "ring setup failed\n")
        return 1
    }

    /* stdout is definitely not an io_uring descriptor */
    ret = test_attach_invalid(2)
    m d "$__FUNCTION__ $ret = test_attach_invalid(2)"
    if (ret.nz) {
        fprintf(stderr, "test_attach_invalid failed\n")
        return ret
    }

    ret = test_attach(ring.ring_fd)
    m d "$__FUNCTION__ $ret = test_attach(ring.ring_fd)"
    if (ret.nz) {
        fprintf(stderr, "test_attach failed\n")
        return ret
    }

    return 0
}
