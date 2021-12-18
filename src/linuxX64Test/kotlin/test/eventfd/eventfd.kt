package test.eventfd

import kotlinx.cinterop.*
import linux_uring.*
import platform.linux.EFD_CLOEXEC
import platform.linux.eventfd
import platform.posix.POLLIN
import simple.CZero.nz
import simple.d
import simple.m

/* SPDX-License-Identifier: MIT
 * Description: run various nop tests */
fun main(args: Array<String>): Unit {
    exit(main1())
}

fun main1(): Int = memScoped {
    val __FUNCTION__ = "main"
    m d "$__FUNCTION__"
    val p: io_uring_params = alloc()

    val cqe: CPointerVar<io_uring_cqe> = alloc()
    val ring: io_uring = alloc()
    val ptr: uint64_tVar = alloc()
    val psz = sizeOf<uint64_tVar>()
    val vec: iovec = alloc {
        iov_base = ptr.ptr
        iov_len = psz.convert()
    }

    var ret = io_uring_queue_init_params(8, ring.ptr, p.ptr)
    if (ret.nz) {
        fprintf(stderr, "ring setup failed: %d\n", ret)
        return 1
    }
    if (!(p.features and IORING_FEAT_CUR_PERSONALITY).nz) {
        fprintf(stdout, "Skipping\n")
        return 0
    }

    val evfd = eventfd(0, EFD_CLOEXEC)
    if (evfd < 0) {
        perror("eventfd")
        return 1
    }

    ret = io_uring_register_eventfd(ring.ptr, evfd)
    if (ret.nz) {
        fprintf(stderr, "failed to register evfd: %d\n", ret)
        return 1
    }

    var sqe = io_uring_get_sqe(ring.ptr)!!
    io_uring_prep_poll_add(sqe, evfd, POLLIN)
    sqe.pointed.flags = sqe.pointed.flags.or(IOSQE_IO_LINK.convert())
    sqe.pointed.user_data = 1u

    sqe = io_uring_get_sqe(ring.ptr)!!
    io_uring_prep_readv(sqe, evfd, vec.ptr, 1, 0)
    sqe.pointed.flags = sqe.pointed.flags.or(IOSQE_IO_LINK.convert())
    sqe.pointed.user_data = 2u

    ret = io_uring_submit(ring.ptr)
    if (ret != 2) {
        fprintf(stderr, "submit: %d\n", ret)
        return 1
    }

    sqe = io_uring_get_sqe(ring.ptr)!!
    io_uring_prep_nop(sqe)
    sqe.pointed.user_data = 3u

    ret = io_uring_submit(ring.ptr)
    if (ret != 1) {
        fprintf(stderr, "submit: %d\n", ret)
        return 1
    }

    for (i in 0 until 3) {
        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr)
        if (ret.nz) {
            fprintf(stderr, "wait: %d\n", ret)
            return 1
        }
        val pointed = cqe.pointed!!
        when (pointed.user_data.toInt()) {
            1 ->/* POLLIN */ if (pointed.res != 1) {
                fprintf(stderr, "poll: %d\n", pointed.res)
                return 1
            }
            2 -> if (pointed.res != psz.toInt()) {
                fprintf(stderr, "read: %d\n", pointed.res)
                return 1
            }
            3 -> if (pointed.res.nz) {
                fprintf(stderr, "nop: %d\n", pointed.res)
                return 1
            }
            else -> {}
        }
        io_uring_cqe_seen(ring.ptr, cqe.value)
    }
    return 0
}
