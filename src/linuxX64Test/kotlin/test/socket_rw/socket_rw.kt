package test.socket_rw

import kotlinx.cinterop.*
import linux_uring.*
import simple.CZero.nz
import simple.HasPosixErr.Companion.posixRequires
import simple.d

/* SPDX-License-Identifier: MIT */
/*
 * Check that a readv on a socket queued before a writev doesn't hang
 * the processing.
 *
 * From Hrvoje Zeba <zeba.hrvoje@gmail.com>
 */
//include <stdio.h>
//include <stdlib.h>
//include <stdint.h>
//include <posixRequires.h>

//include <errno.h>
//include <fcntl.h>
//include <unistd.h>
//include <sys/socket.h>
//include <sys/un.h>
//include <netinet/tcp.h>
//include <netinet/in.h>
//include <arpa/inet.h>

//include "liburing.h"
fun main() {
    exit(main1())
}

fun main1(): Int = memScoped {
    var __FUNCTION__ = "test.socket_rw.main"

    val p_fd = IntArray(2)
    val val1: int32_tVar = alloc { value = 1 }
    val addr: sockaddr_in = alloc()

    srand(getpid().toUInt())

    val recv_s0 = socket(AF_INET, SOCK_STREAM or SOCK_CLOEXEC, IPPROTO_TCP)
    var ret = setsockopt(recv_s0, SOL_SOCKET, SO_REUSEPORT, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires(ret != -1) { "setsockopt(recv_s0, SOL_SOCKET, SO_REUSEPORT, val1.ptr, sizeOf<int32_tVar>( ));" }
    ret = setsockopt(recv_s0, SOL_SOCKET, SO_REUSEADDR, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires(ret != -1) { "setsockopt(recv_s0, SOL_SOCKET, SO_REUSEADDR, val1.ptr, sizeOf<int32_tVar>( ));" }

    addr.sin_family = AF_INET.toUShort()
    addr.sin_addr.s_addr = inet_addr("127.0.0.1")

    do {
        addr.sin_port = htons(((rand() % 61440).toUShort() + 4096.toUShort()).toUShort())
        ret = bind(recv_s0, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr>().toUInt())
        if (!ret.nz)
            break
        if (errno != EADDRINUSE) {
            perror("bind")
            exit(1)
        }
    } while (1.nz)
    ret = listen(recv_s0, 128)
    posixRequires(ret != -1) { "ret = listen(recv_s0, 128)" }


    p_fd[1] = socket(AF_INET, SOCK_STREAM or SOCK_CLOEXEC, IPPROTO_TCP)

    val1.value = 1
    ret = setsockopt(p_fd[1], IPPROTO_TCP, TCP_NODELAY, val1.ptr, sizeOf<int32_tVar>().toUInt())
    posixRequires(ret != -1) { " ret = setsockopt(p_fd[1], IPPROTO_TCP, TCP_NODELAY, val1.ptr, sizeOf<int32_tVar>( ));" }

    val flags: platform.posix.int32_tVar = alloc { value = fcntl(p_fd[1], F_GETFL, 0); }
    posixRequires(flags.value != -1) { " flags:int32_t = fcntl(p_fd[1], F_GETFL, 0);" }

    flags.value = flags.value.or(O_NONBLOCK)
    ret = fcntl(p_fd[1], F_SETFL, flags.value)
    posixRequires(ret != -1) { " ret = fcntl(p_fd[1], F_SETFL, flags);" }

    ret = connect(p_fd[1], addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr>().toUInt())
    posixRequires(ret == -1) { " ret = connect(p_fd[1], (struct sockaddr *) addr.ptr, sizeof(addr));" }

    flags.value = fcntl(p_fd[1], F_GETFL, 0)
    posixRequires(flags.value != -1) { "  flags = fcntl(p_fd[1], F_GETFL, 0);" }

    flags.value = flags.value and O_NONBLOCK.inv()
    ret = fcntl(p_fd[1], F_SETFL, flags.value)
    posixRequires(ret != -1) { " ret = fcntl(p_fd[1], F_SETFL, flags);" }

    p_fd[0] = accept(recv_s0, null, null)
    posixRequires(p_fd[0] != -1) { " p_fd[0] = accept(recv_s0, NULL, NULL);" }

    while (1.nz) {
        val code: int32_tVar = alloc()
        val code_len: socklen_tVar = alloc { value = sizeOf<int32_tVar>().toUInt() }

        ret = getsockopt(p_fd[1], SOL_SOCKET, SO_ERROR, code.ptr, code_len.ptr)
        posixRequires(ret != -1) { "ret = getsockopt(p_fd[1], SOL_SOCKET, SO_ERROR, code.ptr, code_len.ptr)" }

        if (!code.value.nz) break
    }

    val m_io_uring: io_uring = alloc()

    ret = io_uring_queue_init(32, m_io_uring.ptr, 0)
    posixRequires(ret >= 0) { "    ret = io_uring_queue_init(32, m_io_uring.ptr, 0)" }

    val recv_buff = ByteArray(128)
    val send_buff = ByteArray(128)

    run {
        val iov = allocArray<iovec>(1)

        iov[0].iov_base = recv_buff.refTo(0).getPointer(this)
        iov[0].iov_len = recv_buff.size.toULong()

        val sqe = io_uring_get_sqe(m_io_uring.ptr)
        posixRequires(sqe != null) { "sqe  = io_uring_get_sqe(m_io_uring.ptr)" }

        io_uring_prep_readv(sqe, p_fd[0], iov, 1, 0)
    }

    run {
        val iov = allocArray<iovec>(1)

        iov[0].iov_base = send_buff.refTo(0).getPointer(this)
        iov[0].iov_len = (send_buff).size.toULong()

        val sqe = io_uring_get_sqe(m_io_uring.ptr)
        posixRequires(sqe != null) { "        var sqe = io_uring_get_sqe(m_io_uring.ptr)" }

        io_uring_prep_writev(sqe, p_fd[1], iov, 1, 0)
    }

    ret = io_uring_submit_and_wait(m_io_uring.ptr, 2)
    posixRequires(ret != -1) { "ret = io_uring_submit_and_wait(m_io_uring.ptr, 2)" }


    val count: uint32_tVar = alloc()

    while (count.value != 2u) {

        ret = io_uring_do_for_each_cqe2(m_io_uring.ptr, count.ptr, staticCFunction(::xxyyyyyx))
        if (ret.nz) exit(1)

        posixRequires(count.value <= 2u) { "ret=io_uring_do_for_each_cqe2(m_io_uring.ptr, count.ptr, staticCFunction(::xxyyyyyx))" }
        io_uring_cq_advance(m_io_uring.ptr, count.value)
    }

    io_uring_queue_exit(m_io_uring.ptr)
    return 0
}

@Suppress("UNUSED_PARAMETER")
fun xxyyyyyx(_ring: CPointer<io_uring>?, _head: UInt, cqe: CPointer<io_uring_cqe>?, ccount: COpaquePointer?): Int {
    if (cqe?.pointed?.res != 128) return -1
    val pointed = ccount?.reinterpret<uint32_tVar>()?.pointed!!
    pointed.value++


    nativeHeap.d("count  ${pointed.value}")
    return 0
}