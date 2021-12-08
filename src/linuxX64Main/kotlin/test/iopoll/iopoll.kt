package test.iopoll

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.t_create_buffers
import linux_uring.include.t_create_file
import linux_uring.include.t_create_ring
import linux_uring.include.t_register_buffers
import platform.posix.set_posix_errno

import simple.CZero.bool
import simple.CZero.nz
import simple.d
import simple.m
import test.cat.io_uring_enter
import test.lfsopenat.OPEN_FLAGS

/* SPDX-License-Identifier: MIT */
/*
 * Description: basic read/write tests with polled IO
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/types.h>
//include <sys/poll.h>
//include <sys/eventfd.h>
//include <sys/resource.h>
//include "helpers.h"
//include "liburing.h"
//include "../src/syscall.h"


class IoPoll : NativeFreeablePlacement by nativeHeap {

    lateinit var vecs: CArrayPointer<iovec>
    var no_buf_select: Int = 0
    var no_iopoll: Int = 0

    fun provide_buffers(ring: CPointer<io_uring>): Int {
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        var sqe: CPointer<io_uring_sqe>
        for (i in 0 until BUFFERS) {
            sqe = io_uring_get_sqe(ring)!!
            io_uring_prep_provide_buffers(sqe, vecs[i].iov_base,
                vecs[i].iov_len.toInt(), 1, 1, i)
        }

        var ret = io_uring_submit(ring)
        if (ret == BUFFERS) {
            for (i in 0 until BUFFERS) {
                ret = io_uring_wait_cqe(ring, cqe.ptr)
                if (cqe.pointed!!.res < 0) {
                    fprintf(stderr, "cqe.pointed.res=%d\n", cqe.pointed!!.res)
                    return 1
                }
                io_uring_cqe_seen(ring, cqe.value)
            }

            return 0
        }
        fprintf(stderr, "submit: %d\n", ret)
        return 1
    }

    fun __test_io(
        file: String, ring: CPointer<io_uring>, write1: Int, sqthread: Int, fixed1: Int, buf_select: Int,
    ): Int = memScoped {
        val __FUNCTION__ = "__test_io"

        val cqe: CPointerVar<io_uring_cqe> = alloc()
        var open_flags: Int
        val offset: off_tVar = alloc()
        var write = write1
        var fixed = fixed1
        m d "$__FUNCTION__ file: $file write: $write sqthread: $sqthread fixed: $fixed buf_select: $buf_select"
        if (buf_select.nz) {
            write = 0
            fixed = 0
        }
        if (buf_select.nz && provide_buffers(ring).nz)
            return 1

        if (write.nz)
            open_flags = O_WRONLY.also { m d "open  O_WRONLY" }
        else
            open_flags = O_RDONLY.also { m d "open  O_RDONLY" }
        open_flags += __O_DIRECT

        var ret: Int
        lateinit var fd:IntVar// = alloc<IntVar> { value = -1 }

        err@ for (__err in 0..0) {
            if (fixed.nz) {
                ret = t_register_buffers(ring, vecs, BUFFERS.toUInt()).toInt().also { m d "t_register_buffers " }
                when {
                    ret == T_SETUP_SKIP.toInt() -> return 0.also { m d "$__FUNCTION__  T_SETUP_SKIP.toInt()" }
                    ret != T_SETUP_OK.toInt() -> {
                        m d "$__FUNCTION__ did not get T_SETUP_OK "
                        fprintf(stderr, "buffer reg failed: %d\n", ret)
                        break@err
                    }
                    else-> m d "$__FUNCTION__ did get T_SETUP_OK "
                }
            } else m d "$__FUNCTION__ skipping fixed buffers"
            fd = alloc { value=open(file, open_flags).also { m d "file open $file $OPEN_FLAGS = ${it}" } }
            if (fd.value < 0) {
                perror("file open")
                break@err
            }
            if (sqthread.nz) {
                ret = io_uring_register_files(ring, fd.ptr, 1)
                if (ret.nz) {
                    fprintf(stderr, "file reg failed: %d\n", ret)
                    break@err
                }
            }else m d "$__FUNCTION__ skipping register files"

            offset.value = 0
            for (i in 0 until BUFFERS) {
                val sqe = io_uring_get_sqe(ring)!!

                offset.value = (BS * (rand() % BUFFERS)).toLong()
                var do_fixed: Int
                var use_fd: Int
                if (write.nz) {
                    do_fixed = fixed
                    use_fd = fd.value

                    if (sqthread.nz)
                        use_fd = 0
                    if (fixed.nz && (i and 1).nz)
                        do_fixed = 0
                    if (do_fixed.nz) {
                        io_uring_prep_write_fixed(sqe, use_fd, vecs[i].iov_base,
                            vecs[i].iov_len.toUInt(),
                            offset.value.toULong(), i)
                    } else {
                        io_uring_prep_writev(sqe, use_fd, vecs[i].ptr, 1,
                            offset.value.toULong())
                    }
                } else {
                    var do_fixed = fixed
                    var use_fd = fd.value

                    if (sqthread.nz)
                        use_fd = 0
                    if (fixed.nz && (i and 1).nz)
                        do_fixed = 0
                    if (do_fixed.nz) {
                        io_uring_prep_read_fixed(sqe, use_fd, vecs[i].iov_base,
                            vecs[i].iov_len.toUInt(),
                            offset.value.toULong(), i)
                    } else {
                        io_uring_prep_readv(sqe, use_fd, vecs[i].ptr, 1,
                            offset.value.toULong())
                    }

                }
                if (sqthread.nz)
                    sqe.pointed.flags = sqe.pointed.flags.or(IOSQE_FIXED_FILE.toUByte())
                if (buf_select.nz) {
                    sqe.pointed.flags = sqe.pointed.flags.or(IOSQE_BUFFER_SELECT.toUByte())
                    sqe.pointed.buf_group = buf_select.toUShort()
                    sqe.pointed.user_data = i.toULong()
                }
            }
            ret = io_uring_submit(ring)


            out@
            for (__out in 0..0) {
                for (i in 0 until BUFFERS) {
                    ret = io_uring_wait_cqe(ring, cqe.ptr)
                    if (ret.nz) {
                        fprintf(stderr, "wait_cqe=%d\n", ret)
                        break@err
                    } else {
                        val res = cqe.pointed!!.res
                        if (res == -EOPNOTSUPP) {
                            m d ("File/device/fs doesn't support polled IO\n")
                            no_iopoll = 1
                            break@out
                        } else if (res != BS) {
                            set_posix_errno(-res)
                            fprintf(stderr, "cqe res %d, wanted %d +- ${strerror(-res)!!.toKStringFromUtf8()}\n", res, BS)
                            break@err
                        }
                    }
                    io_uring_cqe_seen(ring, cqe.value)
                }

                if (fixed.nz) {
                    ret = io_uring_unregister_buffers(ring)
                    if (ret.nz) {
                        fprintf(stderr, "buffer unreg failed: %d\n", ret)
                        break@err
                    }
                }
                if (sqthread.nz) {
                    ret = io_uring_unregister_files(ring)
                    if (ret.nz) {
                        fprintf(stderr, "file unreg failed: %d\n", ret)
                        break@err
                    }
                }
            }
            close(fd.value)
            return 0

        }
        if (fd.value != -1)
            close(fd.value)
        return 1
    }

    //extern __io_uring_flush_sq:Int(ring:CPointer<io_uring>)

    /*
     * if we are polling io_uring_submit needs to always enter the
     * kernel to fetch events
     */
    fun test_io_uring_submit_enters(file: String): Int = memScoped {
        val __FUNCTION__ = "test_io_uring_submit_enters"
        val vp = vecs.getPointer(this)
        val ring: io_uring = alloc()
        m d "ring @ $__FUNCTION__"

        /* io_uring_for_each_cqe(ring.ptr, head, cqe)*/
        /* submit manually to avoid adding IORING_ENTER_GETEVENTS */
        if (no_iopoll.nz) {
            m d "ring @ $__FUNCTION__ no_iopoll"
            return 0
        }
        val ring_flags = IORING_SETUP_IOPOLL

        var ret = io_uring_queue_init(64, ring.ptr, ring_flags)

        if (ret.nz) {
            fprintf(stderr, "ring create failed: %d\n", ret)
            return 1
        } else       m d "ring @ $__FUNCTION__ created"


        val open_flags = O_WRONLY or __O_DIRECT

        val fd = open(file, open_flags)
        out@ for (_ignored in 0..0) {
            err@ for (__ignored in 0..0) {
                if (fd < 0) {
                    perror("file open")
                    break@err
                }
                m d "$__FUNCTION__ open"

                for (i in 0 until BUFFERS) {
                    val offset: off_t = (BS * (rand() % BUFFERS)).toLong()
                    val sqe: CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr)!!
                    io_uring_prep_writev(sqe, fd, vp[i].ptr, 1, offset.toULong())
                    sqe.pointed.user_data = 1UL
                }
                m d "$__FUNCTION__ BUFFERS"
                /* submit manually to avoid adding IORING_ENTER_GETEVENTS */
                val ioUringFlushSq = __io_uring_flush_sq(ring.ptr)
                ret = io_uring_enter(
                    ring.ring_fd, ioUringFlushSq.toUInt(), 0.toUInt(),
                    0.toUInt(), null
                )
                if (ret < 0) {
                    break@err
                }

                for (i in 0 until 500) {

                    ret = io_uring_submit(ring.ptr)
                    if (ret != 0) {
                        fprintf(stderr, "still had %d sqes to submit, this is unexpected", ret)
                        break@err
                    }

                    val cq: io_uring_cq = ring.cq
                    val head: UIntVar = alloc { value = cq.khead?.pointed?.value ?: 0U }
                    val cqe: CPointerVar<io_uring_cqe> = alloc()

                    m d "$__FUNCTION__ cval create $i"
                    val cqep1 = cValue<cqe_parms> {
                        this.ring = ring.ptr
                        this.head = head.value
                        this.cqe = cqe.value
                    }
                    m d "$__FUNCTION__ io_uring_do_for_each_cqe"

                    val res = io_uring_do_for_each_cqe(cqep1, vfunc)

                    m d "$__FUNCTION__ io_uring_do_for_each_cqe done $res"

                    if (res == -1) break@err else break@out
                }
            }
            if (fd != -1)
                close(fd)
            return 1

        }
        m d "success? @ $__FUNCTION__ = $ret"/* runs after test_io so should not have happened */// io_uring_for_each_cqe translated

        io_uring_queue_exit(ring.ptr)
        ret
    }

    fun test_io(file: String, write: Int, sqthread: Int, fixed: Int, buf_select: Int): Int {
        val __FUNCTION__ = "test_io"
        val ring: io_uring = alloc()
        m d "ring @ $__FUNCTION__"

        val ring_flags = IORING_SETUP_IOPOLL

        var ret: Int
        if (!no_iopoll.nz) {
            ret = t_create_ring(64, ring.ptr, ring_flags).toInt()
            return when {
                ret == T_SETUP_SKIP.toInt() -> 0.also { m d "$__FUNCTION__ T_SETUP_SKIP " }
                ret != T_SETUP_OK.toInt() -> {
                    fprintf(stderr, "ring create failed: %d\n", ret)
                    1.also { m d "$__FUNCTION__ T_SETUP_OK" }
                }
                else -> {
                    ret = __test_io(file, ring.ptr, write, sqthread, fixed, buf_select)
                    m d "$__FUNCTION__ __test_io $ret "
                    io_uring_queue_exit(ring.ptr)
                    m d "$__FUNCTION__ io_uring_queue_exit "
                    ret.also { m d "$__FUNCTION__ __test_io $it" }
                }
            }
        }
        return 0
    }

    fun probe_buf_select(): Int {
        val __FUNCTION__ = "probe_buf_select"

        val p: CPointerVar<io_uring_probe> = alloc()
        val ring: io_uring = alloc()
        m d "ring @ $__FUNCTION__"

        val ret = io_uring_queue_init(1, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "ring create failed: %d\n", ret)
            return 1
        }

        p.value = io_uring_get_probe_ring(ring.ptr)!!
        if (!io_uring_opcode_supported(p.value, IORING_OP_PROVIDE_BUFFERS.toInt()).nz) {
            no_buf_select = 1
            fprintf(stdout, "Buffer select not supported, skipping\n")
            return 0
        }
        io_uring_free_probe(p.value)
        return 0
    }

    fun main(argv: Array<String>): Int {
        val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"

        if (probe_buf_select().nz)
            exit(1)

        val fname: String = argv.firstOrNull() ?: let {
            srand(time(null).toUInt())
            ".basic-rw-${rand()}-${getpid().toUInt()}"
        }
        t_create_file(fname, FILE_SIZE.toULong()).also { m d "t_create_file($fname, $FILE_SIZE.toULong())" }

        vecs = t_create_buffers(BUFFERS.toULong(), BS.toULong()).reinterpret()
        var ret: Int
        var nr = 16
        err@ for (___err in 0..0) {
            if (no_buf_select.nz)
                nr = 8
            for (i in 0 until nr) {
                val write: Int = (i and 1).nz.bool
                val sqthread: Int = (i and 2).nz.bool
                val fixed: Int = (i and 4).nz.bool
                val buf_select: Int = (i and 8).nz.bool

                ret = test_io(fname, write, sqthread, fixed, buf_select)
                if (ret.nz) {
                    fprintf(stderr, "test_io failed %d/%d/%d/%d\n",
                        write, sqthread, fixed, buf_select)
                    break@err
                }
                if (no_iopoll.nz)
                    break
            }

            ret = test_io_uring_submit_enters(fname)
            m d "back from test_io_uring_submit_enters"
            if (ret.nz) {
                fprintf(stderr, "test_io_uring_submit_enters failed\n")
                break@err
            }

            if (fname != argv.firstOrNull())
                unlink(fname).also {
                    m d "$fname unlinked"
                }
            exit(0)

        }
        if (fname != argv.firstOrNull())
            unlink(fname).also {
                m d "$fname unlinked"
            }
        exit(1)
        return 0
    }

    companion object {
        /*
         * Sync internal state with kernel ring state on the SQ side. Returns the
         * number of pending items in the SQ ring, for the shared ring.
         */
        fun __io_uring_flush_sq(ring: CPointer<io_uring>): UInt {
            val __FUNCTION__ = "__io_uring_flush_sq"

            val sq: CPointer<io_uring_sq> = ring.pointed.sq.ptr
            val mask: UInt = sq.pointed.kring_mask!!.pointed.value
            var ktail: UInt = sq.pointed.ktail!!.pointed.value
            var to_submit: UInt = sq.pointed.sqe_tail - sq.pointed.sqe_head

            out@ for (__out in 0..0) {
                if (!to_submit.nz)
                    break@out

                /*
                 * Fill in sqes that we have queued up, adding them to the kernel ring
                 */
                do {
                    sq.pointed.array!![(ktail and mask).toInt()] = sq.pointed.sqe_head and mask
                    ktail++
                    sq.pointed.sqe_head++
                } while ((--to_submit).nz)


                write_barrier()
                /*
                 * Ensure that the kernel sees the SQE updates before it sees the tail
                 * update.
                 */
                sq.pointed.ktail!!.pointed.value = ktail.toUInt()
                //io_uring_smp_store_release(sq.pointed.ktail, ktail);
                write_barrier()
            }
            /*
             * This _may_ look problematic, as we're not supposed to be reading
             * SQ.pointed.head without acquire semantics. When we're in SQPOLL mode, the
             * kernel submitter could be updating this right now. For non-SQPOLL,
             * task itself does it, and there's no potential race. But even for
             * SQPOLL, the load is going to be potentially out-of-date the very
             * instant it's done, regardless or whether or not it's done
             * atomically. Worst case, we're going to be over-estimating what
             * we can submit. The point is, we need to be able to deal with this
             * situation regardless of any perceived atomicity.
             */
            return (ktail - sq.pointed.khead!!.pointed.value)
        }

        const val FILE_SIZE = (128 * 1024)
        const val BS = 4096
        const val BUFFERS = (FILE_SIZE / BS)
    }
}

val vfunc = staticCFunction<CValue<cqe_parms>, Int> {
    it.useContents {
        if (cqe!!.pointed.res == -EOPNOTSUPP) {
            fprintf(stdout, "File/device/fs doesn't support polled IO\n")
            -1
        } else 1
    }
}

fun main(args: Array<String>) {
    val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"
    exit(IoPoll().main(args))
}