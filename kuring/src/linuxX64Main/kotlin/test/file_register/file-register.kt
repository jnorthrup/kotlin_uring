package test.file_register

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.fromOctal
import linux_uring.include.t_calloc
import linux_uring.include.t_malloc
import platform.posix.NULL
import simple.CZero.nz

/* SPDX-License-Identifier: MIT */
/*
 * Description: run various file registration tests
 *
 */
//#include <errno.h>
//#include <stdio.h>
//#include <unistd.h>
//#include <stdlib.h>
//#include <string.h>
//#include <fcntl.h>
//#include <sys/resource.h>
//
//#include "helpers.h"
//#include "liburing.h"
class FileRegister : NativeFreeablePlacement by nativeHeap {
    var no_update: Int = 0

    fun close_files(files: CPointer<IntVar>?, nr_files: Int, add: Int) {
        val __FUNCTION__ = "close_files"

        val fname = ByteArray(32)
        for (i in 0 until nr_files) {
            (files)?.let {
                close(files[i])
            }
            val ref = fname.refTo(0)
            if (!add.nz) {
                sprintf(ref, ".reg.%d", i)
            } else
                sprintf(ref, ".add.%d", i + add)
            unlink(fname.toKString())
        }
        files?.let {
            free(files)
        }
    }

    fun open_files(nr_files: Int, extra: Int, add: Int): CPointer<IntVar> {
        val __FUNCTION__ = "open_files"

        val fname = ByteArray(32)
        val files: CPointer<IntVarOf<Int>> =
            t_calloc((nr_files.toULong() + extra.toULong()).toULong(), Int.SIZE_BYTES.toULong()).reinterpret<IntVar>()

        val __s = fname.refTo(0)
        for (i in 0 until nr_files) {
            if (!add.nz) {
                sprintf(__s, ".reg.%d", i)
            } else
                sprintf(__s, ".add.%d", i + add)
            files[i] = open(fname.toKString(), O_RDWR or O_CREAT, 644.fromOctal())
            if (files[i] < 0) {
                perror("open")
                free(files)

                break
            }
        }
        if (extra.nz) {
            for (i in nr_files until nr_files + extra)
                files[i] = -1
        }

        return files
    }

    fun test_shrink(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_shrink"




        val files = open_files(50, 0, 0)
        var ret = io_uring_register_files(ring, files, 50)
        err@ do {
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            var off = 0U
            do {
                val fd  :IntVar=alloc { value=-1 }
                ret = io_uring_register_files_update(ring, off, fd.ptr, 1)
                if (ret != 1) {
                    if (50u == off && ret == -EINVAL)
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

            close_files(files, 50, 0)
            return 0
        } while (false )
        close_files(files, 50, 0)
        return 1
    }


    fun test_grow(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_grow"


        val files = open_files(50, 250, 0)
        var ret = io_uring_register_files(ring, files, 300)
        err@
        for (goto____ in 1..1){
            if (ret.nz) {
                fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
                break@err
            }
            var off = 50u

            while(true) {
                val fds = open_files(1, 0, off.toInt())
                ret = io_uring_register_files_update(ring, off, fds, 1)
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
            }

            ret = io_uring_unregister_files(ring)
            if (ret.nz) {
                fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
                break@err
            }

            close_files(files, 100, 0)
            close_files(null, 251, 50)
            return 0
        }
        close_files(files, 100, 0)
        close_files(null, 251, 50)
        return 1
    }

    fun test_replace_all(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_replace_all"
 

      val files = open_files(100, 0, 0)
      var ret = io_uring_register_files(ring, files, 100)
     err@do{
         if (ret.nz) {
             fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
             break@err
         }

         val fds = t_malloc(sizeof:CPointer<100>(int))
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

         close_files(files, 100, 0)
         if (fds)
             free(fds)
         return 0
        
     }while(false)
        close_files(files, 100, 0)
        if (fds)
            free(fds)
        return 1
    }

    fun test_replace(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_replace"

        files:CPointer<IntVar>, *fds = NULL
        ret:Int

        files = open_files(100, 0, 0)
        ret = io_uring_register_files(ring, files, 100)
        if (ret.nz) {
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        fds = open_files(10, 0, 1)
        ret = io_uring_register_files_update(ring, 90, fds, 10)
        if (ret != 10) {
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        close_files(files, 100, 0)
        if (fds)
            close_files(fds, 10, 1)
        return 0
        err:
        close_files(files, 100, 0)
        if (fds)
            close_files(fds, 10, 1)
        return 1
    }

    fun test_removals(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_removals"

        files:CPointer<IntVar>, *fds = NULL
        ret:Int, i

        files = open_files(100, 0, 0)
        ret = io_uring_register_files(ring, files, 100)
        if (ret.nz) {
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        fds = t_calloc(10, Int.SIZE_BYTES)
        for (i in 0 until 10)
            fds[i] = -1

        ret = io_uring_register_files_update(ring, 50, fds, 10)
        if (ret != 10) {
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        close_files(files, 100, 0)
        if (fds)
            free(fds)
        return 0
        err:
        close_files(files, 100, 0)
        if (fds)
            free(fds)
        return 1
    }

    fun test_additions(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_additions"

        files:CPointer<IntVar>, *fds = NULL
        ret:Int

        files = open_files(100, 100, 0)
        ret = io_uring_register_files(ring, files, 200)
        if (ret.nz) {
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        fds = open_files(2, 0, 1)
        ret = io_uring_register_files_update(ring, 100, fds, 2)
        if (ret != 2) {
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        close_files(files, 100, 0)
        if (fds)
            close_files(fds, 2, 1)
        return 0
        err:
        close_files(files, 100, 0)
        if (fds)
            close_files(fds, 2, 1)
        return 1
    }

    fun test_sparse(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_sparse"

        files:CPointer<IntVar>
        ret:Int

        files = open_files(100, 100, 0)
        ret = io_uring_register_files(ring, files, 200)
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
        done:
        close_files(files, 100, 0)
        return 0
        err:
        close_files(files, 100, 0)
        return 1
    }

    fun test_basic_many(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_basic_many"

        files:CPointer<IntVar>
        ret:Int

        files = open_files(768, 0, 0)
        ret = io_uring_register_files(ring, files, 768)
        if (ret.nz) {
            fprintf(stderr, "%s: register %d\n", __FUNCTION__, ret)
            break@err
        }
        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister %d\n", __FUNCTION__, ret)
            break@err
        }
        close_files(files, 768, 0)
        return 0
        err:
        close_files(files, 768, 0)
        return 1
    }

    fun test_basic(ring: CPointer<io_uring>, fail: Int): Int {
        val __FUNCTION__ = "test_basic"

        files:CPointer<IntVar>
        ret:Int
        nr_files:Int = fail ? 10 : 100

        files = open_files(nr_files, 0, 0)
        ret = io_uring_register_files(ring, files, 100)
        if (ret.nz) {
            if (fail) {
                if (ret == -EBADF || ret == -EFAULT)
                    return 0
            }
            fprintf(stderr, "%s: register %d\n", __FUNCTION__, ret)
            break@err
        }
        if (fail) {
            fprintf(stderr, "Registration succeeded, but expected fail\n")
            break@err
        }
        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister %d\n", __FUNCTION__, ret)
            break@err
        }
        close_files(files, nr_files, 0)
        return 0
        err:
        close_files(files, nr_files, 0)
        return 1
    }

    /*
     * Register 0 files, but reserve space for 10.  Then add one file.
     */
    fun test_zero(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_zero"

        files:CPointer<IntVar>, *fds = NULL
        ret:Int

        files = open_files(0, 10, 0)
        ret = io_uring_register_files(ring, files, 10)
        if (ret.nz) {
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        fds = open_files(1, 0, 1)
        ret = io_uring_register_files_update(ring, 0, fds, 1)
        if (ret != 1) {
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        if (fds)
            close_files(fds, 1, 1)
        free(files)
        return 0
        err:
        if (fds)
            close_files(fds, 1, 1)
        free(files)
        return 1
    }

    fun test_fixed_read_write(ring: CPointer<io_uring>, index: Int): Int {
        val __FUNCTION__ = "test_fixed_read_write"

        sqe:CPointer<io_uring_sqe>
        cqe:CPointer<io_uring_cqe>
        iov:iovec[2]
        ret:Int

        iov[0].iov_base = t_malloc(4096)
        iov[0].iov_len = 4096
        memset(iov[0].iov_base, 0x5a, 4096)

        iov[1].iov_base = t_malloc(4096)
        iov[1].iov_len = 4096

        sqe = io_uring_get_sqe(ring)
        if (!sqe) {
            fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__)
            return 1
        }
        io_uring_prep_writev(sqe, index, iov.ptr[0], 1, 0)
        sqe.pointed.flags | = IOSQE_FIXED_FILE
        sqe.pointed.user_data = 1

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
        if (cqe.pointed.res != 4096) {
            fprintf(stderr, "%s: write cqe.pointed.res=%d\n", __FUNCTION__, cqe.pointed.res)
            return 1
        }
        io_uring_cqe_seen(ring, cqe)

        sqe = io_uring_get_sqe(ring)
        if (!sqe) {
            fprintf(stderr, "%s: failed to get sqe\n", __FUNCTION__)
            return 1
        }
        io_uring_prep_readv(sqe, index, iov.ptr[1], 1, 0)
        sqe.pointed.flags | = IOSQE_FIXED_FILE
        sqe.pointed.user_data = 2

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
        if (cqe.pointed.res != 4096) {
            fprintf(stderr, "%s: read cqe.pointed.res=%d\n", __FUNCTION__, cqe.pointed.res)
            return 1
        }
        io_uring_cqe_seen(ring, cqe)

        if (memcmp(iov[1].iov_base, iov[0].iov_base, 4096)) {
            fprintf(stderr, "%s: data mismatch\n", __FUNCTION__)
            return 1
        }

        free(iov[0].iov_base)
        free(iov[1].iov_base)
        return 0
    }

    fun adjust_nfiles(want_files: Int) {
        val __FUNCTION__ = "adjust_nfiles"

        rlim:rlimit

        if (getrlimit(RLIMIT_NOFILE, rlim.ptr) < 0)
            return
        if (rlim.rlim_cur >= want_files)
            return
        rlim.rlim_cur = want_files
        setrlimit(RLIMIT_NOFILE, rlim.ptr)
    }

    /*
     * Register 8K of sparse files, update one at a random spot, then do some
     * file IO to verify it works.
     */
    fun test_huge(ring: CPointer<io_uring>)
            : Int {
        val __FUNCTION__ = "test_huge"

        files:CPointer<IntVar>
        ret:Int

        adjust_nfiles(16384)

        files = open_files(0, 8192, 0)
        ret = io_uring_register_files(ring, files, 8192)
        if (ret.nz) {
            /* huge sets not supported */
            if (ret == -EMFILE) {
                fprintf(stdout, "%s: No huge file set support, skipping\n", __FUNCTION__)
                break@out
            }
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        files[7193] = open(".reg.7193", O_RDWR or O_CREAT, 0644)
        if (files[7193] < 0) {
            fprintf(stderr, "%s: open=%d\n", __FUNCTION__, errno)
            break@err
        }

        ret = io_uring_register_files_update(ring, 7193, files.ptr[7193], 1)
        if (ret != 1) {
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        if (test_fixed_read_write(ring, 7193))
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
        out:
        free(files)
        return 0
        err:
        if (files[7193] != -1) {
            close(files[7193])
            unlink(".reg.7193")
        }
        free(files)
        return 1
    }

    fun test_skip(ring: CPointer<io_uring>): Int {
        val __FUNCTION__ = "test_skip"

        files:CPointer<IntVar>
        ret:Int

        files = open_files(100, 0, 0)
        ret = io_uring_register_files(ring, files, 100)
        if (ret.nz) {
            fprintf(stderr, "%s: register ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        files[90] = IORING_REGISTER_FILES_SKIP
        ret = io_uring_register_files_update(ring, 90, files.ptr[90], 1)
        if (ret != 1) {
            if (ret == -EBADF) {
                fprintf(stdout, "Skipping files not supported\n")
                break@done
            }
            fprintf(stderr, "%s: update ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        /* verify can still use file index 90 */
        if (test_fixed_read_write(ring, 90))
            break@err

                    ret = io_uring_unregister_files(ring)
        if (ret.nz) {
            fprintf(stderr, "%s: unregister ret=%d\n", __FUNCTION__, ret)
            break@err
        }

        done:
        close_files(files, 100, 0)
        return 0
        err:
        close_files(files, 100, 0)
        return 1
    }

    fun test_sparse_updates(void)
            : Int {
        val __FUNCTION__ = "test_sparse_updates"

        ring:io_uring
        ret:Int, i, *fds, newfd

        ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "queue_init: %d\n", ret)
            return ret
        }

        fds = t_malloc(sizeof:CPointer<256>(int))
        for (i in 0 until 256)
            fds[i] = -1

        ret = io_uring_register_files(ring.ptr, fds, 256)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }

        newfd = 1
        for (i in 0 until 256) {
            ret = io_uring_register_files_update(ring.ptr, i, newfd.ptr, 1)
            if (ret != 1) {
                fprintf(stderr, "file_update: %d\n", ret)
                return ret
            }
        }
        io_uring_unregister_files(ring.ptr)

        for (i in 0 until 256)
            fds[i] = 1

        ret = io_uring_register_files(ring.ptr, fds, 256)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }

        newfd = -1
        for (i in 0 until 256) {
            ret = io_uring_register_files_update(ring.ptr, i, newfd.ptr, 1)
            if (ret != 1) {
                fprintf(stderr, "file_update: %d\n", ret)
                return ret
            }
        }
        io_uring_unregister_files(ring.ptr)

        io_uring_queue_exit(ring.ptr)
        return 0
    }

    fun test_fixed_removal_ordering(void)
            : Int {
        val __FUNCTION__ = "test_fixed_removal_ordering"

        char buffer [128]
        ring:io_uring
        sqe:CPointer<io_uring_sqe>
        cqe:CPointer<io_uring_cqe>
        ts:__kernel_timespec
        ret:Int, fd, i, fds[2]

        ret = io_uring_queue_init(8, ring.ptr, 0)
        if (ret < 0) {
            fprintf(stderr, "failed to init io_uring: %s\n", strerror(-ret))
            return ret
        }
        if (pipe(fds)) {
            perror("pipe")
            return -1
        }
        ret = io_uring_register_files(ring.ptr, fds, 2)
        if (ret.nz) {
            fprintf(stderr, "file_register: %d\n", ret)
            return ret
        }
        /* ring should have fds referenced, can close them */
        close(fds[0])
        close(fds[1])

        sqe = io_uring_get_sqe(ring.ptr)
        if (!sqe) {
            fprintf(stderr, "%s: get sqe failed\n", __FUNCTION__)
            return 1
        }
        /* outwait file recycling delay */
        ts.tv_sec = 3
        ts.tv_nsec = 0
        io_uring_prep_timeout(sqe, ts.ptr, 0, 0)
        sqe.pointed.flags | = IOSQE_IO_LINK or IOSQE_IO_HARDLINK
        sqe.pointed.user_data = 1

        sqe = io_uring_get_sqe(ring.ptr)
        if (!sqe) {
            printf("get sqe failed\n")
            return -1
        }
        io_uring_prep_write(sqe, 1, buffer, sizeof(buffer), 0)
        sqe.pointed.flags | = IOSQE_FIXED_FILE
        sqe.pointed.user_data = 2

        ret = io_uring_submit(ring.ptr)
        if (ret != 2) {
            fprintf(stderr, "%s: got %d, wanted 2\n", __FUNCTION__, ret)
            return -1
        }

        /* remove unused pipe end */
        fd = -1
        ret = io_uring_register_files_update(ring.ptr, 0, fd.ptr, 1)
        if (ret != 1) {
            fprintf(stderr, "update off=0 failed\n")
            return -1
        }

        /* remove used pipe end */
        fd = -1
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
            io_uring_cqe_seen(ring.ptr, cqe)
        }

        io_uring_queue_exit(ring.ptr)
        return 0
    }


    fun main(argc: Int, char: CPointer<ByteVar>argv]):Int
    {
        val __FUNCTION__ = "main"

        ring:io_uring
        ret:Int

        if (argc > 1)
            return 0

        ret = io_uring_queue_init(8, ring.ptr, 0)
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

        if (no_update)
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

    companion object {
        enum class end
    }
}

fun main(args: Array<String>) {
    val __FUNCTION__ = "main"

    exit(FileRegister().main(args))
}
