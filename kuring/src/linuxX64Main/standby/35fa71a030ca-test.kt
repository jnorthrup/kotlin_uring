/* SPDX-License-Identifier: MIT */
// autogenerated by syzkaller (https://github.com/google/syzkaller)

#include <dirent.h>
#include <endian.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <sys/mman.h>

#include <linux/futex.h>

#include "liburing.h"
#include "../src/syscall.h"

#if !defined(SYS_futex) && defined(SYS_futex_time64)
# define SYS_futex SYS_futex_time64
#endif

static void sleep_ms(ms:uint64_t) {
    usleep(ms * 1000);
}

static current_time_ms:uint64_t(void) {
    ts:timespec;
    if (clock_gettime(CLOCK_MONOTONIC, ts.ptr))
        exit(1);
    return (uint64_t) ts.tv_sec * 1000 + (uint64_t) ts.tv_nsec / 1000000;
}

static void thread_start(void *(*fn)(void *), arg:CPointer<ByteVar> ) {
    th:pthread_t;
    attr:pthread_attr_t;
    pthread_attr_init(attr.ptr);
    pthread_attr_setstacksize(attr.ptr, 128 << 10);
    i:Int;
    for (i in 0 until  100) {
        if (pthread_create(th.ptr, attr.ptr, fn, arg) == 0) {
            pthread_attr_destroy(attr.ptr);
            return;
        }
        if (errno == EAGAIN) {
            usleep(50);
            continue;
        }
        break;
    }
    exit(1);
}

typedef struct {
    state:Int;
} event_t;

static void event_init(event_t *ev) {
 ev.pointed.state  = 0;
}

static void event_reset(event_t *ev) {
 ev.pointed.state  = 0;
}

static void event_set(event_t *ev) {
    if ( ev.pointed.state )
        exit(1);
    __atomic_store_n(ev. ptr.pointed.state , 1, __ATOMIC_RELEASE);
    syscall(SYS_futex, ev. ptr.pointed.state ,  FUTEX_WAKE or FUTEX_PRIVATE_FLAG );
}

static void event_wait(event_t *ev) {
    while (!__atomic_load_n(ev. ptr.pointed.state , __ATOMIC_ACQUIRE))
        syscall(SYS_futex, ev. ptr.pointed.state ,  FUTEX_WAIT or FUTEX_PRIVATE_FLAG , 0, 0);
}

static event_isset:Int(event_t *ev) {
    return __atomic_load_n(ev. ptr.pointed.state , __ATOMIC_ACQUIRE);
}

static event_timedwait:Int(event_t *ev, timeout:uint64_t) {
    start:uint64_t = current_time_ms();
    now:uint64_t = start;
    for (;;) {
        remain:uint64_t = timeout - (now - start);
        ts:timespec;
        ts.tv_sec = remain / 1000;
        ts.tv_nsec = (remain % 1000) * 1000 * 1000;
        syscall(SYS_futex, ev. ptr.pointed.state ,  FUTEX_WAIT or FUTEX_PRIVATE_FLAG , 0, ts.ptr);
        if (__atomic_load_n(ev. ptr.pointed.state , __ATOMIC_RELAXED))
            return 1;
        now = current_time_ms();
        if (now - start > timeout)
            return 0;
    }
}

static write_file:Boolean(file:String, const what:CPointer<ByteVar>, ...) {
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

static void kill_and_wait(pid:Int, int *status) {
    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    i:Int;
    for (i in 0 until  100) {
        if (waitpid(-1, status,  WNOHANG or __WALL ) == pid)
            return;
        usleep(1000);
    }
    DIR *dir = opendir("/sys/fs/fuse/connections");
    if (dir) {
        for (;;) {
            ent:CPointer<dirent> = readdir(dir);
            if (!ent)
                break;
            if (strcmp( ent.pointed.d_name , ".") == 0 || strcmp( ent.pointed.d_name , "..") == 0)
                continue;
            char abort[300];
            snprintf(abort, sizeof(abort), "/sys/fs/fuse/connections/%s/abort",
 ent.pointed.d_name );
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

#define SYZ_HAVE_SETUP_TEST 1

static void setup_test() {
    prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
    setpgrp();
    write_file("/proc/self/oom_score_adj", "1000");
}

t:thread_ {
    created:Int, call;
    ready:event_t, done;
};

static s:thread:thread_t[16];

static void execute_call(call:Int);

static running:Int;

static thr:CPointer<ByteVar> (arg:CPointer<ByteVar> ) {
    th:CPointer<thread_t> = (t:thread_ *) arg;
    for (;;) {
        event_wait(th. ptr.pointed.ready );
        event_reset(th. ptr.pointed.ready );
        execute_call( th.pointed.call );
        __atomic_fetch_sub(running.ptr, 1, __ATOMIC_RELAXED);
        event_set(th. ptr.pointed.done );
    }
    return 0;
}

static void execute_one(void) {
    i:Int, call, thread;
    for (call in 0 until  3) {
        for (thread = 0; thread < (int) (sizeof(threads) / sizeof(threads[0]));
             thread++) {
            th:CPointer<thread_t> = threads.ptr[thread];
            if (! th.pointed.created ) {
 th.pointed.created  = 1;
                event_init(th. ptr.pointed.ready );
                event_init(th. ptr.pointed.done );
                event_set(th. ptr.pointed.done );
                thread_start(thr, th);
            }
            if (!event_isset(th. ptr.pointed.done ))
                continue;
            event_reset(th. ptr.pointed.done );
 th.pointed.call  = call;
            __atomic_fetch_add(running.ptr, 1, __ATOMIC_RELAXED);
            event_set(th. ptr.pointed.ready );
            event_timedwait(th. ptr.pointed.done , 45);
            break;
        }
    }
    for (i in 0 until  100 && __atomic_load_n(running.ptr, __ATOMIC_RELAXED))
        sleep_ms(1);
}

static void execute_one(void);

#define WAIT_FLAGS __WALL

static void loop(void) {
    iter:Int;
    for (iter = 0;; iter++) {
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

uint64_t r[1] = {0xffffffffffffffff};

fun execute_call(call:Int):Unit{
    :Longres
    when  (call)  {
        0 -> 
            *(uint32_t *) 0x20000040 = 0;
            *(uint32_t *) 0x20000044 = 0;
            *(uint32_t *) 0x20000048 = 0;
            *(uint32_t *) 0x2000004c = 0;
            *(uint32_t *) 0x20000050 = 0;
            *(uint32_t *) 0x20000054 = 0;
            *(uint32_t *) 0x20000058 = 0;
            *(uint32_t *) 0x2000005c = 0;
            *(uint32_t *) 0x20000060 = 0;
            *(uint32_t *) 0x20000064 = 0;
            *(uint32_t *) 0x20000068 = 0;
            *(uint32_t *) 0x2000006c = 0;
            *(uint32_t *) 0x20000070 = 0;
            *(uint32_t *) 0x20000074 = 0;
            *(uint32_t *) 0x20000078 = 0;
            *(uint32_t *) 0x2000007c = 0;
            *(uint32_t *) 0x20000080 = 0;
            *(uint32_t *) 0x20000084 = 0;
            *(uint64_t *) 0x20000088 = 0;
            *(uint32_t *) 0x20000090 = 0;
            *(uint32_t *) 0x20000094 = 0;
            *(uint32_t *) 0x20000098 = 0;
            *(uint32_t *) 0x2000009c = 0;
            *(uint32_t *) 0x200000a0 = 0;
            *(uint32_t *) 0x200000a4 = 0;
            *(uint32_t *) 0x200000a8 = 0;
            *(uint32_t *) 0x200000ac = 0;
            *(uint64_t *) 0x200000b0 = 0;
            res = __sys_io_uring_setup(0x64, (s:io_uring_param *) 0x20000040UL);
            if (res != -1)
                r[0] = res;
            break;
        1 -> 
            __sys_io_uring_register((long) r[0], 0, 0, 0);
            break;
        2 -> 
            __sys_io_uring_register((long) r[0], 0, 0, 0);
            break;
    }
}

static void sig_int(sig:Int) {
    exit(0);
}

int main(argc:Int, argv:CPointer<ByteVar>[]) {
    if (argc > 1)
        return 0;
    signal(SIGINT, sig_int);
    mmap((void *) 0x20000000, 0x1000000, 3, 0x32, -1, 0);
    signal(SIGALRM, sig_int);
    alarm(5);

    loop();
    return 0;
}