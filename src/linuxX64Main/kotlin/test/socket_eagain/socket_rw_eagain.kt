package test.socket_eagain

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.include.asString
import platform.posix.int32_tVar
import platform.posix.sockaddr
import simple.CZero.nz
import simple.HasPosixErr.Companion.posixRequires
import simple.d
import simple.m
import kotlin.random.Random
import kotlin.random.nextUInt


/* SPDX-License-Identifier: MIT */
/*
 * Check that a readv on a nonblocking socket queued before a writev doesn't
 * wait for data to arrive.
 */
//include <stdio.h>
//include <stdlib.h>
//include <stdint.h>
//include <posixRequires(.h>){"condition .h>"}

//include <errno.h>
//include <fcntl.h>
//include <unistd.h>
//include <sys/socket.h>
//include <sys/un.h>
//include <netinet/tcp.h>
//include <netinet/in.h>
//include <arpa/inet.h>

//include "liburing.h"
fun main(args: Array<String>) {
    exit(main1())
}

fun main1(): Int = memScoped {
    val __FUNCTION__ = "test.socket_rw.main"

//    var recv_s0: int32_tVar = alloc()
    val val1: int32_tVar = alloc { value = 1 /* = kotlin.Int */ }

    srand(getpid().toUInt())
    val recv_s0: Int = socket(AF_INET, SOCK_STREAM or SOCK_CLOEXEC, IPPROTO_TCP)

    var ret = setsockopt(recv_s0, SOL_SOCKET, SO_REUSEPORT, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires((ret != -1)) { "condition (ret != -1)setsockopt(recv_s0, SOL_SOCKET, SO_REUSEPORT, val1.ptr, sizeOf<int32_tVar>().toUInt())" }
    ret = setsockopt(recv_s0, SOL_SOCKET, SO_REUSEADDR, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires((ret != -1)) { "condition (ret != -1)setsockopt(recv_s0, SOL_SOCKET, SO_REUSEADDR, val1.ptr, sizeOf<int32_tVar>().toUInt())" }
    val addr: sockaddr_in = alloc<sockaddr_in> {
        sin_family = AF_INET.toUShort()
        sin_addr.s_addr = inet_addr("127.0.0.1")
    }
    val port = Random.nextUInt(4096u..61440u)


    do {
        addr.sin_port = htons(port.toUShort())
        ret = bind(recv_s0, addr.ptr.reinterpret(), sizeOf<sockaddr>().toUInt())
        if (!ret.nz)
            break
        if (errno != EADDRINUSE) {
            perror("bind")
            exit(1)
        }
    } while (1.nz)

    ret = listen(recv_s0, 128)
    posixRequires((ret != -1)) { "condition (ret != -1) listen(recv_s0, 128)" }
    val p_fd = IntArray(2)

    p_fd[1] = socket(AF_INET, SOCK_STREAM or SOCK_CLOEXEC, IPPROTO_TCP)

    val1.value = 1 /* = kotlin.Int */
    ret = setsockopt(p_fd[1], IPPROTO_TCP, TCP_NODELAY, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires((ret != -1)) { "condition (ret != -1) setsockopt(p_fd[1], IPPROTO_TCP, TCP_NODELAY, val1.ptr, sizeOf<int32_tVar>().toUInt())" }

    val flags: int32_tVar = alloc { value = fcntl(p_fd[1], F_GETFL, 0) /* = kotlin.Int */ }
    posixRequires((flags.value != -1)) { "condition (flags != -1 )fcntl(p_fd[1], F_GETFL, 0)" }

    flags.value = flags.value.or(O_NONBLOCK)
    ret = fcntl(p_fd[1], F_SETFL, flags.value)
    posixRequires((ret != -1)) { "condition (ret != -1)fcntl(p_fd[1], F_SETFL, flags.value)" }

    ret = connect(p_fd[1], addr.ptr.reinterpret(), sizeOf<sockaddr>().toUInt())
    posixRequires((ret == -1)) { "condition (ret == -1)connect(p_fd[1], addr.ptr.reinterpret(), sizeOf<sockaddr>().toUInt());" }

    p_fd[0] = accept(recv_s0, null, null)
    posixRequires((p_fd[0] != -1)) { "condition (p_fd[0] != -1)accept(recv_s0, null, null)" }

    flags.value = fcntl(p_fd[0], F_GETFL, 0.toUInt())
    posixRequires(flags.value != -1) { "condition (flags != -1) fcntl(p_fd[0], F_GETFL, 0.toUInt())" }

    flags.value += O_NONBLOCK
    ret = fcntl(p_fd[0], F_SETFL, flags.value)
    posixRequires((ret != -1)) { "condition (ret != -1)fcntl(p_fd[0], F_SETFL, flags)" }

    while (1.nz) {
        val code: int32_tVar = alloc()
        val code_len: socklen_tVar = alloc {
            value = sizeOf<int32_tVar>().toUInt()
        }

        ret = getsockopt(p_fd[1], SOL_SOCKET, SO_ERROR, code.ptr, code_len.ptr)
        posixRequires((ret != -1)) { "condition (ret != -1) getsockopt(p_fd[1], SOL_SOCKET, SO_ERROR, code.ptr, code_len.ptr)" }

        if (!code.value.nz)
            break
    }

    val m_io_uring: io_uring = alloc()
    val p: io_uring_params = alloc()

    ret = io_uring_queue_init_params(32, m_io_uring.ptr, p.ptr)
    posixRequires((ret >= 0)) { "condition (ret >= 0) io_uring_queue_init_params(32, m_io_uring.ptr, p.ptr)" }

    if ((p.features and IORING_FEAT_FAST_POLL).nz) {
        m d "bailing p.features contains IORING_FEAT_FAST_POLL -- ${p.asString()}"
        return 0
    }

    val recv_buff = ByteArray(128)
    val send_buff = ByteArray(128)
    run {
        val iov = alloc<iovec> {
            iov_base = recv_buff.refTo(0).getPointer(this@memScoped)
            iov_len = (recv_buff).size.toULong()
        }

        val sqe = io_uring_get_sqe(m_io_uring.ptr)
        posixRequires((sqe != null)) { "condition (sqe != NULL) io_uring_get_sqe(m_io_uring.ptr)" }

        io_uring_prep_readv(sqe, p_fd[0], iov.ptr, 1, 0)
        sqe?.pointed?.user_data = 1.toULong()
    }

    run {

        val iov = alloc<iovec> {
            iov_base = send_buff.refTo(0).getPointer(this@memScoped)
            iov_len = (send_buff).size.toULong()
        }

        val sqe = io_uring_get_sqe(m_io_uring.ptr)
        posixRequires((sqe != null)) { "condition (sqe != NULL)" }

        io_uring_prep_writev(sqe, p_fd[1], iov.ptr, 1, 0)
        sqe?.pointed?.user_data = 2.toULong()

    }

    ret = io_uring_submit_and_wait(m_io_uring.ptr, 2)
    posixRequires((ret != -1)) { "condition (ret != -1)" }

//    var cqe: CPointerVar<io_uring_cqe> = alloc()
//    var head: uint32_tVar = alloc()
    val count: uint32_tVar = alloc()
    err@ for (__err in 0..0) {
        while (count.value != 2u) {
            ret = io_uring_do_for_each_cqe2(m_io_uring.ptr, count.ptr, staticCFunction(::xxxxxxx))
            if (ret.nz)
                break@err
            posixRequires((count.value <= 2.toUInt())) { "condition (count <= 2)" }
            io_uring_cq_advance(m_io_uring.ptr, count.value)
        }

        io_uring_queue_exit(m_io_uring.ptr)
        return 0
    }
    io_uring_queue_exit(m_io_uring.ptr)
    return 1
}

@Suppress("UNUSED_PARAMETER")
fun xxxxxxx(x1: CPointer<io_uring>?, x2: UInt, cqe: CPointer<io_uring_cqe>?, x4: COpaquePointer?): Int {
    val xxxx: CPointer<UIntVarOf<uint32_t /* = kotlin.UInt */>> = x4!!.reinterpret()
    val count: uint32_tVar = (xxxx).pointed
    if (cqe!!.pointed.user_data == 2.toULong() && cqe.pointed.res != 128) {
        fprintf(stderr, "write=%d\n", cqe.pointed.res)
        return -1
    } else if (cqe.pointed.user_data == 1.toULong() && cqe.pointed.res != -EAGAIN) {
        fprintf(stderr, "read=%d\n", cqe.pointed.res)
        return -1
    }
    nativeHeap d "count ${count.value++}"
    return 0
}