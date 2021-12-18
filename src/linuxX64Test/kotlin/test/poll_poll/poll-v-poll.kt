package test.poll_poll


import kotlinx.cinterop.*
import linux_uring.*
import platform.linux.*
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.poll
import platform.posix.pollfd
import simple.CZero.nz
import simple.d
import simple.m

@ExperimentalUnsignedTypes
class PollPoll : NativeFreeablePlacement by nativeHeap {


    fun do_pipe_pollin_test(ring: CPointer<io_uring>): Int = memScoped {
        val __FUNCTION__ = "do_pipe_pollin_test"

        val threads = Array<pthread_tVar>(2) { alloc() }
        val ret: Int
        val buf: ByteVar = alloc()
        val pipe1 = IntArray(2)



        if (pipe(pipe1.refTo(0)) < 0) {
            perror("pipe")
            return 1
        }
        val td: thread_data = thread_data(

            ring = ring.pointed.reinterpret(),
            fd = pipe1[0],
            events = POLLIN,
            test = __FUNCTION__,
        )
        pthread_create(threads[1].ptr, null, staticCFunction(::iou_poll), StableRef.create(td).asCPointer())
        pthread_create(threads[0].ptr, null, staticCFunction(::poll_pipe), StableRef.create(td).asCPointer())
        usleep(100000)

        buf.value = 0x89.toByte()
        ret = write(pipe1[1], buf.ptr, sizeOf<ByteVar>().toULong()).toInt()
        if (ret != sizeOf<ByteVar>().toInt()) {
            fprintf(stderr, "write failed: %d\n", ret)
            return 1
        }

        pthread_join(threads[0].value, null)
        pthread_join(threads[1].value, null)

        if (td.out[0] != td.out[1]) {
            fprintf(stderr, "%s: res %x/%x differ\n", __FUNCTION__,
                td.out[0], td.out[1])
            return 1
        }
        return 0
    }

    fun do_pipe_pollout_test(ring: CPointer<io_uring>): Int = memScoped {
        val __FUNCTION__ = "do_pipe_pollout_test"

        val pipe1 = IntArray(2)
        if (pipe(pipe1.refTo(0)) < 0) {
            perror("pipe")
            return 1
        }

        val td: thread_data = thread_data(
            ring = ring.pointed,
            fd = pipe1[1],
            events = POLLOUT,
            test = __FUNCTION__,
        )
        val threads = Array<pthread_tVar>(2) { alloc() }
        val ret: Int
        val buf: ByteVar = alloc()


        pthread_create(threads[0].ptr, null, staticCFunction(::poll_pipe), StableRef.create(td).asCPointer())
        pthread_create(threads[1].ptr, null, staticCFunction(::iou_poll), StableRef.create(td).asCPointer())
        usleep(100000)

        buf.value = 0x89.toByte()
        ret = write(pipe1[1], buf.ptr, sizeOf<ByteVar>().toULong()).toInt()
        if (ret != sizeOf<ByteVar>().toInt()) {
            fprintf(stderr, "write failed: %d\n", ret)
            return 1
        }

        pthread_join(threads[0].value, null)
        pthread_join(threads[1].value, null)

        if (td.out[0] != td.out[1]) {
            fprintf(stderr, "%s: res %x/%x differ\n", __FUNCTION__,
                td.out[0], td.out[1])
            return 1
        }

        return 0
    }

    fun do_fd_test(ring: CPointer<io_uring>, fname: String, events: Int): Int {
        val __FUNCTION__ = "do_fd_test"
        val threads = ULongArray(2)

        val fd: Int = open(fname, O_RDONLY)
        if (fd < 0) {
            perror("open")
            return 1
        }
        val td = thread_data(

            ring = ring.pointed,
            fd = fd,
            events = events,
            test = __FUNCTION__,
        )
        pthread_create(threads.refTo(0), null, staticCFunction(::poll_pipe), StableRef.create(td).asCPointer())
        pthread_create(threads.refTo(1), null, staticCFunction(::iou_poll), StableRef.create(td).asCPointer())

        pthread_join(threads[0], null)
        pthread_join(threads[1], null)

        if (td.out[0] != td.out[1]) {
            fprintf(stderr, "%s: res %x/%x differ\n", __FUNCTION__,
                td.out[0], td.out[1])
            return 1
        }

        return 0
    }

    fun iou_epoll_ctl(
        ring: CPointer<io_uring>, epfd: Int, fd: Int,
        ev: CPointer<epoll_event>,
    ): Int {

        val cqe: CPointerVar<io_uring_cqe> = alloc()
        var ret: Int

        val sqe = io_uring_get_sqe(ring)!!


        io_uring_prep_epoll_ctl(sqe, epfd, fd, EPOLL_CTL_ADD, ev)

        ret = io_uring_submit(ring)
        if (ret != 1) {
            fprintf(stderr, "submit: %d\n", ret)
            return 1
        }

        ret = io_uring_wait_cqe(ring, cqe.ptr)
        if (ret.nz) {
            fprintf(stderr, "wait_cqe: %d\n", ret)
            return 1
        }

        ret = cqe.pointed!!.res
        io_uring_cqe_seen(ring, cqe.value)
        return ret
    }

    fun do_test_epoll(ring: CPointer<io_uring>, iou_epoll_add: Int): Int {
        val __FUNCTION__ = "do_test_epoll"

        val ev: epoll_event = alloc()

        val threads = ULongArray(2)
        var ret: Int
        val pipe1 = IntArray(2)
        if (pipe(pipe1.refTo(0)) < 0) {
            perror("pipe")
            return 1
        }
        val fd = epoll_create1(0)
        if (fd < 0) {
            perror("epoll_create")
            return 1
        }



        ev.events = EPOLLIN.toUInt()
        ev.data.fd = pipe1[0]

        if (!iou_epoll_add.nz) {
            if (epoll_ctl(fd, EPOLL_CTL_ADD, pipe1[0], ev.ptr) < 0) {
                perror("epoll_ctrl")
                return 1
            }
        } else {
            ret = iou_epoll_ctl(ring, fd, pipe1[0], ev.ptr)
            if (ret == -EINVAL) {
                fprintf(stdout, "epoll not supported, skipping\n")
                return 0
            } else if (ret < 0) {
                return 1
            }
        }
        val td: thread_data = thread_data(
            ring = ring.pointed,
            fd = fd,
            events = POLLIN,
            test = __FUNCTION__)

        pthread_create(threads.refTo(0), null, staticCFunction(::iou_poll), StableRef.create(td).asCPointer())
        pthread_create(threads.refTo(1), null, staticCFunction(::epoll_wait_fn), StableRef.create(td).asCPointer())
        usleep(100000)

        val buf: ByteVarOf<Byte> = alloc { value = 0x89.toByte() }
        ret = write(pipe1[1], buf.ptr, sizeOf<ByteVar>().toULong()).toInt()
        if (ret != sizeOf<ByteVar>().toInt()) {
            fprintf(stderr, "write failed: %d\n", ret)
            return 1
        }

        pthread_join(threads[0], null)
        pthread_join(threads[1], null)
        return 0
    }

    fun main(argc: Int, argv: Array<String>): Int {
        val __FUNCTION__ = "test.socket_eagain.test.socket_rw.main"

        val ring: io_uring = alloc()


        var ret = io_uring_queue_init(1, ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "ring setup failed\n")
            return 1
        }

        ret = do_pipe_pollin_test(ring.ptr)
        if (ret.nz) {
            fprintf(stderr, "pipe pollin test failed\n")
            return ret
        }

        ret = do_pipe_pollout_test(ring.ptr)
        if (ret.nz) {
            fprintf(stderr, "pipe pollout test failed\n")
            return ret
        }

        ret = do_test_epoll(ring.ptr, 0)
        if (ret.nz) {
            fprintf(stderr, "epoll test 0 failed\n")
            return ret
        }

        ret = do_test_epoll(ring.ptr, 1)
        if (ret.nz) {
            fprintf(stderr, "epoll test 1 failed\n")
            return ret
        }
        val fname: String = if (argc > 0)
            argv[0]
        else
            "/etc/sysctl.conf"
        m d "$__FUNCTION__ fname=$fname"
        ret = do_fd_test(ring.ptr, fname, POLLIN)
        m d "$__FUNCTION__ $ret = do_fd_test(ring.ptr, fname, POLLIN)"

        if (ret.nz) {
            fprintf(stderr, "fd test IN failed\n")
            return ret
        }

        ret = do_fd_test(ring.ptr, fname, POLLOUT)
        if (ret.nz) {
            fprintf(stderr, "fd test OUT failed\n")
            return ret
        }

        ret = do_fd_test(ring.ptr, fname, POLLOUT or POLLIN)
        m d "$__FUNCTION__ $ret = do_fd_test(ring.ptr, fname, POLLOUT or POLLIN)"
        if (ret.nz) {
            fprintf(stderr, "fd test  IN or OUT  failed\n")
            return ret
        }

        m d "poll_poll $__FUNCTION__ returning 0"
        return 0
    }
}

fun main(args: Array<String>) {
    exit(PollPoll().main(args.size, args))
}

fun iou_poll(data: COpaquePointer?): COpaquePointer? = memScoped {
    val __FUNCTION__ = "iou_poll"
    val td: thread_data = data!!.asStableRef<thread_data>().get()
    m d "$__FUNCTION__ $td: thread_data = data!!.asStableRef<thread_data>().get()"

    val cqe: CPointerVar<io_uring_cqe> = alloc()
    var ret: Int

    val sqe = io_uring_get_sqe(td.ring.ptr)

    io_uring_prep_poll_add(sqe, td.fd, td.events.toUInt())
    ret = io_uring_submit(td.ring.ptr)
    err@ for (__err in 0..0) {
        if (ret != 1) {
            fprintf(stderr, "submit got %d\n", ret)
            break@err
        }

        ret = io_uring_wait_cqe(td.ring.ptr, cqe.ptr)
        if (ret.nz) {
            fprintf(stderr, "wait_cqe: %d\n", ret)
            break@err
        }

        td.out[0] = cqe.pointed!!.res and 0x3f
        io_uring_cqe_seen(td.ring.ptr, cqe.value)
        m d "$__FUNCTION__         return success"
        return null
    }
    m d "$__FUNCTION__ err"
    return 1L.toCPointer<COpaque>()
}

fun poll_pipe(data: COpaquePointer?): COpaquePointer? = memScoped {
    val __FUNCTION__ = "poll_pipe"
    val td: thread_data = data!!.asStableRef<thread_data>().get()
    m d "$__FUNCTION__ $td: thread_data = data!!.asStableRef<thread_data>().get()"
    val pfd: pollfd = alloc {
        fd = td.fd
        events = td.events.toShort()
    }
    val ret: Int = poll(pfd.ptr, 1, -1)
    m d "$__FUNCTION__ $ret: Int = poll(pfd.ptr, 1, -1)"

    if (ret < 0) perror("poll")

    td.out[1] = pfd.revents.toInt()
    m d "$__FUNCTION__ td.out[1] = ${pfd.revents.toInt()}"
    return null
}

fun epoll_wait_fn(data: COpaquePointer?): COpaquePointer? = memScoped {
    val __FUNCTION__ = "epoll_wait_fn"

    val td: thread_data = data!!.asStableRef<thread_data>().get()
    m d "$__FUNCTION__ $td: thread_data = data!!.asStableRef<thread_data>().get()"
    val ev: epoll_event = alloc()
    err@ for (__err in 0..0) {
        if (epoll_wait(td.fd, ev.ptr, 1, -1) < 0) {
            perror("epoll_wait")
            break@err
        }
        return null
    }
    return 1L.toCPointer()
}
