@file:Suppress("VARIABLE_IN_SINGLETON_WITHOUT_THREAD_LOCAL")

package test.timeout_new


import kotlinx.cinterop.*
import linux_uring.*
import platform.posix.gettimeofday
import platform.posix.pthread_tVar
import simple.CZero.nz
import test.timeout_new.TimeoutNew.Companion.cnt


/* SPDX-License-Identifier: MIT */
/*
 * Description: tests for getevents timeout
 *
 */
//include <stdio.h>
//include <sys/time.h>
//include <unistd.h>
//include <pthread.h>
//include "liburing.h"

class TimeoutNew : NativeFreeablePlacement by nativeHeap {


    fun mtime_since_now(tv: CPointer<timeval>): ULong {
        val end = alloc<timeval>()

        gettimeofday(end.ptr.reinterpret(), null)
        return mtime_since(tv, end.ptr.reinterpret()).toULong()
    }


    fun test_return_before_timeout(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_return_before_timeout"

        val cqe: CPointerVar<io_uring_cqe> = alloc()
        var retried: Boolean = false
        val ts: __kernel_timespec = alloc()

        msec_to_ts(ts.ptr, TIMEOUT_MSEC.toUInt())

        val sqe = io_uring_get_sqe(ring)
        io_uring_prep_nop(sqe)

        var ret = io_uring_submit(ring)
        if (ret <= 0) {
            fprintf(stderr, "%s: sqe submit failed: %d\n", __FUNCTION__, ret)
            return 1
        }


        again@ while (true) {
            ret = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
            if (ret == -ETIME && (ring.pointed.flags and IORING_SETUP_SQPOLL).nz && !retried) {
                /* there is a small chance SQPOLL hasn't been waked up yet,
                 * give it one more try.
                 */
                printf("warning: funky SQPOLL timing\n")
                sleep(1)
                retried = true
                continue@again
            } else if (ret < 0) {
                fprintf(stderr, "%s: timeout error: %d\n", __FUNCTION__, ret)
                return 1
            }
            break
        }
        io_uring_cqe_seen(ring, cqe.value)
        return 0
    }

    fun test_return_after_timeout(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_return_after_timeout"

        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val ts: __kernel_timespec = alloc()
        val tv: CPointerVar<timeval> = alloc()

        msec_to_ts(ts.ptr, TIMEOUT_MSEC.toUInt())
        gettimeofday(tv.ptr.reinterpret(), null)
        val ret = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
        val exp: ULong = mtime_since_now(tv.ptr.reinterpret())
        if (ret != -ETIME) {
            fprintf(stderr, "%s: timeout error: %d\n", __FUNCTION__, ret)
            return 1
        }

        if (exp < (TIMEOUT_MSEC / 2).toUInt() || exp > ((TIMEOUT_MSEC * 3) / 2).toUInt()) {
            fprintf(stderr, "%s: Timeout seems wonky (got %llu)\n", __FUNCTION__, exp)
            return 1
        }

        return 0
    }

    /*
     * This is to test issuing a sqe in test.socket_eagain.test.socket_rw.main thread and reaping it in two child-thread
     * at the same time. To see if timeout feature works or not.
     */
    fun test_multi_threads_timeout(): Int {
        val __FUNCTION__ = "test_multi_threads_timeout"
        val ring: io_uring = alloc()
        var both_wait: Boolean = false
        val reap_thread0: pthread_tVar = alloc()
        val reap_thread1: pthread_tVar = alloc()


        var ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "%s: ring setup failed: %d\n", __FUNCTION__, ret.nz)
            return 1
        }

        pthread_create(reap_thread0.ptr, null, staticCFunction(::reap_thread_fn0), ring.ptr)
        pthread_create(reap_thread1.ptr, null, staticCFunction(::reap_thread_fn1), ring.ptr)

        /* make two threads both enter io_uring_wait_cqe_timeout() before issuing the sqe
         * as possible as we can. So that there are two threads in the ctx.pointed.wait queue.
         * In this way, we can test if a cqe wakes up two threads at the same time.
         */
        while (!both_wait) {
            pthread_mutex_lock(mutex.ptr)
            if (cnt == 2)
                both_wait = true
            pthread_mutex_unlock(mutex.ptr)
            sleep(1)
        }

        var sqe = io_uring_get_sqe(ring.ptr)

        io_uring_prep_nop(sqe)

        ret = io_uring_submit(ring.ptr)
        err@ for (__err in 0..0) {
            if (ret <= 0) {
                fprintf(stderr, "%s: sqe submit failed: %d\n", __FUNCTION__, ret)
                break@err
            }

            pthread_join(reap_thread0.value, null)
            pthread_join(reap_thread1.value, null)

            if ((thread_ret0.value.nz && thread_ret0.value != -ETIME) || thread_ret1.value.nz && thread_ret1.value != -ETIME) {
                fprintf(stderr, "%s: thread wait cqe timeout failed: %d %d\n",
                    __FUNCTION__, thread_ret0, thread_ret1)
                break@err
            }

            return 0
//            err:
        }
        return 1
    }

    fun main(argc: Int): Int {
        val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"

        var ring_normal: io_uring = alloc()
        var ring_sq = alloc<io_uring>()

        if (argc > 0)
            return 0

        var ret = io_uring_queue_init(8, ring_normal.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "ring_normal setup failed: %d\n", ret)
            return 1
        }
        if (!(ring_normal.features and IORING_FEAT_EXT_ARG).nz) {
            fprintf(stderr, "feature IORING_FEAT_EXT_ARG not supported, skipping.\n")
            return 0
        }

        ret = test_return_before_timeout(ring_normal.ptr)
        if (ret.nz) {
            fprintf(stderr, "ring_normal: test_return_before_timeout failed\n")
            return ret
        }

        ret = test_return_after_timeout(ring_normal.ptr)
        if (ret.nz) {
            fprintf(stderr, "ring_normal: test_return_after_timeout failed\n")
            return ret
        }

        ret = io_uring_queue_init(8, ring_sq.ptr, IORING_SETUP_SQPOLL)
        if (ret.nz) {
            fprintf(stderr, "ring_sq setup failed: %d\n", ret)
            return 1
        }

        ret = test_return_before_timeout(ring_sq.ptr)
        if (ret.nz) {
            fprintf(stderr, "ring_sq: test_return_before_timeout failed\n")
            return ret
        }

        ret = test_return_after_timeout(ring_sq.ptr)
        if (ret.nz) {
            fprintf(stderr, "ring_sq: test_return_after_timeout failed\n")
            return ret
        }

        ret = test_multi_threads_timeout()
        if (ret.nz) {
            fprintf(stderr, "test_multi_threads_timeout failed\n")
            return ret
        }

        return 0
    }

    companion object {
        const val TIMEOUT_MSEC = 200
        const val TIMEOUT_SEC = 10
        fun msec_to_ts(ts: CPointer<__kernel_timespec>, msec: UInt): Unit {
            val __FUNCTION__ = "msec_to_ts"
            ts.pointed.tv_sec = (msec / 1000u).toLong()
            ts.pointed.tv_nsec = ((msec % 1000u) * 1000000u).toLong()
        }

        fun mtime_since(s: CPointer<timeval>, e: CPointer<timeval>): Long {
            var sec = e.pointed.tv_sec - s.pointed.tv_sec
            var usec = (e.pointed.tv_usec - s.pointed.tv_usec)
            if (sec > 0 && usec < 0) {
                sec--
                usec += 1_000_000
            }

            sec *= 1000
            usec /= 1000
            return (sec + usec).toLong()
        }

        var thread_ret0 = nativeHeap.alloc<IntVar>()
        var thread_ret1: IntVar = nativeHeap.alloc()
        var cnt = 0
        var mutex: pthread_mutex_t = nativeHeap.alloc()


    }
}

fun main(args: Array<String>) {
    exit(TimeoutNew().main(args.size))
}

fun __reap_thread_fn(data: COpaquePointer?): COpaquePointer? = memScoped {
    val __FUNCTION__ = "__reap_thread_fn"

    val ring: CPointer<io_uring> = data!!.asStableRef<io_uring>().asCPointer().reinterpret()
    val cqe: CPointerVar<io_uring_cqe> = alloc()
    val ts: __kernel_timespec = alloc()

    TimeoutNew.msec_to_ts(ts.ptr, TimeoutNew.TIMEOUT_SEC.toUInt())
    pthread_mutex_lock(TimeoutNew.mutex.ptr)
    cnt++
    pthread_mutex_unlock(TimeoutNew.mutex.ptr)
    val ioUringWaitCqeTimeout = io_uring_wait_cqe_timeout(ring, cqe.ptr, ts.ptr)
    return ioUringWaitCqeTimeout.toLong().toCPointer<COpaque>()?.reinterpret()
}

fun reap_thread_fn0(data: COpaquePointer?): COpaquePointer? {
    val __FUNCTION__ = "reap_thread_fn0"

    TimeoutNew.thread_ret0.value = __reap_thread_fn(data).toLong().toInt()

    return null
}

fun reap_thread_fn1(data: COpaquePointer?): COpaquePointer? {
    val __FUNCTION__ = "reap_thread_fn1"

    TimeoutNew.thread_ret1.value = __reap_thread_fn(data).toLong().toInt()
    return null
}
