//package test.update
//
//import kotlinx.cinterop.*
//import linux_uring.*
//import platform.posix.POLLIN
//import platform.posix.exit
//import platform.posix.pthread_tVar
//import simple.CZero.nz
//import test.update.PollMshotUpdate.end.*
//
///* SPDX-License-Identifier: MIT */
///*
// * Description: test many files being polled for and updated
// *
// */
//// #include <errno.h>
//// #include <stdio.h>
//// #include <unistd.h>
//// #include <stdlib.h>
//// #include <string.h>
//// #include <signal.h>
//// #include <sys/poll.h>
//// #include <sys/resource.h>
//// #include <fcntl.h>
//// #include <pthread.h>
////
//// #include "liburing.h"
//fun test.socket_eagain.test.socket_rw.main(args: Array<String>) {
//    val __status = PollMshotUpdate().test.socket_eagain.test.socket_rw.main(args).toInt()
//    println("success")
//    exit(__status = __status)
//}
//
//private const val NFILES = 5000
//private const val BATCH = 500
//private const val NLOOPS = 1000
//private const val RING_SIZE = 512
//
//class PollMshotUpdate : NativePlacement by nativeHeap {
//    enum class end { seen, err_noring, err_nofail, err }
//
//    inner class p_t(var fd: IntArray = IntArray(2), var triggered: Int = 0)
//
//    val p: Array<p_t> = Array<p_t>(NFILES) { p_t() }
//
//    private fun has_poll_update(): Int {
//        val ring: io_uring = alloc()
//        val cqe: CPointerVar<io_uring_cqe> = alloc()
//
//        var has_update = 0
//
//        var ret = io_uring_queue_init(8, ring.ptr, 0)
//        if (ret.nz)
//            return -1
//
//        val sqe = io_uring_get_sqe(ring.ptr)!!
//        io_uring_prep_poll_update(sqe, null, null, POLLIN, IORING_TIMEOUT_UPDATE)
//
//        ret = io_uring_submit(ring.ptr)
//        if (ret != 1)
//            return -1
//
//        ret = io_uring_wait_cqe(ring.ptr, cqe.ptr)
//        if (!ret.nz) {
//            val pointed = cqe.pointed!!
//            if (pointed.res == -ENOENT)
//                has_update = 1
//            else if (pointed.res != -EINVAL)
//                return -1
//            io_uring_cqe_seen(ring.ptr, cqe.value)
//        }
//        io_uring_queue_exit(ring.ptr)
//        return has_update
//    }
//
//    private fun arm_poll(ring: CPointer<io_uring>, off: Int): Int {
//
//        var sqe = io_uring_get_sqe(ring)!!
//        if (sqe == null) {
//            fprintf(stderr, "failed getting sqe\n")
//            return 1
//        }
//
//        io_uring_prep_poll_multishot(sqe, p[off].fd[0], POLLIN)
//        sqe.pointed.user_data = off.toULong()
//        return 0
//    }
//
//    private fun reap_polls(ring: CPointer<io_uring>): Int {
//        val cqe: CPointerVar<io_uring_cqe> = alloc()
//        var c: ByteVar = alloc()
//
//        for (i in 0 until BATCH) {
//            val sqe = io_uring_get_sqe(ring)!!
//            /* update event */
//            io_uring_prep_poll_update(
//                sqe, i.toLong().toCPointer(), null,
//                POLLIN, IORING_POLL_UPDATE_EVENTS
//            )
//            sqe.pointed.user_data = 0x12345678UL
//        }
//
//        var ret = io_uring_submit(ring)
//        if (ret != BATCH) {
//            fprintf(stderr, "submitted %d, %d\n", ret, BATCH)
//            return 1
//        }
//
//        var i = 0
//
//        for (i1 in 0 until 2 * BATCH) {
//            var goto: end? = null; i = i1; do {
//                ret = io_uring_wait_cqe(ring, cqe.ptr)
//                if (ret.nz) {
//                    fprintf(stderr, "wait cqe %d\n", ret)
//                    return ret
//                }
//                val pointed = cqe.pointed!!
//                var off = pointed.user_data
//                if (off == 0x12345678UL) {
//                    goto = end.seen; break
//                }
//                ret = read(p[off.toInt()].fd[0], c.ptr, 1).toInt()
//                if (ret != 1) {
//                    if (ret == -1 && errno == EAGAIN) {
//                        goto = end.seen; break
//                    }
//                    fprintf(stderr, "read got %d/%d\n", ret, errno)
//                    break
//                }
//            } while (false)
//            if (goto == null) break
//            io_uring_cqe_seen(ring, cqe.value)
//        }
//
//        if (i != 2 * BATCH) {
//            fprintf(stderr, "gave up at %d\n", i)
//            return 1
//        }
//
//        return 0
//    }
//
//    private fun trigger_polls(): Int {
//        val c: ByteVar = alloc { value = 89 }
//
//        for (i in 0 until BATCH) {
//            var off: Int
//
//            do {
//                off = rand() % NFILES
//                if (!p[off].triggered.nz)
//                    break
//            } while (1.nz)
//
//            p[off].triggered = 1
//            var ret = write(p[off].fd[1], c.ptr, 1)
//            if (ret != 1L) {
//                fprintf(stderr, "write got %d/%d\n", ret, errno)
//                return 1
//            }
//        }
//
//        return 0
//    }
//
//    private fun trigger_polls_fn(dat: CPointer<ByteVar>): CPointer<ByteVar>? {
//        trigger_polls()
//        return null
//    }
//
//    private fun arm_polls(ring: CPointer<io_uring>): Int {
//
//        var to_arm = NFILES
//
//        var off = 0
//        while (to_arm.nz) {
//
//            var this_arm = to_arm
//            if (this_arm > RING_SIZE)
//                this_arm = RING_SIZE
//
//            for (i in 0 until this_arm) {
//                if (arm_poll(ring, off).nz) {
//                    fprintf(stderr, "arm failed at %d\n", off)
//                    return 1
//                }
//                off++
//            }
//
//            var ret = io_uring_submit(ring)
//            if (ret != this_arm) {
//                fprintf(stderr, "submitted %d, %d\n", ret, this_arm)
//                return 1
//            }
//            to_arm -= this_arm
//        }
//
//        return 0
//    }
//
//    fun test.socket_eagain.test.socket_rw.main(ignored: Array<String>): Int {
//
//        var goto: end? = null
//        val ring: io_uring = alloc()
//        var params: io_uring_params = alloc()
//        var rlim: rlimit = alloc()
//        var thread: pthread_tVar = alloc()
//
//        var ret = has_poll_update()
//        do {
//            if (ret < 0) {
//                fprintf(stderr, "poll update check failed %i\n", ret)
//                return -1
//            } else if (!ret.nz) {
//                fprintf(stderr, "no poll update, skip\n")
//                return 0
//            }
//
//            if (getrlimit(RLIMIT_NOFILE, rlim.ptr) < 0) {
//                perror("getrlimit")
//                goto = err_noring
//            }
//
//            if (rlim.rlim_cur < ((2 * NFILES + 5).toUInt())) {
//                rlim.rlim_cur = ((2 * NFILES + 5).toULong())
//                rlim.rlim_max = rlim.rlim_cur
//                if (setrlimit(RLIMIT_NOFILE, rlim.ptr) < 0) {
//                    if (errno == EPERM) {
//                        goto = err_nofail; break
//                    }
//                    perror("setrlimit")
//                    goto = err_noring; break
//                }
//            }
//
//            for (i in 0 until NFILES) {
//                if (pipe(p[i].fd.refTo(0)) < 0) {
//                    perror("pipe")
//                    goto = err_noring; break
//                }
//                fcntl(p[i].fd[0], F_SETFL, O_NONBLOCK)
//            }
//            if (goto != null) break
//
//            params.flags = IORING_SETUP_CQSIZE
//            params.cq_entries = 4096.toUInt()
//            ret = io_uring_queue_init_params(RING_SIZE.toUInt(), ring.ptr, params.ptr)
//            if (ret.nz) {
//                if (ret == -EINVAL) {
//                    fprintf(stdout, "No CQSIZE, trying without\n")
//                    ret = io_uring_queue_init(RING_SIZE.toUInt(), ring.ptr, 0)
//                    if (ret.nz) {
//                        fprintf(stderr, "ring setup failed: %d\n", ret)
//                        return 1
//                    }
//                }
//            }
//
//            if (arm_polls(ring.ptr).nz) {
//                goto = err
//            }
//
//            for (i in 0 until NLOOPS) {
////                exit (1 ) //we need to disable this for the build to continue
////                pthread_create(thread.ptr, null, staticCFunction(::trigger_polls_fn).reinterpret(), null)
//                ret = reap_polls(ring.ptr)
//                if (ret.nz)
//                    goto = err; break
//                pthread_join(thread.value, null)
//
//                for (j in 0 until NFILES)
//                    p[j].triggered = 0
//            }
//            if (goto != null) break
//            io_uring_queue_exit(ring.ptr)
//        } while (false)
//
//        when (goto) {
//            err -> {
//                io_uring_queue_exit(ring.ptr); fprintf(stderr, "poll-many failed\n")
//                exit(1)
//            }
//            err_noring -> {
//                fprintf(stderr, "poll-many failed\n")
//                exit(1)
//            }
//            err_nofail -> {
//                fprintf(
//                    stderr, "poll-many: not enough files available (and not root), " + "skipped\n"
//                )
//            }
//            else -> {}
//        }
//        return 0
//    }
//}
