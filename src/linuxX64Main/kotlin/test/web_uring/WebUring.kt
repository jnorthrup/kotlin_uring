package test.web_uring

import kotlinx.cinterop.*
import linux_uring.*
import platform.posix.size_t
import simple.*
import simple.CZero.nz
import simple.PosixOpenOpts.Companion.withFlags
import simple.PosixOpenOpts.OpenReadOnly
import simple.PosixOpenOpts.PathSpecific


fun main() {
    exit(WebUring().main())
}

class WebUring : NativeFreeablePlacement by nativeHeap {
    var ring: io_uring = alloc()

    /** Utility function to convert a string to lower case. */
    fun strtolower(str: CPointer<ByteVar>) {
        val __FUNCTION__ = "strtolower"
        m d "$__FUNCTION__ Str: ${str.toKString()}"
        var c = 0
        while (str[c].nz) {
            str[c] = tolower(str[c].toInt()).toByte()
            c++
        }
        m d "$__FUNCTION__ str: ${str.toKString()}"
    }

    /** One function that prints the system call and the error details
    and then exits with error code 1. Non-zero meaning things didn't go well. */
    fun fatal_error(syscall: String) {
        val __FUNCTION__ = "fatal_error"
        perror(syscall)
        exit(1)
    }

    /** Helper function for cleaner looking code. */

    fun zh_malloc(size: size_t): COpaquePointer {
        val __FUNCTION__ = "zh_malloc"
        val buf = alloc(size.toLong(), 8)
        return buf.reinterpret<COpaque>().ptr
    }

    /** This function is responsible for setting up the main listening socket used by the
     * web server. */
    fun setup_listening_socket(port: UShort): Int {
        val __FUNCTION__ = "setup_listening_socket"
        val sock = socket(PF_INET, SOCK_STREAM, 0)
        if (sock == -1)
            fatal_error("socket()")

        val enable: IntVar = alloc(1)
        if (setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, enable.ptr, sizeOf<IntVar>().toUInt()) < 0)
            fatal_error("setsockopt(SO_REUSEADDR)")


        val srv_addr: sockaddr_in = alloc {
            sin_family = AF_INET.toUShort()
            sin_port = htons(port)
            sin_addr.s_addr = htonl(INADDR_ANY)
        }
        /* We bind to a port and turn this socket into a listening
         * socket.
         * */
        if (bind(sock, srv_addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()) < 0)
            fatal_error("bind()")

        if (listen(sock, 10) < 0)
            fatal_error("listen()")

        return (sock)
    }

    fun add_accept_request(
        server_socket: Int,
        client_addr: CPointer<sockaddr_in>,
        client_addr_len: CPointer<socklen_tVar>,
    ): Int {
        val __FUNCTION__ = "add_accept_request"
        val sqe = io_uring_get_sqe(ring.ptr)!!
        io_uring_prep_accept(sqe, server_socket, client_addr.reinterpret(), client_addr_len, 0)
        val req = WebUringRequest()
        req.event_type = EVENT_TYPE_ACCEPT
        io_uring_sqe_set_data(sqe, StableRef.create(req).asCPointer().reinterpret<COpaque>())
        io_uring_submit(ring.ptr)
        return 0
    }

    fun add_read_request(client_socket: Int): Int {
        val __FUNCTION__ = "add_read_request"

        val sqe: CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr)!!
        val req = WebUringRequest(
            client_socket = client_socket,
            event_type = EVENT_TYPE_READ,
            iovec_count = 1).apply {
            iov[0].iov_len = READ_SZ.toULong()
            iov[0].iov_base = zh_malloc(iov[0].iov_len)

        }
        /* Linux kernel 5.5 has support for readv, but not for recv() or read() */
        io_uring_prep_readv(sqe, client_socket, req.iov[0].ptr, 1, 0)
        io_uring_sqe_set_data(sqe, StableRef.create(req).asCPointer().reinterpret<COpaque>())
        io_uring_submit(ring.ptr)
        return 0
    }

    fun add_write_request(pReq: WebUringRequest): Int {
        val __FUNCTION__ = "add_write_request"
        m d "$__FUNCTION__ pReq: $pReq"

        val sqe: CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr)!!
        pReq.event_type = EVENT_TYPE_WRITE
        io_uring_prep_writev(sqe, pReq.client_socket, pReq.iov, pReq.iovec_count.toUInt(), 0UL)
        io_uring_sqe_set_data(sqe, StableRef.create(pReq).asCPointer().reinterpret<COpaque>())
        io_uring_submit(ring.ptr)
        return 0
    }

    /** todo */
    fun _send_static_string_content(str: String, client_socket: Int): Unit = memScoped {
        val __FUNCTION__ = "_send_static_string_content"
        val slen = strlen(str)
        m d "$__FUNCTION__ len: $slen"
        val req = WebUringRequest(iovec_count = 1, client_socket = client_socket).apply {
            val pointer = str.cstr.getPointer(this@memScoped)
            val zhMalloc = zh_malloc(slen)
            iov[0].iov_base = zhMalloc
            iov[0].iov_len = slen
            memcpy(zhMalloc, pointer, slen)
        }
        add_write_request(req)
    }

    /** When ZeroHTTPd encounters any other HTTP method other than GET or POST, this function
     * is used to inform the client. */

    fun handle_unimplemented_method(client_socket: Int): Unit {
        val __FUNCTION__ = "handle_unimplemented_method"

        _send_static_string_content(unimplemented_content, client_socket)
    }

    /** This function is used to send a "HTTP Not Found" code and message to the client in
     * case the file requested is not found. */

    fun handle_http_404(client_socket: Int): Unit {
        val __FUNCTION__ = "handle_http_404"
        m d "$__FUNCTION__"
        _send_static_string_content(http_404_content, client_socket)
    }

    /** Once a static file is identified to be served, this function is used to read the file
     * and write it over the client socket using Linux's sendfile() system call. This saves us
     * the hassle of transferring file buffers from kernel to user space and back. */

    fun copy_file_contents(file_path: CPointer<ByteVar>, file_size: off_t, iov: CPointer<iovec>): Unit {
        val __FUNCTION__ = "copy_file_contents"

        val buf = zh_malloc(file_size.toULong())
        val fd = open(file_path.toKString(), O_RDONLY)
        if (fd < 0)
            fatal_error("open")

        /* We should really check for short reads here */
        val ret: Int = read(fd, buf.reinterpret<COpaque>(), file_size.toULong()).toInt()
        if (ret < file_size) {
            fprintf(stderr, "Encountered a short read.\n")
        }
        close(fd)

        iov.pointed.iov_base = buf
        iov.pointed.iov_len = file_size.toULong()
    }

    /** Simple function to get the file extension of the file that we are about to serve. */

    fun get_filename_ext(filename: String): String? {
        val __FUNCTION__ = "get_filename_ext"
        m d "$__FUNCTION__ fn:$filename"
        val ext =
            filename.indexOfLast { it == '.' }.takeIf { (filename.length - it) >= 3 }?.let { filename.drop(it.inc()) }
        m d "$__FUNCTION__ fn:$filename ext: $ext"
        return ext
    }

    /** Sends the HTTP 200 OK header, the server string, for a few types of files, it can also
     * send the content type based on the file extension. It also sends the content length
     * header. Finally it send a '\r\n' in a line by itself signalling the end of headers
     * and the beginning of any content. */
    fun send_headers(path: String, len: off_t, iov: CPointer<iovec>) = memScoped {
        val __FUNCTION__ = "send_headers"

        m d "$__FUNCTION__ path: $path len: $len iov:len ${iov.pointed.iov_len}"
        val small_case_path = ByteArray(1024)
        val send_buffer1 = ByteArray(1024)
        strcpy(small_case_path.refTo(0).getPointer(this), path)
        strtolower(small_case_path.refTo(0).getPointer(this))

        val str = "HTTP/1.0 200 OK\r\n"
        var slen: ULong = strlen(str)
        iov[0].iov_base = zh_malloc(slen)
        iov[0].iov_len = slen
        memcpy(iov[0].iov_base, str.cstr, slen)
        m d "$__FUNCTION__ iov[0].iov_base=${iov[0].iov_base.toLong().toCPointer<ByteVar>()!!.toKString()}"
        slen = strlen(SERVER_STRING)
        iov[1].iov_base = zh_malloc(slen)
        iov[1].iov_len = slen
        memcpy(iov[1].iov_base, SERVER_STRING.cstr, slen)
        m d "$__FUNCTION__ iov[1].iov_base=${iov[1].iov_base.toLong().toCPointer<ByteVar>()!!.toKString()}"

        /*
         * Check the file extension for certain common types of files
         * on web pages and send the appropriate content-type header.
         * Since extensions can be mixed case like JPG, jpg or Jpg,
         * we turn the extension into lower case before checking.
         * */
        val file_ext = get_filename_ext(small_case_path.toKString())


        val ext = when (file_ext ?: "") {
            "jpg" -> "Content-Type: image/jpeg\r\n"
            "jpeg" -> "Content-Type: image/jpeg\r\n"
            "png" -> "Content-Type: image/png\r\n"
            "gif" -> "Content-Type: image/gif\r\n"
            "htm" -> "Content-Type: text/html\r\n"
            "html" -> "Content-Type: text/html\r\n"
            "js" -> "Content-Type: application/javascript\r\n"
            "css" -> "Content-Type: text/css\r\n"
            "txt" -> "Content-Type: text/plain\r\n"
            else -> "Content-Type: application/binary\r\n"
        }

        slen = strlen(ext)
        iov[2].iov_base = zh_malloc(slen)
        iov[2].iov_len = slen
        memcpy(iov[2].iov_base, ext.cstr, slen)
        m d "$__FUNCTION__ iov[2].iov_base=${iov[2].iov_base.toLong().toCPointer<ByteVar>()!!.toKString()}"

        val send_buffer = send_buffer1.refTo(0).getPointer(this)
        /* Send the content-length header, which is the file size in this case. */
        sprintf(send_buffer, "content-length: %ld\r\n", len)
        slen = strlen(send_buffer.toKString())
        iov[3].iov_base = zh_malloc(slen)
        iov[3].iov_len = slen
        memcpy(iov[3].iov_base, send_buffer, slen)
        m d "$__FUNCTION__ iov[3].iov_base=${iov[3].iov_base.toLong().toCPointer<ByteVar>()!!.toKString()}"

        /* When the browser sees a '\r\n' sequence in a line on its own,
         * it understands there are no more headers. Content may follow. */
        strcpy(send_buffer, "\r\n")
        slen = strlen(send_buffer.toKString())
        iov[4].iov_base = zh_malloc(slen)
        iov[4].iov_len = slen
        memcpy(iov[4].iov_base, send_buffer, slen)
        m d "$__FUNCTION__ iov[4].iov_base=${iov[4].iov_base.toLong().toCPointer<ByteVar>()!!.toKString()}"
    }


    fun handle_get_method(path: CPointer<ByteVar>, client_socket: Int) = memScoped {
        val __FUNCTION__ = "handle_get_method"
        val __src = path.toKStringFromUtf8()
        m d "$__FUNCTION__ s:${client_socket} path: $__src"
        val final_path1 = ByteArray(1024)
        val final_path: CPointer<ByteVarOf<Byte>> = final_path1.refTo(0).getPointer(this)
        /*
         If a path ends in a trailing slash, the client probably wants the index
         file inside of that directory.
         */
        if (path[(strlen(__src) - 1u).toInt()] == '/'.code.toByte()) {
            strcpy(final_path, "public")
            strcat(final_path, __src)
            strcat(final_path, "index.html")
        } else {
            strcpy(final_path, "public")
            strcat(final_path, __src)
        }

        val O_FLAGS = withFlags(PathSpecific, OpenReadOnly/*,NoFollowLinks*/)
        val posixFile = PosixFile(final_path.toKString(), O_FLAGS)
        /*val lnk = posixFile.isLnk*/
        val reg = /*lnk ||*/ posixFile.isReg
        m d "$__FUNCTION__ posixFile (${posixFile.fd} )= PosixFile(${final_path.toKString()}, withFlags(${
            PosixOpenOpts.fromInt(O_FLAGS)
        })) $reg"
        if (reg) {
            val req = WebUringRequest(iovec_count = 6, client_socket = client_socket)
            val fsize = posixFile.size.convert<ULong>().convert<off_t>()
            send_headers(final_path.toKString(), fsize, req.iov)
            copy_file_contents(final_path, fsize, req.iov[5].ptr)
            printf("200 %s %ld bytes\n", final_path, fsize)
            add_write_request(req)
            return

        } else {
            printf("404 Not Found: %s (%s)\n", final_path, path)
            handle_http_404(client_socket)
        }
    }

    /** This function looks at method used and calls the appropriate handler function.
     * Since we only implement GET and POST methods, it calls handle_unimplemented_method()
     * in case both these don't match. This sends an error to the client. */
    fun handle_http_method(method_buffer: CPointer<ByteVar>, client_socket: Int): Unit {
        val __FUNCTION__ = "handle_http_method"
        val saveptr = alloc<CPointerVar<ByteVar>>()

        val method = strtok_r(method_buffer, " ", saveptr.ptr)
        strtolower(method!!.reinterpret())
        val path = strtok_r(null, " ", saveptr.ptr)

        if (strcmp(method.toKString(), "get") == 0) {
            handle_get_method(path!!.reinterpret(), client_socket)
        } else {
            handle_unimplemented_method(client_socket)
        }
    }

    fun get_line(src: CPointer<ByteVar>, dest: CPointer<ByteVar>, dest_sz: Int): Int {
        val __FUNCTION__ = "get_line"

        for (i/*as int */ in 0 until dest_sz) {
            dest[i] = src[i]
            if (src[i] == '\r'.code.toByte() && src[i + 1] == '\n'.code.toByte()) {
                dest[i] = 0.toByte()
                return 0
            }
        }
        return 1
    }

    fun handle_client_request(reqs: COpaquePointer): Int = memScoped {
        val __FUNCTION__ = "handle_client_request"
        val req = reqs.asStableRef<WebUringRequest>().get()
        val http_request = ByteArray(1024)
        /* Get the first line, which will be the request */
        val dest = http_request.refTo(0).getPointer(this)
        if (get_line(req.iov[0].iov_base!!.reinterpret(), dest, 1024).nz) {
            fprintf(stderr, "Malformed request\n")
            exit(1)
        }
        handle_http_method(dest, req.client_socket)
        return 0
    }

    fun server_loop(server_socket: Int): Unit {
        val __FUNCTION__ = "server_loop"
        val cqe: CPointerVar<io_uring_cqe> = alloc()
        val client_addr: sockaddr_in = alloc()
        val client_addr_len: socklen_tVar = alloc(sizeOf<sockaddr_in>().toUInt())

        add_accept_request(server_socket, client_addr.ptr, client_addr_len.ptr)

        while (1.nz) {
            val ret: Int = io_uring_wait_cqe(ring.ptr, cqe.ptr)
            if (ret < 0)
                fatal_error("io_uring_wait_cqe")
            val pReq = cqe.value!!.pointed.user_data.toLong().toCPointer<COpaque>()
            val req = pReq!!.asStableRef<WebUringRequest>().get()
            val res = cqe.pointed!!.res
            if (cqe.pointed!!.res < 0) {
                fprintf(stderr,
                    "Async request failed: %s for event: %d\n",
                    strerror(-res),
                    req.event_type)
                exit(1)
            }

            when (req.event_type) {
                EVENT_TYPE_ACCEPT -> {
                    add_accept_request(server_socket, client_addr.ptr, client_addr_len.ptr)
                    add_read_request(res)
                    free(pReq)
                }

                EVENT_TYPE_READ -> {
                    if (!res.nz) {
                        fprintf(stderr, "Empty request!\n")
                    } else {
                        handle_client_request(pReq)
                        free(req.iov[0].iov_base)
                        free(pReq)
                    }
                }
                EVENT_TYPE_WRITE -> {
                    for (i/*as int */ in 0 until req.iovec_count) free(req.iov[i].iov_base)
                    close(req.client_socket)
                    free(pReq)
                }
            }
            /* Mark this request as processed */
            io_uring_cqe_seen(ring.ptr, cqe.value)
        }
    }


    fun main(): Int {
        val __FUNCTION__ = "main"

        val server_socket: Int = setup_listening_socket(DEFAULT_SERVER_PORT.toUShort())

        signal(SIGINT, staticCFunction(::sigint_handler))
        io_uring_queue_init(QUEUE_DEPTH.toUInt(), ring.ptr, 0)
        server_loop(server_socket)

        return 0
    }

    companion object {
        const val SERVER_STRING = "Server: zerohttpd/0.1\r\n"
        const val DEFAULT_SERVER_PORT = 8000
        const val QUEUE_DEPTH = 256
        const val READ_SZ = 8192
        const val EVENT_TYPE_ACCEPT = 0
        const val EVENT_TYPE_READ = 1
        const val EVENT_TYPE_WRITE = 2


        const val unimplemented_content =
            "HTTP/1.0 400 Bad Request\r\n" +
                    "Content-type: text/html\r\n" +
                    "\r\n" +
                    "<html>" +
                    "<head>" +
                    "<title>ZeroHTTPd: Unimplemented</title>" +
                    "</head>" +
                    "<body>" +
                    "<h1>Bad Request (Unimplemented)</h1>" +
                    "<p>Your client sent a request ZeroHTTPd did not understand and it is probably not your fault.</p>" +
                    "</body>" +
                    "</html>"

        const val http_404_content =
            "HTTP/1.0 404 Not Found\r\n" +
                    "Content-type: text/html\r\n" +
                    "\r\n" +
                    "<html>" +
                    "<head>" +
                    "<title>ZeroHTTPd: Not Found</title>" +
                    "</head>" +
                    "<body>" +
                    "<h1>Not Found (404)</h1>" +
                    "<p>Your client is asking for an object that was not found on this server.</p>" +
                    "</body>" +
                    "</html>"


    }
}

@Suppress("UNUSED_PARAMETER")
fun sigint_handler(signo: Int): Unit {
    val __FUNCTION__ = "sigint_handler"

    printf("^C pressed. Shutting down.\n")

    exit(0)
}
