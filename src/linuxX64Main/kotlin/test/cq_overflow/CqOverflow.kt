package test.cq_overflow

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.t_create_buffers
import linux_uring.include.t_create_file
import simple.CZero.nz

/* SPDX-License-Identifier: MIT */

//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include "helpers.h"
//include "liburing.h"/*
// * Description: run various CQ ring overflow tests
// *
// */
class CqOverflow : NativeFreeablePlacement by nativeHeap {

    companion object {
        const val FILE_SIZE = (256 * 1024)
        const val BS = 4096
        const val BUFFERS = (FILE_SIZE / BS)
        const val ENTRIES = 8
    }

    lateinit var vecs: CArrayPointer<iovec>


    fun test_io(file: String, usecs: ULong, drops: CPointer<UIntVar>, fault: Int): Int = memScoped {
        val __FUNCTION__ = "test_io"
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val p: io_uring_params = alloc()
        var reaped: UInt
        var total: UInt
        val ring: io_uring = alloc()
        var nodrop: Int = -1
        var fd = -1
        var ret = -1
        val drops: IntVar = alloc()

        err@ for (__err in 0..0) {
            fd = open(file, O_RDONLY or __O_DIRECT)
            if (fd < 0) {
                perror("file open")
                break@err
            }

//            bzero(p.ptr, sizeOf<io_uring_params>())
            ret = io_uring_queue_init_params(ENTRIES.toUInt(), ring.ptr, p.ptr)
            if (ret.nz) {
                fprintf(stderr, "ring create failed: %d\n", ret)
                break@err
            }
            nodrop = 0
            if ((p.features and IORING_FEAT_NODROP).nz)
                nodrop = 1

            total = 0.toUInt()
            for (i in 0 until BUFFERS / 2) {
                val offset = alloc<off_tVar> { value = (BS * (rand() % BUFFERS)).toLong() }
                val sqe = io_uring_get_sqe(ring.ptr)!!

                if (fault.nz && i == ENTRIES + 4)
                    vecs[i].iov_base = null
                io_uring_prep_readv(sqe, fd, vecs[i].ptr, 1, offset.value.toULong())


                ret = io_uring_submit(ring.ptr)
                if (nodrop.nz && ret == -EBUSY) {
                    drops.value = 1
                    total = i.toUInt()
                    break
                } else if (ret != 1) {
                    fprintf(stderr, "submit got %d, wanted %d\n", ret, 1)
                    total = i.toUInt()
                    break
                }
                total++
            }

            reap_it@ for (__reap in 0..0) {
                if (drops.value.nz)
                    break@reap_it

                usleep(usecs.toUInt())

                for (i in total until BUFFERS.toUInt()) {


                    val sqe = io_uring_get_sqe(ring.ptr)!!


                    val offset = BS * (rand() % BUFFERS)

                    io_uring_prep_readv(sqe, fd, vecs[i.toInt()].ptr, 1, offset.toULong())

                    ret = io_uring_submit(ring.ptr)
                    if (nodrop.nz && ret == -EBUSY) {
                        drops.value = 1
                        break
                    } else if (ret != 1) {
                        fprintf(stderr, "submit got %d, wanted %d\n", ret, 1)
                        break
                    }
                    total++
                }

//                reap_it:
            }
            reaped = 0u

            do {
                if (nodrop.nz) {
                    /* nodrop should never lose events */
                    if (reaped == total)
                        break
                } else {
                    if (reaped + ring.cq.koverflow!!.pointed.value == total)
                        break
                }
                ret = io_uring_wait_cqe(ring.ptr, cqe.ptr)
                if (ret.nz) {
                    fprintf(stderr, "wait_cqe=%d\n", ret)
                    break@err
                }
                if (cqe.pointed!!.res != BS) {
                    if (!(fault.nz && cqe.pointed!!.res == -EFAULT)) {
                        fprintf(stderr, "cqe res %d, wanted %d\n",
                            cqe.pointed!!.res, BS)
                        break@err
                    }
                }
                io_uring_cqe_seen(ring.ptr, cqe.value)
                reaped++
            } while (true)

            if (!io_uring_peek_cqe(ring.ptr, cqe.ptr).nz) {
                fprintf(stderr, "found unexpected completion\n")
                break@err
            }

            if (!nodrop.nz) {
                 drops.value = ring.cq.koverflow!!.pointed.value.toInt()
            } else if (  ring.cq.koverflow!!.pointed.value.nz ) {
                fprintf(stderr, "Found %u overflows\n",  ring.cq.koverflow!!.pointed.value.nz)
                break@err
            }

            io_uring_queue_exit(ring.ptr)
            close(fd)
            return 0
        }
        //err:
        if (fd != -1)
            close(fd)
        io_uring_queue_exit(ring.ptr)
        return 1
    }

    fun reap_events(ring: CPointer<io_uring>, nr_events: UInt, do_wait: Int): Int {
        val __FUNCTION__ = "reap_events"

        val cqe:CPointerVar<io_uring_cqe> = alloc()
//        i:Int, ret = 0, seq = 0
        var i:Int=0
        var ret: Int=0
        val seq=alloc<IntVar>()
        for (i1 in 0 until nr_events.toInt()) {
            i=i1
            if (do_wait.nz)
                ret = io_uring_wait_cqe(ring, cqe.ptr)
            else
                ret = io_uring_peek_cqe(ring, cqe.ptr)
            if (ret.nz) {
                if (ret != -EAGAIN)
                    fprintf(stderr, "cqe peek failed: %d\n", ret)
                break
            }
            if (cqe.pointed!!.user_data!= seq.value.toULong()) {
                fprintf(stderr, "cqe sequence out-of-order\n")
                fprintf(stderr, "got %d, wanted %d\n", cqe.pointed!!.user_data,
                    seq.value)
                return -EINVAL
            }
            seq.value++
            io_uring_cqe_seen(ring, cqe.value)
        }

        return if(i.nz)  i else ret
    }

    /*
     * Submit some NOPs and watch if the overflow is correct
     */
    fun test_overflow(): Int {
        val __FUNCTION__ = "test_overflow"

      val ring:io_uring=alloc()
      val p:io_uring_params =alloc()
      var sqe:CPointer<io_uring_sqe>
      val pending:UIntVar=alloc()
//      var ret:Int, i, j

        var ret = io_uring_queue_init_params(4, ring.ptr, p.ptr)
        if (ret.nz) {
            fprintf(stderr, "io_uring_queue_init failed %d\n", ret)
            return 1
        }

        /* submit 4x4 SQEs, should overflow the ring by 8 */
        pending.value = 0u
        err@ for (__err in 0..0) {
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    sqe = io_uring_get_sqe(ring.ptr) !!


                    io_uring_prep_nop(sqe)
                    sqe.pointed.user_data = ((i * 4) + j).toULong()
                }

                ret = io_uring_submit(ring.ptr)
                if (ret == 4) {
                    pending.value += 4u
                    continue
                }
                if ((p.features   and IORING_FEAT_NODROP).nz){
                    if (ret == -EBUSY)
                        break
                }
                fprintf(stderr, "sqe submit failed: %d\n", ret)
                break@err
            }

            /* we should now have 8 completions ready */
            ret = reap_events(ring.ptr, pending.value, 0)
            if (ret < 0)
                break@err

            if (!(p.features and IORING_FEAT_NODROP).nz) {
                if (  ring.cq.koverflow!!.pointed.value != 8.toUInt()) {
                    fprintf(stderr, "cq ring overflow %d, expected 8\n",
                        ring.cq.koverflow!!.pointed.value)
                    break@err
                }
            }
            io_uring_queue_exit(ring.ptr)
            return 0
//            err:
        }
        io_uring_queue_exit(ring.ptr)
        return 1
    }

    fun main(argc: Int, argv: Array<String>): Int {
        val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"

      val fname:String = ".cq-overflow"
      var iters:UInt
      val drops:UIntVar=alloc()
      var usecs:ULong
      val ret:Int

        if (argc > 1)
            return 0

        ret = test_overflow()
        if (ret.nz) {
            printf("test_overflow failed\n")
            return ret
        }

        t_create_file(fname, FILE_SIZE.toULong())

        vecs = t_create_buffers(BUFFERS.toULong(), BS.toULong()) .reinterpret()

        iters = 0u
        usecs = 1000.toULong()
        err@for(__err in 0..0){
            do {
                drops.value = 0u

                if (test_io(fname, usecs, drops.ptr, 0).nz) {
                    fprintf(stderr, "test_io nofault failed\n")
                    break@err
                }
                if (drops.value.nz)
                    break
                usecs = (usecs * 12.toUInt()) / 10.toUInt()
                iters++
            } while (iters < 40.toUInt())

            if (test_io(fname, usecs, drops.ptr, 0).nz) {
                fprintf(stderr, "test_io nofault failed\n")
                break@err
            }

            if (test_io(fname, usecs, drops.ptr, 1).nz) {
                fprintf(stderr, "test_io fault failed\n")
                break@err
            }

            unlink(fname)
            return 0
        }//err:
        unlink(fname)
        return 1
    }
}

fun main(args: Array<String>) {
    exit(CqOverflow().main(args.size,args))
}