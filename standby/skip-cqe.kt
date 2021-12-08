//include <errno.h>
//include <stdio.h>
//include <unistd.h>
//include <stdlib.h>
//include <string.h>
//include <fcntl.h>
//include <assert.h>

//include "liburing.h"

#define LINK_SIZE        6
#define TIMEOUT_USER_DATA    (-1)

static fds:Int[2];

/* should be successfully submitted but fails during execution */
fun prep_exec_fail_req(sqe:CPointer<io_uring_sqe>):Unit{
	val __FUNCTION__="prep_exec_fail_req"

    io_uring_prep_write(sqe, fds[1], NULL, 100, 0);
}

fun test_link_success(ring:CPointer<io_uring>, nr:Int, skip_last:Boolean):Int{
	val __FUNCTION__="test_link_success"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    for (i in 0 until  nr) {
        sqe = io_uring_get_sqe(ring);
        io_uring_prep_nop(sqe);
        if (i != nr - 1 || skip_last)
            sqe.pointed.flags |=  IOSQE_IO_LINK or IOSQE_CQE_SKIP_SUCCESS ;
        sqe.pointed.user_data = i;
    }

    ret = io_uring_submit(ring);
    if (ret != nr) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }

    if (!skip_last) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret != 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }
        if (cqe.pointed.res != 0) {
            fprintf(stderr, "nop failed: res %d\n", cqe.pointed.res);
            break@err;
        }
        if (cqe.pointed.user_data != nr - 1) {
            fprintf(stderr, "invalid user_data %i\n", (int) cqe.pointed.user_data);
            break@err;
        }
        io_uring_cqe_seen(ring, cqe);
    }

    if (io_uring_peek_cqe(ring, cqe.ptr) >= 0) {
        fprintf(stderr, "single CQE expected %i\n", (int) cqe.pointed.user_data);
        break@err;
    }
    return 0;
    err:
    return 1;
}

fun test_link_fail(ring:CPointer<io_uring>, nr:Int, fail_idx:Int):Int{
	val __FUNCTION__="test_link_fail"

    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    for (i in 0 until  nr) {
        sqe = io_uring_get_sqe(ring);
        if (i == fail_idx)
            prep_exec_fail_req(sqe);
        else
            io_uring_prep_nop(sqe);

        if (i != nr - 1)
            sqe.pointed.flags |=  IOSQE_IO_LINK or IOSQE_CQE_SKIP_SUCCESS ;
        sqe.pointed.user_data = i;
    }

    ret = io_uring_submit(ring);
    if (ret != nr) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }
    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret != 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        break@err;
    }
    if (!cqe.pointed.res || cqe.pointed.user_data != fail_idx) {
        fprintf(stderr, "got: user_data %d res %d, expected data: %d\n",
                (int) cqe.pointed.user_data, cqe.pointed.res, fail_idx);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);

    if (io_uring_peek_cqe(ring, cqe.ptr) >= 0) {
        fprintf(stderr, "single CQE expected %i\n", (int) cqe.pointed.user_data);
        break@err;
    }
    return 0;
    err:
    return 1;
}

static test_ltimeout_cancel:Int(ring:CPointer<io_uring>, nr:Int, tout_idx:Int,
        async:Boolean, fail_idx:Int) {
    ts:__kernel_timespec = {.tv_sec = 1, .tv_nsec = 0};
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;
    e_res:Int = 0, e_idx = nr - 1;

    if (fail_idx >= 0) {
        e_res = -EFAULT;
        e_idx = fail_idx;
    }

    for (i in 0 until  nr) {
        sqe = io_uring_get_sqe(ring);
        if (i == fail_idx)
            prep_exec_fail_req(sqe);
        else
            io_uring_prep_nop(sqe);
        sqe.pointed.user_data = i;
        sqe.pointed.flags |= IOSQE_IO_LINK;
        if (async)
            sqe.pointed.flags |= IOSQE_ASYNC;
        if (i != nr - 1)
            sqe.pointed.flags |= IOSQE_CQE_SKIP_SUCCESS;

        if (i == tout_idx) {
            sqe = io_uring_get_sqe(ring);
            io_uring_prep_link_timeout(sqe, ts.ptr, 0);
            sqe.pointed.flags |=  IOSQE_IO_LINK or IOSQE_CQE_SKIP_SUCCESS ;
            sqe.pointed.user_data = TIMEOUT_USER_DATA;
        }
    }

    ret = io_uring_submit(ring);
    if (ret != nr + 1) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }
    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret != 0) {
        fprintf(stderr, "wait completion %d\n", ret);
        break@err;
    }
    if (cqe.pointed.user_data != e_idx) {
        fprintf(stderr, "invalid user_data %i\n", (int) cqe.pointed.user_data);
        break@err;
    }
    if (cqe.pointed.res != e_res) {
        fprintf(stderr, "unexpected res: %d\n", cqe.pointed.res);
        break@err;
    }
    io_uring_cqe_seen(ring, cqe);

    if (io_uring_peek_cqe(ring, cqe.ptr) >= 0) {
        fprintf(stderr, "single CQE expected %i\n", (int) cqe.pointed.user_data);
        break@err;
    }
    return 0;
    err:
    return 1;
}

static test_ltimeout_fire:Int(ring:CPointer<io_uring>, async:Boolean,
        skip_main:Boolean, skip_tout:Boolean) {
    char buf[1];
    ts:__kernel_timespec = {.tv_sec = 0, .tv_nsec = 1000000};
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;
    nr:Int = 1 + !skip_tout;

    sqe = io_uring_get_sqe(ring);
    io_uring_prep_read(sqe, fds[0], buf, sizeof(buf), 0);
    sqe.pointed.flags |= IOSQE_IO_LINK;
    sqe.pointed.flags |= async ? IOSQE_ASYNC : 0;
    sqe.pointed.flags |= skip_main ? IOSQE_CQE_SKIP_SUCCESS : 0;
    sqe.pointed.user_data = 0;

    sqe = io_uring_get_sqe(ring);
    io_uring_prep_link_timeout(sqe, ts.ptr, 0);
    sqe.pointed.flags |= skip_tout ? IOSQE_CQE_SKIP_SUCCESS : 0;
    sqe.pointed.user_data = 1;

    ret = io_uring_submit(ring);
    if (ret != 2) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        return 1;
    }

    for (i in 0 until  nr) {
        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret != 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            return 1;
        }
        when  (cqe.pointed.user_data)  {
            0 -> 
                if (cqe.pointed.res != -ECANCELED && cqe.pointed.res != -EINTR) {
                    fprintf(stderr, "unexpected read return: %d\n", cqe.pointed.res);
                    return 1;
                }
                break;
            1 -> 
                if (skip_tout) {
                    fprintf(stderr, "extra timeout cqe, %d\n", cqe.pointed.res);
                    return 1;
                }
                break;
        }
        io_uring_cqe_seen(ring, cqe);
    }


    if (io_uring_peek_cqe(ring, cqe.ptr) >= 0) {
        fprintf(stderr, "single CQE expected: got data: %i res: %i\n",
                (int) cqe.pointed.user_data, cqe.pointed.res);
        return 1;
    }
    return 0;
}

static test_hardlink:Int(ring:CPointer<io_uring>, nr:Int, fail_idx:Int,
        skip_idx:Int, hardlink_last:Boolean) {
    cqe:CPointer<io_uring_cqe>;
    sqe:CPointer<io_uring_sqe>;
    ret:Int, i;

    assert(fail_idx < nr);
    assert(skip_idx < nr);

    for (i in 0 until  nr) {
        sqe = io_uring_get_sqe(ring);
        if (i == fail_idx)
            prep_exec_fail_req(sqe);
        else
            io_uring_prep_nop(sqe);
        if (i != nr - 1 || hardlink_last)
            sqe.pointed.flags |= IOSQE_IO_HARDLINK;
        if (i == skip_idx)
            sqe.pointed.flags |= IOSQE_CQE_SKIP_SUCCESS;
        sqe.pointed.user_data = i;
    }

    ret = io_uring_submit(ring);
    if (ret != nr) {
        fprintf(stderr, "sqe submit failed: %d\n", ret);
        break@err;
    }

    for (i in 0 until  nr) {
        if (i == skip_idx && fail_idx != skip_idx)
            continue;

        ret = io_uring_wait_cqe(ring, cqe.ptr);
        if (ret != 0) {
            fprintf(stderr, "wait completion %d\n", ret);
            break@err;
        }
        if (cqe.pointed.user_data != i) {
            fprintf(stderr, "invalid user_data %d (%i)\n",
                    (int) cqe.pointed.user_data, i);
            break@err;
        }
        if (i == fail_idx) {
            if (cqe.pointed.res >= 0) {
                fprintf(stderr, "req should've failed %d %d\n",
                        (int) cqe.pointed.user_data, cqe.pointed.res);
                break@err;
            }
        } else {
            if (cqe.pointed.res) {
                fprintf(stderr, "req error %d %d\n",
                        (int) cqe.pointed.user_data, cqe.pointed.res);
                break@err;
            }
        }

        io_uring_cqe_seen(ring, cqe);
    }

    if (io_uring_peek_cqe(ring, cqe.ptr) >= 0) {
        fprintf(stderr, "single CQE expected %i\n", (int) cqe.pointed.user_data);
        break@err;
    }
    return 0;
    err:
    return 1;
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    ring:io_uring;
    ret:Int, i, j, k;
    mid_idx:Int = LINK_SIZE / 2;
    last_idx:Int = LINK_SIZE - 1;

    if (pipe(fds)) {
        fprintf(stderr, "pipe() failed\n");
        return 1;
    }
    ret = io_uring_queue_init(16, ring.ptr, 0);
    if (ret) {
        fprintf(stderr, "ring setup failed: %d\n", ret);
        return 1;
    }

    if (!(ring. features and IORING_FEAT_CQE_SKIP )) {
        printf("IOSQE_CQE_SKIP_SUCCESS is not supported, skip\n");
        return 0;
    }

    for (i in 0 until  4) {
        skip_last:Boolean =  i and 1 ;
        sz:Int = ( i and 2 ) ? LINK_SIZE : 1;

        ret = test_link_success(ring.ptr, sz, skip_last);
        if (ret) {
            fprintf(stderr, "test_link_success sz %d, %d last\n",
                    skip_last, sz);
            return ret;
        }
    }

    ret = test_link_fail(ring.ptr, LINK_SIZE, mid_idx);
    if (ret) {
        fprintf(stderr, "test_link_fail mid failed\n");
        return ret;
    }

    ret = test_link_fail(ring.ptr, LINK_SIZE, last_idx);
    if (ret) {
        fprintf(stderr, "test_link_fail last failed\n");
        return ret;
    }

    for (i in 0 until  2) {
        async:Boolean =  i and 1 ;

        ret = test_ltimeout_cancel(ring.ptr, 1, 0, async, -1);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel 1 failed, %i\n",
                    async);
            return ret;
        }
        ret = test_ltimeout_cancel(ring.ptr, LINK_SIZE, mid_idx, async, -1);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel mid failed, %i\n",
                    async);
            return ret;
        }
        ret = test_ltimeout_cancel(ring.ptr, LINK_SIZE, last_idx, async, -1);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel last failed, %i\n",
                    async);
            return ret;
        }
        ret = test_ltimeout_cancel(ring.ptr, LINK_SIZE, mid_idx, async, mid_idx);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel fail mid failed, %i\n",
                    async);
            return ret;
        }
        ret = test_ltimeout_cancel(ring.ptr, LINK_SIZE, mid_idx, async, mid_idx - 1);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel fail2 mid failed, %i\n",
                    async);
            return ret;
        }
        ret = test_ltimeout_cancel(ring.ptr, LINK_SIZE, mid_idx, async, mid_idx + 1);
        if (ret) {
            fprintf(stderr, "test_ltimeout_cancel fail3 mid failed, %i\n",
                    async);
            return ret;
        }
    }

    for (i in 0 until  8) {
        async:Boolean =  i and 1 ;
        skip1:Boolean =  i and 2 ;
        skip2:Boolean =  i and 4 ;

        ret = test_ltimeout_fire(ring.ptr, async, skip1, skip2);
        if (ret) {
            fprintf(stderr, "test_ltimeout_fire failed\n");
            return ret;
        }
    }

    /* test 3 positions, start/middle/end of the link, i.e. indexes 0, 3, 6 */
    for (i in 0 until  3) {
        for (j in 0 until  3) {
            for (k in 0 until  2) {
                mark_last:Boolean =  k and 1 ;

                ret = test_hardlink(ring.ptr, 7, i * 3, j * 3, mark_last);
                if (ret) {
                    fprintf(stderr, "test_hardlink failed"
                                    "fail %i skip %i mark last %i\n",
                            i * 3, j * 3, k);
                    return 1;
                }
            }
        }
    }

    close(fds[0]);
    close(fds[1]);
    io_uring_queue_exit(ring.ptr);
    return 0;
}
