/* SPDX-License-Identifier: MIT */
// autogenerated by syzkaller (https://github.com/google/syzkaller)

//include <dirent.h>
//include <endian.h>
//include <errno.h>
//include <fcntl.h>
//include <signal.h>
//include <stdarg.h>
//include <stdbool.h>
//include <stdint.h>
//include <stdio.h>
//include <stdlib.h>
//include <string.h>
//include <sys/prctl.h>
//include <sys/stat.h>
//include <sys/types.h>
//include <sys/wait.h>
//include <sys/mman.h>
//include <time.h>
//include <unistd.h>

//include "liburing.h"
//include "../src/syscall.h"

fun sleep_ms(ms:uint64_t):Unit{
	val __FUNCTION__="sleep_ms"

    usleep(ms * 1000);
}

fun current_time_ms(void):uint64_t{
	val __FUNCTION__="current_time_ms"

    ts:timespec;
    if (clock_gettime(CLOCK_MONOTONIC, ts.ptr))
        exit(1);
    return (uint64_t) ts.tv_sec * 1000 + (uint64_t) ts.tv_nsec / 1000000;
}

fun write_file(file:String, what:String, ...):Boolean{
	val __FUNCTION__="write_file"

    char buf[1024];
    va_list args;
    va_start(args, what);
    vsnprintf(buf, sizeof(buf), what, args);
    va_end(args);
    buf[sizeof(buf) - 1] = 0;
    len:Int = strlen(buf);
    fd:Int = open(file,  O_WRONLY or O_CLOEXEC );
    if (fd == -1)
        return false;
    if (write(fd, buf, len) != len) {
        err:Int = errno;
        close(fd);
        errno = err;
        return false;
    }
    close(fd);
    return true;
}

fun kill_and_wait(pid:Int, status:CPointer<Int>):Unit{
	val __FUNCTION__="kill_and_wait"

    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    i:Int;
    for (i in 0 until  100) {
        if (waitpid(-1, status,  WNOHANG or __WALL ) == pid)
            return;
        usleep(1000);
    }
    dir:CPointer<DIR> = opendir("/sys/fs/fuse/connections");
    if (dir) {
        for (;;) {
            ent:CPointer<dirent> = readdir(dir);
            if (!ent)
                break;
            if (strcmp(ent.pointed.d_name, ".") == 0 || strcmp(ent.pointed.d_name, "..") == 0)
                continue;
            char abort[300];
            snprintf(abort, sizeof(abort), "/sys/fs/fuse/connections/%s/abort",
                     ent.pointed.d_name);
            fd:Int = open(abort, O_WRONLY);
            if (fd == -1) {
                continue;
            }
            if (write(fd, abort, 1) < 0) {
            }
            close(fd);
        }
        closedir(dir);
    } else {
    }
    while (waitpid(-1, status, __WALL) != pid) {
    }
}

fun setup_test():Unit{
	val __FUNCTION__="setup_test"

    prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
    setpgrp();
    write_file("/proc/self/oom_score_adj", "1000");
}

static void execute_one(void);

#define WAIT_FLAGS __WALL

fun loop(void):Unit{
	val __FUNCTION__="loop"

    iter:Int;
    for (iter in 0 until  5000) {
        pid:Int = fork();
        if (pid < 0)
            exit(1);
        if (pid == 0) {
            setup_test();
            execute_one();
            exit(0);
        }
        status:Int = 0;
        start:uint64_t = current_time_ms();
        for (;;) {
            if (waitpid(-1, status.ptr,  WNOHANG or WAIT_FLAGS ) == pid)
                break;
            sleep_ms(1);
            if (current_time_ms() - start < 5 * 1000)
                continue;
            kill_and_wait(pid, status.ptr);
            break;
        }
    }
}

fun execute_one(void):Unit{
	val __FUNCTION__="execute_one"

    *(uint32_t *) 0x20000080 = 0;
    *(uint32_t *) 0x20000084 = 0;
    *(uint32_t *) 0x20000088 = 3;
    *(uint32_t *) 0x2000008c = 3;
    *(uint32_t *) 0x20000090 = 0x175;
    *(uint32_t *) 0x20000094 = 0;
    *(uint32_t *) 0x20000098 = 0;
    *(uint32_t *) 0x2000009c = 0;
    *(uint32_t *) 0x200000a0 = 0;
    *(uint32_t *) 0x200000a4 = 0;
    *(uint32_t *) 0x200000a8 = 0;
    *(uint32_t *) 0x200000ac = 0;
    *(uint32_t *) 0x200000b0 = 0;
    *(uint32_t *) 0x200000b4 = 0;
    *(uint32_t *) 0x200000b8 = 0;
    *(uint32_t *) 0x200000bc = 0;
    *(uint32_t *) 0x200000c0 = 0;
    *(uint32_t *) 0x200000c4 = 0;
    *(uint64_t *) 0x200000c8 = 0;
    *(uint32_t *) 0x200000d0 = 0;
    *(uint32_t *) 0x200000d4 = 0;
    *(uint32_t *) 0x200000d8 = 0;
    *(uint32_t *) 0x200000dc = 0;
    *(uint32_t *) 0x200000e0 = 0;
    *(uint32_t *) 0x200000e4 = 0;
    *(uint32_t *) 0x200000e8 = 0;
    *(uint32_t *) 0x200000ec = 0;
    *(uint64_t *) 0x200000f0 = 0;
    __sys_io_uring_setup(0x983, (struct io_uring_params *) 0x20000080);
}

fun sig_int(sig:Int):Unit{
	val __FUNCTION__="sig_int"

    exit(0);
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    if (argc > 1)
        return 0;
    signal(SIGINT, sig_int);
    mmap((void *) 0x20000000, 0x1000000, 3, 0x32, -1, 0);
    loop();
    return 0;
}
