/* SPDX-License-Identifier: MIT */
/*
 * Description: test sharing a ring across a fork
 */
//include <fcntl.h>
//include <pthread.h>
//include <signal.h>
//include <stdio.h>
//include <stdlib.h>
//include <string.h>
//include <sys/mman.h>
//include <sys/stat.h>
//include <sys/types.h>
//include <sys/wait.h>
//include <unistd.h>

//include "liburing.h"


struct forktestmem {
    ring:io_uring;
    barrier:pthread_barrier_t;
    barrierattr:pthread_barrierattr_t;
};

fun open_tempfile(dir:String, fname:String):Int{
	val __FUNCTION__="open_tempfile"

    fd:Int;
    char buf[32];

    snprintf(buf, sizeof(buf), "%s/%s",
             dir, fname);
    fd = open(buf,  O_RDWR or O_CREAT  | O_APPEND,  S_IRUSR or S_IWUSR );
    if (fd < 0) {
        perror("open");
        exit(1);
    }

    return fd;
}

static submit_write:Int(ring:CPointer<io_uring>, fd:Int, str:String,
        wait:Int) {
    sqe:CPointer<io_uring_sqe>;
    iovec:iovec;
    ret:Int;

    sqe = io_uring_get_sqe(ring);
    if (!sqe) {
        fprintf(stderr, "could not get sqe\n");
        return 1;
    }

    iovec.iov_base = (char *) str;
    iovec.iov_len = strlen(str);
    io_uring_prep_writev(sqe, fd, iovec.ptr, 1, 0);
    ret = io_uring_submit_and_wait(ring, wait);
    if (ret < 0) {
        fprintf(stderr, "submit failed: %s\n", strerror(-ret));
        return 1;
    }

    return 0;
}

fun wait_cqe(ring:CPointer<io_uring>, stage:String):Int{
	val __FUNCTION__="wait_cqe"

    cqe:CPointer<io_uring_cqe>;
    ret:Int;

    ret = io_uring_wait_cqe(ring, cqe.ptr);
    if (ret) {
        fprintf(stderr, "%s wait_cqe failed %d\n", stage, ret);
        return 1;
    }
    if (cqe.pointed.res < 0) {
        fprintf(stderr, "%s cqe failed %d\n", stage, cqe.pointed.res);
        return 1;
    }

    io_uring_cqe_seen(ring, cqe);
    return 0;
}

fun verify_file(tmpdir:String, fname:String, expect:String):Int{
	val __FUNCTION__="verify_file"

    fd:Int;
    char buf[512];
    err:Int = 0;

    memset(buf, 0, sizeof(buf));

    fd = open_tempfile(tmpdir, fname);
    if (fd < 0)
        return 1;

    if (read(fd, buf, sizeof(buf) - 1) < 0)
        return 1;

    if (strcmp(buf, expect) != 0) {
        fprintf(stderr, "content mismatch for %s\n"
                        "got:\n%s\n"
                        "expected:\n%s\n",
                fname, buf, expect);
        err = 1;
    }

    close(fd);
    return err;
}

fun cleanup(tmpdir:String):Unit{
	val __FUNCTION__="cleanup"

    char buf[32];

    /* don't check errors, called during partial runs */

    snprintf(buf, sizeof(buf), "%s/%s", tmpdir, "shared");
    unlink(buf);

    snprintf(buf, sizeof(buf), "%s/%s", tmpdir, "parent1");
    unlink(buf);

    snprintf(buf, sizeof(buf), "%s/%s", tmpdir, "parent2");
    unlink(buf);

    snprintf(buf, sizeof(buf), "%s/%s", tmpdir, "child");
    unlink(buf);

    rmdir(tmpdir);
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    shmem:CPointer<forktestmem>;
    char:CPointer<ByteVar>tmpdir= "forktmpXXXXXX";
    shared_fd:Int;
    ret:Int;
    p:pid_t;

    if (argc > 1)
        return 0;

    shmem = mmap(0, sizeof(struct forktestmem),  PROT_READ or PROT_WRITE ,
                  MAP_SHARED or MAP_ANONYMOUS , 0, 0);
    if (!shmem) {
        fprintf(stderr, "mmap failed\n");
        exit(1);
    }

    pthread_barrierattr_init(shmem.pointed.barrierattr.ptr);
    pthread_barrierattr_setpshared(shmem.pointed.barrierattr.ptr, 1);
    pthread_barrier_init(shmem.pointed.barrier.ptr, shmem.pointed.barrierattr.ptr, 2);

    ret = io_uring_queue_init(10, shmem.pointed.ring.ptr, 0);
    if (ret < 0) {
        fprintf(stderr, "queue init failed\n");
        exit(1);
    }

    if (mkdtemp(tmpdir) == NULL) {
        fprintf(stderr, "temp directory creation failed\n");
        exit(1);
    }

    shared_fd = open_tempfile(tmpdir, "shared");

    /*
     * First do a write before the fork, to test whether child can
     * reap that
     */
    if (submit_write(shmem.pointed.ring.ptr, shared_fd, "before fork: write shared fd\n", 0))
        break@errcleanup;

    p = fork();
    when  (p)  {
        -1 -> 
            fprintf(stderr, "fork failed\n");
            break@errcleanup;

        default: {
            /* parent */
            parent_fd1:Int;
            parent_fd2:Int;
            wstatus:Int;

            /* wait till fork is started up */
            pthread_barrier_wait(shmem.pointed.barrier.ptr);

            parent_fd1 = open_tempfile(tmpdir, "parent1");
            parent_fd2 = open_tempfile(tmpdir, "parent2");

            /* do a parent write to the shared fd */
            if (submit_write(shmem.pointed.ring.ptr, shared_fd, "parent: write shared fd\n", 0))
                break@errcleanup;

            /* do a parent write to an fd where same numbered fd exists in child */
            if (submit_write(shmem.pointed.ring.ptr, parent_fd1, "parent: write parent fd 1\n", 0))
                break@errcleanup;

            /* do a parent write to an fd where no same numbered fd exists in child */
            if (submit_write(shmem.pointed.ring.ptr, parent_fd2, "parent: write parent fd 2\n", 0))
                break@errcleanup;

            /* wait to switch read/writ roles with child */
            pthread_barrier_wait(shmem.pointed.barrier.ptr);

            /* now wait for child to exit, to ensure we still can read completion */
            waitpid(p, wstatus.ptr, 0);
            if (WEXITSTATUS(wstatus) != 0) {
                fprintf(stderr, "child failed\n");
                break@errcleanup;
            }

            if (wait_cqe(shmem.pointed.ring.ptr, "p cqe 1"))
                break@errcleanup;

            if (wait_cqe(shmem.pointed.ring.ptr, "p cqe 2"))
                break@errcleanup;

            /* check that IO can still be submitted after child exited */
            if (submit_write(shmem.pointed.ring.ptr, shared_fd, "parent: write shared fd after child exit\n", 0))
                break@errcleanup;

            if (wait_cqe(shmem.pointed.ring.ptr, "p cqe 3"))
                break@errcleanup;

            break;
        }
        0 ->  {
            /* child */
            child_fd:Int;

            /* wait till fork is started up */
            pthread_barrier_wait(shmem.pointed.barrier.ptr);

            child_fd = open_tempfile(tmpdir, "child");

            if (wait_cqe(shmem.pointed.ring.ptr, "c cqe shared"))
                exit(1);

            if (wait_cqe(shmem.pointed.ring.ptr, "c cqe parent 1"))
                exit(1);

            if (wait_cqe(shmem.pointed.ring.ptr, "c cqe parent 2"))
                exit(1);

            if (wait_cqe(shmem.pointed.ring.ptr, "c cqe parent 3"))
                exit(1);

            /* wait to switch read/writ roles with parent */
            pthread_barrier_wait(shmem.pointed.barrier.ptr);

            if (submit_write(shmem.pointed.ring.ptr, child_fd, "child: write child fd\n", 0))
                exit(1);

            /* ensure both writes have finished before child exits */
            if (submit_write(shmem.pointed.ring.ptr, shared_fd, "child: write shared fd\n", 2))
                exit(1);

            exit(0);
        }
    }

    if (verify_file(tmpdir, "shared",
                    "before fork: write shared fd\n"
                    "parent: write shared fd\n"
                    "child: write shared fd\n"
                    "parent: write shared fd after child exit\n") ||
        verify_file(tmpdir, "parent1", "parent: write parent fd 1\n") ||
        verify_file(tmpdir, "parent2", "parent: write parent fd 2\n") ||
        verify_file(tmpdir, "child", "child: write child fd\n"))
        break@errcleanup;

    cleanup(tmpdir);
    exit(0);

    errcleanup:
    cleanup(tmpdir);
    exit(1);
}
