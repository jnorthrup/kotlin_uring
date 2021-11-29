/* SPDX-License-Identifier: MIT */
//include <stdint.h>
//include <stdio.h>
//include <stdlib.h>
//include <string.h>
//include <sys/types.h>
//include <sys/wait.h>
//include <unistd.h>
//include <errno.h>

//include "liburing.h"

fun loop(void):Unit{
	val __FUNCTION__="loop"

    i:Int, ret = 0;

    for (i in 0 until  100) {
        ring:io_uring;
        fd:Int;

        memset(ring.ptr, 0, sizeof(ring));
        fd = io_uring_queue_init(0xa4, ring.ptr, 0);
        if (fd >= 0) {
            close(fd);
            continue;
        }
        if (fd != -ENOMEM)
            ret++;
    }
    exit(ret);
}

fun main(argc:Int, argv:CPointerVarOf<CPointer<ByteVar>>):Int{
	val __FUNCTION__="main"

    i:Int, ret, status;

    if (argc > 1)
        return 0;

    for (i in 0 until  12) {
        if (!fork()) {
            loop();
            break;
        }
    }

    ret = 0;
    for (i in 0 until  12) {
        if (waitpid(-1, status.ptr, 0) < 0) {
            perror("waitpid");
            return 1;
        }
        if (WEXITSTATUS(status))
            ret++;
    }

    return ret;
}
