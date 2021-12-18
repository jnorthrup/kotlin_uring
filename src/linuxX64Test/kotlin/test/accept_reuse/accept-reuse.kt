package test.accept_reuse

import kotlinx.cinterop.*
import linux_uring.*
import linux_uring.AF_INET
import linux_uring.AF_INET6
import linux_uring.AF_UNSPEC
import linux_uring.SOCK_STREAM
import linux_uring.SOL_SOCKET
import linux_uring.SOMAXCONN
import linux_uring.SO_REUSEADDR
import linux_uring.SO_REUSEPORT
import linux_uring.exit
import linux_uring.fprintf
import linux_uring.listen
import linux_uring.memset
import linux_uring.perror
import linux_uring.setsockopt
import linux_uring.sockaddr
import linux_uring.socket
import linux_uring.stderr
import linux_uring.stdout
import platform.posix.*
import platform.posix.socklen_tVar
import simple.CZero.nz
import simple.alloc
import test.cat.io_uring_enter

class AcceptReuse : NativeFreeablePlacement by nativeHeap {
    var io_uring: io_uring = alloc()


    fun submit_sqe(): Int {
        val __FUNCTION__ = "submit_sqe"

        val sq: CPointer<io_uring_sq> = io_uring.sq.ptr
        val tail = sq.pointed.ktail!!

        sq.pointed.array!![tail.pointed.value.and(sq.pointed.kring_mask!!.pointed.value).toInt()] = 0.toUInt()
        io_uring_smp_store_release_tail(sq, tail.pointed.value.inc())

        return io_uring_enter(io_uring.ring_fd, 1u, 0u, 0u, null)
    }

    fun main(): Int {
        val __FUNCTION__ = "main"

        val addr_info_list = allocPointerTo<addrinfo>()
        val params: io_uring_params = alloc()
        val hints: addrinfo = alloc()
        val v = sizeOf<sockaddr>().toUInt()
        val sa_size: socklen_tVar = alloc(v)
        memset(params.ptr, 0, sizeOf<io_uring_params>().toULong())
        var ret = io_uring_queue_init_params(4, io_uring.ptr, params.ptr)
        if (ret.nz) {
            fprintf(stderr, "io_uring_init_failed: %d\n", ret)
            return 1
        }
        if (!(params.features and IORING_FEAT_SUBMIT_STABLE).nz) {
            fprintf(stdout, "FEAT_SUBMIT_STABLE not there, skipping\n")
            return 0
        }

        memset(hints.ptr, 0, sizeOf<addrinfo>().toULong())
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        hints.ai_flags = AI_PASSIVE or AI_NUMERICSERV

        ret = getaddrinfo(null, "12345", hints.ptr, addr_info_list.ptr)
        if (ret < 0) {
            perror("getaddrinfo")
            return 1
        }
        val addr_info: CPointerVar<addrinfo> = alloc()
        var ai: CPointer<addrinfo>? = addr_info_list.value
        while (ai != null) {
            if (ai.pointed.ai_family == AF_INET || ai.pointed.ai_family == AF_INET6) {
                addr_info.value = ai
                break
            }
            ai = ai.pointed.ai_next
        }
/*
        if (!addr_info ) {
            fprintf(stderr, "addrinfo not found\n")
            return 1
        }
*/

        val sqe = io_uring.sq.sqes?.get(0)!!// .ptr
        var listen_fd: Int

        ret = socket(addr_info.pointed!!.ai_family, SOCK_STREAM,
            addr_info.pointed!!.ai_protocol)
        if (ret < 0) {
            perror("socket")
            return 1
        }
        listen_fd = ret

        val optionValue: IntVar = alloc(1)
        setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, optionValue.ptr, sizeOf<IntVar>().toUInt())
        setsockopt(listen_fd, SOL_SOCKET, SO_REUSEPORT, optionValue.ptr, sizeOf<IntVar>().toUInt())

        ret = bind(listen_fd, addr_info.pointed!!.ai_addr, addr_info.pointed!!.ai_addrlen)
        if (ret < 0) {
            perror("bind")
            return 1
        }

        ret = listen(listen_fd, SOMAXCONN)
        if (ret < 0) {
            perror("listen")
            return 1
        }
        val sa = alloc<sockaddr>()
        memset(sa.ptr, 0, sizeOf<sockaddr>().toULong())

        io_uring_prep_accept(sqe.ptr, listen_fd, sa.ptr, sa_size.ptr, 0)
        sqe.user_data = 1u
        ret = submit_sqe()
        if (ret != 1) {
            fprintf(stderr, "submit failed: %d\n", ret)
            return 1
        }

        ret = socket(addr_info.pointed!!.ai_family, SOCK_STREAM, addr_info.pointed!!.ai_protocol)
        if (ret < 0) {
            perror("socket")
            return 1
        }
        val connect_fd: Int = ret

        io_uring_prep_connect(sqe.ptr,
            connect_fd,
            addr_info.pointed!!.ai_addr!!.reinterpret<sockaddr>() as CValuesRef<sockaddr>,
            addr_info.pointed!!.ai_addrlen)
        sqe.user_data = 2UL
        ret = submit_sqe()
        if (ret != 1) {
            fprintf(stderr, "submit failed: %d\n", ret)
            return 1
        }
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        for (i in 0 until 2) {
            ret = io_uring_wait_cqe(io_uring.ptr, cqe.ptr)
            if (ret.nz) {
                fprintf(stderr, "io_uring_wait_cqe: %d\n", ret)
                return 1
            }
            when (cqe.pointed!!.user_data) {
                1UL ->
                    if (cqe.pointed!!.res < 0) {
                        fprintf(stderr, "accept failed: %d\n", cqe.pointed!!.res)
                        return 1
                    }
                2UL ->
                    if (cqe.pointed!!.res.nz) {
                        fprintf(stderr, "connect failed: %d\n", cqe.pointed!!.res)
                        return 1
                    }
                else -> io_uring_cq_advance(io_uring.ptr, 1)
            }
        }
        freeaddrinfo(addr_info_list.value)
        io_uring_queue_exit(io_uring.ptr)
        return 0
    }


}

fun main() {
    exit(__status = AcceptReuse().main())

}