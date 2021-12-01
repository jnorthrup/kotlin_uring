package test.register

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.fromOctal
import linux_uring.include.t_calloc
import linux_uring.include.t_malloc
import simple.CZero.nz

/* SPDX-License-Identifier: MIT */
/*
 * Description: run various file registration tests
 *
 */
//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <sys/resource.h>

//include "helpers.h"
//include "liburing.h"
class FileRegister : NativeFreeablePlacement by nativeHeap {
    var no_update: Int = 0

    fun close_files(files: IntArray?, nr_files: Int, add: Int,funcName:String) {

        val fname: ByteArray = ByteArray(256)

        for (i in 0 until nr_files) {
            files?.let {
                if(i < files.size)
                close(files[i])
            }
            if (!add.nz)
                sprintf(fname.refTo(0), ".reg.$funcName.%d", i)
            else
                sprintf(fname.refTo(0), ".add.$funcName.%d", i + add)
            unlink(fname.toKString())
        }
    }

    fun  open_files(nr_files: Int, extra: Int, add: Int,funcName: String): IntArray {
        val __FUNCTION__ = "open_files"

        val fname = ByteArray(256)
        val files = IntArray(nr_files + extra) { -1 }

        for (i in 0 until nr_files) {
            if (!add.nz)
                sprintf(fname.refTo(0), ".reg.$funcName.%d", i)
            else
                sprintf(fname.refTo(0), ".add.$funcName.%d", i + add)
            files[i] = open(fname.toKString(), O_RDWR or O_CREAT, 644.fromOctal())
            if (files[i] < 0) {
                perror("open")
                break
            }
        }
        return files
    }

    fun test_shrink(ring: CPointer<io_uring>): Int = memScoped {
        val __FUNCTION__ = "test_shrink"


        val files = open_files(50, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 50)
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            var off = 0U
            do {
                val fd: IntVar = alloc { value = -1 }
                ret = io_uring_register_files_update(ring, off, fd.ptr, 1)
                if (ret != 1) {
                    if (off == 50u && ret == -EINVAL)
                        break
                    fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                    break
                }
                off++
            } while (1.nz)

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 50, 0,__FUNCTION__)
            return 0
        } while (false)
        close_files(files, 50, 0,__FUNCTION__)
        return 1
    }


    fun test_grow(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_grow"


        val files = open_files(50, 250, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 300)
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            var off: UInt = 50u

            var fds: IntArray
            do {
                fds = open_files(1, 0, off.toInt(),__FUNCTION__)
                ret = io_uring_register_files_update(ring, off.toUInt(), fds.refTo(0), 1)
                if (ret != 1) {
                    if (300u == off && ret == -EINVAL)
                        break
                    fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                    break
                }
                if (off >= 300u) {
                    fprintf(stderr, "%s: Succeeded beyond end-of-list?\n", __FUNCTION__)
                    break@err
                }
                off++
            } while (1.nz)

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0,__FUNCTION__)
            close_files(null, 251, 50,__FUNCTION__)
            return 0
        } while (false)

        close_files(files, 100, 0,__FUNCTION__)
        close_files(null, 251, 50,__FUNCTION__)
        return 1
    }

    fun test_replace_all(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_replace_all"


        val files = open_files(100, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 100)
        var fds: CPointer<IntVarOf<Int>>? = null
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            fds = t_calloc(100.toULong(), sizeOf<ULongVar>().toULong()).reinterpret<IntVar>()
            for (i in 0 until 100)
                fds[i] = -1

            ret = io_uring_register_files_update(ring, 0, fds, 100)
            if (ret != 100) {
                fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0,__FUNCTION__)
            fds.let(::free)
            return 0
        } while (false)

        close_files(files, 100, 0,__FUNCTION__)
        fds?.let(::free)
        return 1
    }

    fun test_replace(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_replace"

        val files = open_files(100, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 100)
        var fds: IntArray? = null

        err@ do {

            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            fds = open_files(10, 0, 1,__FUNCTION__)
            ret = io_uring_register_files_update(ring, 90, fds.refTo(0), 10)
            if (ret != 10) {
                fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0,__FUNCTION__)

            fds.let {
                close_files(fds, 10, 1,__FUNCTION__)
            }
            return 0

        } while (false)
        close_files(files, 100, 0,__FUNCTION__)
        fds.let {
            close_files(fds, 10, 1,__FUNCTION__)
        }
        return 1
    }

    fun test_removals(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_removals"


        val files = open_files(100, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 100)

        var fds: CPointer<IntVarOf<Int>>? = null
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            fds = t_calloc(10.toULong(), sizeOf<IntVar>().toULong()).reinterpret<IntVar>()
            for (i in 0 until 10)
                fds[i] = -1

            ret = io_uring_register_files_update(ring, 50, fds.reinterpret(), 10)
            if (ret != 10) {
                fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0,__FUNCTION__)
            fds.let(this::free)
            return 0
        } while (false)

        close_files(files, 100, 0,__FUNCTION__)
        fds?.let(this::free)
        return 1
    }

    fun test_additions(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_additions"

        val files = open_files(100, 100, 0,__FUNCTION__)
        var fds: IntArray? = null
        err@ do {
            var ret: Int = io_uring_register_files(ring, files.refTo(0), 200)
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            fds = open_files(2, 0, 1,__FUNCTION__)
            ret = io_uring_register_files_update(ring, 100, fds.refTo(0), 2)
            if (ret != 2) {
                fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0,__FUNCTION__)
            if (fds != null) {

                close_files(fds, 2, 1,__FUNCTION__)
            }
            return 0
        } while (false)
        close_files(files, 100, 0,__FUNCTION__)
        if (fds != null) {

            close_files(fds, 2, 1,__FUNCTION__)
        }
        return 1
    }

    fun test_sparse(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_sparse"

        val files = open_files(100, 100, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 200)
        err@ do {
            done@ do {
                if (ret.nz) {
                    if (ret == -EBADF) {
                        fprintf(stdout, "Sparse files not supported\n")
                        no_update = 1
                        break@done
                    }
                    fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                    break@err
                }
                ret = io_uring_unregister_files(ring)
                if (ret.nz) {
                    fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                    break@err
                }
            } while (false)
            close_files(files, 100, 0,__FUNCTION__)
            return 0
        } while (false)
        close_files(files, 100, 0,__FUNCTION__)
        return 1
    }

    fun test_basic_many(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_basic_many"

        val files = open_files(768, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 768)
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register %d\n", __FUNCTION__, ret)
                break@err
            }
            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister %d\n", __FUNCTION__, ret)
                break@err
            }
            close_files(files, 768, 0,__FUNCTION__)
            return 0
        } while (false)
        close_files(files, 768, 0,__FUNCTION__)
        return 1
    }

    fun test_basic(ring: CPointer<io_uring>, fail: Int): Int {
        val __FUNCTION__ = "test_basic"
        val nr_files: Int = if(fail.nz)10 else 100
        val files = open_files(nr_files, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 100)
        err@ do {
            if (ret.nz) {
                if (fail.nz) {
                    if (ret == -EBADF || ret == -EFAULT) {
                        close_files(files, nr_files, 0, __FUNCTION__)
                        return 0
                    }
                }
                fprintf(stderr, "%s: register %d\n", __FUNCTION__, ret)
                break@err
            }
            if (fail.nz) {
                fprintf(stderr, "Registration succeeded, but expected fail\n")
                break@err
            }
            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister %d\n", __FUNCTION__, ret)
                break@err
            }
            close_files(files, nr_files, 0,__FUNCTION__)
            return 0
        } while (false)

        close_files(files, nr_files, 0,__FUNCTION__)
        return 1
    }

    /*
     * Register 0 files, but reserve space for 10.  Then add one file.
     */
    fun test_zero(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_zero"


        val files = open_files(0, 10, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 10)
        var fds: IntArray? = null
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            fds = open_files(1, 0, 1,__FUNCTION__)
            ret = io_uring_register_files_update(ring, 0, fds.refTo(0), 1)
            if (ret != 1) {
                fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }
            close_files(fds, 1, 1,__FUNCTION__)
            return 0

        } while (false)

        close_files(fds, 1, 1,__FUNCTION__)
        return 1
    }

    fun test_fixed_read_write(ring: CPointer<io_uring>, index: Int): Int {
        val __FUNCTION__ = "test_fixed_read_write"
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val iov = allocArray<iovec>(2)


        iov[0].iov_base = t_malloc(4096u)
        iov[0].iov_len = 4096u
        memset(iov[0].iov_base, 0x5a, 4096)

        iov[1].iov_base = t_malloc(4096u)
        iov[1].iov_len = 4096u

        var sqe = io_uring_get_sqe(ring)!!
        io_uring_prep_writev(sqe, index, iov[0].ptr, 1, 0)
        run {
            val pointed = sqe.pointed
            pointed.flags = pointed.flags.or(IOSQE_FIXED_FILE.toUByte())
            pointed.user_data = 1u

        }
        var ret = io_uring_submit(ring)
        if (ret != 1) {
            fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret)
            return 1
        }

        ret = io_uring_wait_cqe(ring, cqe.ptr)
        if (ret < 0) {
            fprintf(stderr, "%s: io_uring_wait_cqe=%d\n", __FUNCTION__, ret)
            return 1
        }
        val pointed1 = cqe.pointed!!
        if (pointed1.res != 4096) {
            fprintf(stderr, "%s: write cqe.pointed.res=%d\n", __FUNCTION__, pointed1.res)
            return 1
        }
        io_uring_cqe_seen(ring, cqe.value)

        sqe = io_uring_get_sqe(ring)!!
        io_uring_prep_readv(sqe, index, iov[1].ptr, 1, 0)
        val pointed = sqe.pointed
        pointed.flags = pointed.flags.or(IOSQE_FIXED_FILE.toUByte())
        pointed.user_data = 2u

        ret = io_uring_submit(ring)
        if (ret != 1) {
            fprintf(stderr, "%s: got %d, wanted 1\n", __FUNCTION__, ret)
            return 1
        }

        ret = io_uring_wait_cqe(ring, cqe.ptr)
        if (ret < 0) {
            fprintf(stderr, "%s: io_uring_wait_cqe=%d\n", __FUNCTION__, ret)
            return 1
        }
        if (pointed1.res != 4096) {
            fprintf(stderr, "%s: read cqe.pointed.res=%d\n", __FUNCTION__, pointed1.res)
            return 1
        }
        io_uring_cqe_seen(ring, cqe.value)

        if (memcmp(iov[1].iov_base!! as CValuesRef<*>, iov[0].iov_base!! as CValuesRef<*>, 4096uL).nz) {
            fprintf(stderr, "%s: data mismatch\n", __FUNCTION__)
            return 1
        }

        free(iov[0].iov_base)
        free(iov[1].iov_base)
        return 0
    }

    fun adjust_nfiles(want_files: Int): Unit {
        val __FUNCTION__ = "adjust_nfiles"

        val rlim: rlimit = alloc()

        if (getrlimit(RLIMIT_NOFILE, rlim.ptr) < 0)
            return
        if (rlim.rlim_cur >= want_files.toUInt())
            return
        rlim.rlim_cur = want_files.toULong()

        setrlimit(RLIMIT_NOFILE, rlim.ptr)
    }

    /*
     * Register 8K of sparse files, update one at a random spot, then do some
     * file IO to verify it works.
     */
    fun test_huge(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_huge"

        adjust_nfiles(16384)

        val files: IntArray = open_files(0, 8192, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 8192)
        err@ do {
            out@ do {
                if (ret.nz) {
                    /* huge sets not supported */
                    if (ret == -EMFILE) {
                        fprintf(stdout, "%s: No huge file set support, skipping\n", __FUNCTION__)
                        break@out
                    }
                    fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                    break@err
                }

                files[7193] = open(".reg.7193", O_RDWR or O_CREAT, 644.fromOctal())
                if (files[7193] < 0) {
                    fprintf(stderr, "%s: open=%d\n", __FUNCTION__, errno)
                    break@err
                }

                val files1: IntVar = alloc { value = files[7193] }
                ret = io_uring_register_files_update(ring, 7193, files1.ptr, 1)
                if (ret != 1) {
                    fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                    break@err
                }

                if (test_fixed_read_write(ring, 7193).nz)
                    break@err

                ret = io_uring_unregister_files(ring)
                if (ret.nz) {
                    fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                    break@err
                }

                if (files[7193] != -1) {
                    close(files[7193])
                    unlink(".reg.7193")
                }
            } while (false)
            return 0
        } while (false)

        if (files[7193] != -1) {
            close(files[7193])
            unlink(".reg.7193")
        }
        return 1
    }

    fun test_skip(ring: CPointer<io_uring>): Int = memScoped {
        val __FUNCTION__ = "test_skip"


        val files = open_files(100, 0, 0,__FUNCTION__)
        var ret = io_uring_register_files(ring, files.refTo(0), 100)
        err@ do {
            done@ do {
                if (ret.nz) {
                    fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                    break@err
                }




                files[90] = IORING_REGISTER_FILES_SKIP

                ret = io_uring_register_files_update(ring, 90, files.refTo(90), 1)
                if (ret != 1) {
                    if (ret == -EBADF) {
                        fprintf(stdout, "Skipping files not supported\n")
                        break@done
                    }
                    fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
                    break@err
                }

                /* verify can still use file index 90 */
                if (test_fixed_read_write(ring, 90).nz)
                    break@err

                ret = io_uring_unregister_files(ring)
                if (ret.nz) {
                    fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                    break@err
                }

            } while (false)
            close_files(files, 100, 0,__FUNCTION__)
            return 0
        } while (false)
        close_files(files, 100, 0,__FUNCTION__)
        return 1
    }

    fun test_sparse_updates(): Int {
        val __FUNCTION__ = "test_sparse_updates"

        val ring: io_uring = alloc()

        var ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "queue_init: %d\n", ret)
            return ret
        }

        val fds = IntArray(256) { -1 }
        ret = io_uring_register_files(ring.ptr, fds.refTo(0), 256)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }

        val newfd: IntVar = alloc { value = 1 }
        for (i: Int in 0 until 256) {
            ret = io_uring_register_files_update(ring.ptr, i.toUInt(), newfd.ptr, 1)
            if (ret != 1) {
                fprintf(stderr, "file_update: %d\n", ret)
                return ret
            }
        }
        io_uring_unregister_files(ring.ptr)

        for (i in 0 until 256)
            fds[i] = 1

        ret = io_uring_register_files(ring.ptr, fds.refTo(0), 256)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }

        newfd.value = -1
        for (i in 0 until 256) {
            ret = io_uring_register_files_update(ring.ptr, i.toUInt(), newfd.ptr, 1)
            if (ret != 1) {
                fprintf(stderr, "file_update: %d\n", ret)
                return ret
            }
        }
        io_uring_unregister_files(ring.ptr)

        io_uring_queue_exit(ring.ptr)
        return 0
    }

    fun test_fixed_removal_ordering(): Int {
        val __FUNCTION__ = "test_fixed_removal_ordering"
        val ring = alloc<io_uring>()
        val buffer = ByteArray(128)
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val ts: __kernel_timespec = alloc()
        val fds = IntArray(2)

        var ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret < 0) {
            fprintf(stderr, "failed to init io_uring: %s\n", strerror(-ret))
            return ret
        }
        if (pipe(fds.refTo(0)).nz) {
            perror("pipe")
            return -1
        }
        ret = io_uring_register_files(ring.ptr, fds.refTo(0), 2)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }
        /* ring should have fds referenced, can close them */
        close(fds[0])
        close(fds[1])

        var sqe = io_uring_get_sqe(ring.ptr)!!
        /* outwait file recycling delay */
        ts.tv_sec = 3
        ts.tv_nsec = 0
        io_uring_prep_timeout(sqe, ts.ptr, 0, 0)
        sqe.pointed.flags = sqe.pointed.flags.or((IOSQE_IO_LINK or IOSQE_IO_HARDLINK).toUByte())
        sqe.pointed.user_data = 1u

        sqe = io_uring_get_sqe(ring.ptr)!!

        io_uring_prep_write(sqe, 1, buffer.refTo(0), (buffer).size.toUInt(), 0)
        sqe.pointed.flags = sqe.pointed.flags.or(IOSQE_FIXED_FILE.toUByte())
        sqe.pointed.user_data = 2u

        ret = io_uring_submit(ring.ptr)
        if (ret != 2) {
            fprintf(stderr, "%s: got %d, wanted 2\n", __FUNCTION__, ret)
            return -1
        }

        /* remove unused pipe end */
        val fd = alloc<IntVar> { value = -1 }
        ret = io_uring_register_files_update(ring.ptr, 0, fd.ptr, 1)
        if (ret != 1) {
            fprintf(stderr, "update off=0 failed\n")
            return -1
        }

        /* remove used pipe end */
        fd.value = -1
        ret = io_uring_register_files_update(ring.ptr, 1, fd.ptr, 1)
        if (ret != 1) {
            fprintf(stderr, "update off=1 failed\n")
            return -1
        }

        for (i in 0 until 2) {
            ret = io_uring_wait_cqe(ring.ptr, cqe.ptr)
            if (ret < 0) {
                fprintf(stderr, "%s: io_uring_wait_cqe=%d\n", __FUNCTION__, ret)
                return 1
            }
            io_uring_cqe_seen(ring.ptr, cqe.value)
        }

        io_uring_queue_exit(ring.ptr)
        return 0
    }


    fun main(argc: Int): Int {
        val ring: io_uring = alloc()
        var ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret.nz) {
            printf("ring setup failed\n")
            return 1
        }

        ret = test_basic(ring.ptr, 0)
        if (ret.nz) {
            printf("test_basic failed\n")
            return ret
        }

        ret = test_basic(ring.ptr, 1)
        if (ret.nz) {
            printf("test_basic failed\n")
            return ret
        }

        ret = test_basic_many(ring.ptr)
        if (ret.nz) {
            printf("test_basic_many failed\n")
            return ret
        }

        ret = test_sparse(ring.ptr)
        if (ret.nz) {
            printf("test_sparse failed\n")
            return ret
        }

        if (no_update.nz)
            return 0

        ret = test_additions(ring.ptr)
        if (ret.nz) {
            printf("test_additions failed\n")
            return ret
        }

        ret = test_removals(ring.ptr)
        if (ret.nz) {
            printf("test_removals failed\n")
            return ret
        }

        ret = test_replace(ring.ptr)
        if (ret.nz) {
            printf("test_replace failed\n")
            return ret
        }

        ret = test_replace_all(ring.ptr)
        if (ret.nz) {
            printf("test_replace_all failed\n")
            return ret
        }

        ret = test_grow(ring.ptr)
        if (ret.nz) {
            printf("test_grow failed\n")
            return ret
        }

        ret = test_shrink(ring.ptr)
        if (ret.nz) {
            printf("test_shrink failed\n")
            return ret
        }

        ret = test_zero(ring.ptr)
        if (ret.nz) {
            printf("test_zero failed\n")
            return ret
        }

        ret = test_huge(ring.ptr)
        if (ret.nz) {
            printf("test_huge failed\n")
            return ret
        }

        ret = test_skip(ring.ptr)
        if (ret.nz) {
            printf("test_skip failed\n")
            return 1
        }

        ret = test_sparse_updates()
        if (ret.nz) {
            printf("test_sparse_updates failed\n")
            return ret
        }

        ret = test_fixed_removal_ordering()
        if (ret.nz) {
            printf("test_fixed_removal_ordering failed\n")
            return 1
        }
        return 0
    }
}


fun main(args: Array<String>) = memScoped {
    val __status = FileRegister().main(args.size)
    exit(__status )
}