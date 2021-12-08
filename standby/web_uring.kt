//include <stdio.h>
//include <netinet/in.h>
//include <string.h>
//include <ctype.h>
//include <unistd.h>
//include <stdlib.h>
//include <signal.h>
//include <liburing.h>
//include <sys/stat.h>
//include <fcntl.h>

#define SERVER_STRING           "Server: zerohttpd/0.1\r\n"
#define DEFAULT_SERVER_PORT     8000
#define QUEUE_DEPTH             256
#define READ_SZ                 8192

#define EVENT_TYPE_ACCEPT       0
#define EVENT_TYPE_READ         1
#define EVENT_TYPE_WRITE        2

struct request {
    event_type:Int;
    iovec_count:Int;
    client_socket:Int;
    iov:CPointer<iovec>;
};

ring:io_uring;

const char:CPointer<ByteVar>unimplemented_content= \
"HTTP/1.0 400 Bad Request\r\n"
"Content-type: text/html\r\n"
"\r\n"
"<html>"
"<head>"
"<title>ZeroHTTPd: Unimplemented</title>"
"</head>"
"<body>"
"<h1>Bad Request (Unimplemented)</h1>"
"<p>Your client sent a request ZeroHTTPd did not understand and it is probably not your fault.</p>"
"</body>"
"</html>";

const char:CPointer<ByteVar>http_404_content= \
"HTTP/1.0 404 Not Found\r\n"
"Content-type: text/html\r\n"
"\r\n"
"<html>"
"<head>"
"<title>ZeroHTTPd: Not Found</title>"
"</head>"
"<body>"
"<h1>Not Found (404)</h1>"
"<p>Your client is asking for an object that was not found on this server.</p>"
"</body>"
"</html>";

/*
 * Utility function to convert a string to lower case.
 * */

fun strtolower(char:CPointer<ByteVar>str:Unit{
	val __FUNCTION__="strtolower"

    for (; *str; ++str)
    *str = (char) tolower(*str);
}

/*
 One function that prints the system call and the error details
 and then exits with error code 1. Non-zero meaning things didn't go well.
 */
fun fatal_error(syscall:String):Unit{
	val __FUNCTION__="fatal_error"

    perror(syscall);
    exit(1);
}

/*
 * Helper function for cleaner looking code.
 * */

fun zh_malloc(size:size_t):CPointer<void>{
	val __FUNCTION__="zh_malloc"

    void:CPointer<ByteVar>buf= malloc(size);
    if (!buf) {
        fprintf(stderr, "Fatal error: unable to allocate memory.\n");
        exit(1);
    }
    return buf;
}

/*
 * This function is responsible for setting up the main listening socket used by the
 * web server.
 * */

fun setup_listening_socket(port:Int):Int{
	val __FUNCTION__="setup_listening_socket"

    sock:Int;
    srv_addr:sockaddr_in;

    sock = socket(PF_INET, SOCK_STREAM, 0);
    if (sock == -1)
        fatal_error("socket()");

    enable:Int = 1;
    if (setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, enable.ptr, sizeof(int)) < 0)
    fatal_error("setsockopt(SO_REUSEADDR)");


    memset(srv_addr.ptr, 0, sizeof(srv_addr));
    srv_addr.sin_family = AF_INET;
    srv_addr.sin_port = htons(port);
    srv_addr.sin_addr.s_addr = htonl(INADDR_ANY);

    /* We bind to a port and turn this socket into a listening
     * socket.
     * */
    if (bind(sock, (const struct sockaddr *) srv_addr.ptr, sizeof(srv_addr)) < 0)
    fatal_error("bind()");

    if (listen(sock, 10) < 0)
        fatal_error("listen()");

    return (sock);
}

fun add_accept_request(server_socket:Int, client_addr:CPointer<sockaddr_in>, socklen_t *client_addr_len):Int{
	val __FUNCTION__="add_accept_request"

    sqe:CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr);
    io_uring_prep_accept(sqe, server_socket, (struct sockaddr *) client_addr, client_addr_len, 0);
    req:CPointer<request> = malloc(sizeof(*req));
    req.pointed.event_type = EVENT_TYPE_ACCEPT;
    io_uring_sqe_set_data(sqe, req);
    io_uring_submit(ring.ptr);

    return 0;
}

fun add_read_request(client_socket:Int):Int{
	val __FUNCTION__="add_read_request"

    sqe:CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr);
    req:CPointer<request> = malloc(sizeof(*req) + sizeof(struct iovec));
    req.pointed.iov[0].iov_base = malloc(READ_SZ);
    req.pointed.iov[0].iov_len = READ_SZ;
    req.pointed.event_type = EVENT_TYPE_READ;
    req.pointed.client_socket = client_socket;
    memset(req.pointed.iov[0].iov_base, 0, READ_SZ);
    /* Linux kernel 5.5 has support for readv, but not for recv() or read() */
    io_uring_prep_readv(sqe, client_socket, req.ptr.pointed.iov[0], 1, 0);
    io_uring_sqe_set_data(sqe, req);
    io_uring_submit(ring.ptr);
    return 0;
}

fun add_write_request(req:CPointer<request>):Int{
	val __FUNCTION__="add_write_request"

    sqe:CPointer<io_uring_sqe> = io_uring_get_sqe(ring.ptr);
    req.pointed.event_type = EVENT_TYPE_WRITE;
    io_uring_prep_writev(sqe, req.pointed.client_socket, req.pointed.iov, req.pointed.iovec_count, 0);
    io_uring_sqe_set_data(sqe, req);
    io_uring_submit(ring.ptr);
    return 0;
}

fun _send_static_string_content(str:String, client_socket:Int):Unit{
	val __FUNCTION__="_send_static_string_content"

    req:CPointer<request> = zh_malloc(sizeof(*req) + sizeof(struct iovec));
    slen:ULong  = strlen(str);
    req.pointed.iovec_count = 1;
    req.pointed.client_socket = client_socket;
    req.pointed.iov[0].iov_base = zh_malloc(slen);
    req.pointed.iov[0].iov_len = slen;
    memcpy(req.pointed.iov[0].iov_base, str, slen);
    add_write_request(req);
}

/*
 * When ZeroHTTPd encounters any other HTTP method other than GET or POST, this function
 * is used to inform the client.
 * */

fun handle_unimplemented_method(client_socket:Int):Unit{
	val __FUNCTION__="handle_unimplemented_method"

    _send_static_string_content(unimplemented_content, client_socket);
}

/*
 * This function is used to send a "HTTP Not Found" code and message to the client in
 * case the file requested is not found.
 * */

fun handle_http_404(client_socket:Int):Unit{
	val __FUNCTION__="handle_http_404"

    _send_static_string_content(http_404_content, client_socket);
}

/*
 * Once a static file is identified to be served, this function is used to read the file
 * and write it over the client socket using Linux's sendfile() system call. This saves us
 * the hassle of transferring file buffers from kernel to user space and back.
 * */

fun copy_file_contents(char:CPointer<ByteVar>file_path file_size:off_t, iov:CPointer<iovec>):Unit{
	val __FUNCTION__="copy_file_contents"

    fd:Int;

    char:CPointer<ByteVar>buf= zh_malloc(file_size);
    fd = open(file_path, O_RDONLY);
    if (fd < 0)
        fatal_error("open");

    /* We should really check for short reads here */
    ret:Int = read(fd, buf, file_size);
    if (ret < file_size) {
        fprintf(stderr, "Encountered a short read.\n");
    }
    close(fd);

    iov.pointed.iov_base = buf;
    iov.pointed.iov_len = file_size;
}

/*
 * Simple function to get the file extension of the file that we are about to serve.
 * */

fun get_filename_ext(filename:String):String{
	val __FUNCTION__="get_filename_ext"

    dot:String = strrchr(filename, '.');
    if (!dot || dot == filename)
        return "";
    return dot + 1;
}

/*
 * Sends the HTTP 200 OK header, the server string, for a few types of files, it can also
 * send the content type based on the file extension. It also sends the content length
 * header. Finally it send a '\r\n' in a line by itself signalling the end of headers
 * and the beginning of any content.
 * */

fun send_headers(path:String, len:off_t, iov:CPointer<iovec>):Unit{
	val __FUNCTION__="send_headers"

    char small_case_path[1024];
    char send_buffer[1024];
    strcpy(small_case_path, path);
    strtolower(small_case_path);

    char:CPointer<ByteVar>str= "HTTP/1.0 200 OK\r\n";
    slen:ULong  = strlen(str);
    iov[0].iov_base = zh_malloc(slen);
    iov[0].iov_len = slen;
    memcpy(iov[0].iov_base, str, slen);

    slen = strlen(SERVER_STRING);
    iov[1].iov_base = zh_malloc(slen);
    iov[1].iov_len = slen;
    memcpy(iov[1].iov_base, SERVER_STRING, slen);

    /*
     * Check the file extension for certain common types of files
     * on web pages and send the appropriate content-type header.
     * Since extensions can be mixed case like JPG, jpg or Jpg,
     * we turn the extension into lower case before checking.
     * */
    file_ext:String = get_filename_ext(small_case_path);
    if (strcmp("jpg", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: image/jpeg\r\n");
    if (strcmp("jpeg", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: image/jpeg\r\n");
    if (strcmp("png", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: image/png\r\n");
    if (strcmp("gif", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: image/gif\r\n");
    if (strcmp("htm", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: text/html\r\n");
    if (strcmp("html", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: text/html\r\n");
    if (strcmp("js", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: application/javascript\r\n");
    if (strcmp("css", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: text/css\r\n");
    if (strcmp("txt", file_ext) == 0)
        strcpy(send_buffer, "Content-Type: text/plain\r\n");
    slen = strlen(send_buffer);
    iov[2].iov_base = zh_malloc(slen);
    iov[2].iov_len = slen;
    memcpy(iov[2].iov_base, send_buffer, slen);

    /* Send the content-length header, which is the file size in this case. */
    sprintf(send_buffer, "content-length: %ld\r\n", len);
    slen = strlen(send_buffer);
    iov[3].iov_base = zh_malloc(slen);
    iov[3].iov_len = slen;
    memcpy(iov[3].iov_base, send_buffer, slen);

    /*
     * When the browser sees a '\r\n' sequence in a line on its own,
     * it understands there are no more headers. Content may follow.
     * */
    strcpy(send_buffer, "\r\n");
    slen = strlen(send_buffer);
    iov[4].iov_base = zh_malloc(slen);
    iov[4].iov_len = slen;
    memcpy(iov[4].iov_base, send_buffer, slen);
}

fun handle_get_method(char:CPointer<ByteVar>path client_socket:Int):Unit{
	val __FUNCTION__="handle_get_method"

    char final_path[1024];

    /*
     If a path ends in a trailing slash, the client probably wants the index
     file inside of that directory.
     */
    if (path[strlen(path) - 1] == '/') {
        strcpy(final_path, "public");
        strcat(final_path, path);
        strcat(final_path, "index.html");
    } else {
        strcpy(final_path, "public");
        strcat(final_path, path);
    }

    /* The stat() system call will give you information about the file
     * like type (regular file, directory, etc), size, etc. */
    path_stat:stat;
    if (stat(final_path, path_stat.ptr) == -1) {
        printf("404 Not Found: %s (%s)\n", final_path, path);
        handle_http_404(client_socket);
    } else {
        /* Check if this is a normal/regular file and not a directory or something else */
        if (S_ISREG(path_stat.st_mode)) {
            req:CPointer<request> = zh_malloc(sizeof(*req) + (sizeof(struct iovec) * 6));
            req.pointed.iovec_count = 6;
            req.pointed.client_socket = client_socket;
            send_headers(final_path, path_stat.st_size, req.pointed.iov);
            copy_file_contents(final_path, path_stat.st_size, req.ptr.pointed.iov[5]);
            printf("200 %s %ld bytes\n", final_path, path_stat.st_size);
            add_write_request(req);
        } else {
            handle_http_404(client_socket);
            printf("404 Not Found: %s\n", final_path);
        }
    }
}

/*
 * This function looks at method used and calls the appropriate handler function.
 * Since we only implement GET and POST methods, it calls handle_unimplemented_method()
 * in case both these don't match. This sends an error to the client.
 * */

fun handle_http_method(char:CPointer<ByteVar>method_buffer client_socket:Int):Unit{
	val __FUNCTION__="handle_http_method"

    char:CPointer<ByteVar>path:CPointer<method>, *saveptr;

    method = strtok_r(method_buffer, " ", saveptr.ptr);
    strtolower(method);
    path = strtok_r(NULL, " ", saveptr.ptr);

    if (strcmp(method, "get") == 0) {
        handle_get_method(path, client_socket);
    } else {
        handle_unimplemented_method(client_socket);
    }
}

fun get_line(src:String, char:CPointer<ByteVar>dest dest_sz:Int):Int{
	val __FUNCTION__="get_line"

    for (i/*as int */ in 0 until  dest_sz) {
        dest[i] = src[i];
        if (src[i] == '\r' && src[i + 1] == '\n') {
            dest[i] = '\0';
            return 0;
        }
    }
    return 1;
}

fun handle_client_request(req:CPointer<request>):Int{
	val __FUNCTION__="handle_client_request"

    char http_request[1024];
    /* Get the first line, which will be the request */
    if (get_line(req.pointed.iov[0].iov_base, http_request, sizeof(http_request))) {
        fprintf(stderr, "Malformed request\n");
        exit(1);
    }
    handle_http_method(http_request, req.pointed.client_socket);
    return 0;
}

fun server_loop(server_socket:Int):Unit{
	val __FUNCTION__="server_loop"

    cqe:CPointer<io_uring_cqe>;
    client_addr:sockaddr_in;
    client_addr_len:socklen_t = sizeof(client_addr);

    add_accept_request(server_socket, client_addr.ptr, client_addr_len.ptr);

    while (1) {
        ret:Int = io_uring_wait_cqe(ring.ptr, cqe.ptr);
        if (ret < 0)
            fatal_error("io_uring_wait_cqe");
        req:CPointer<request> = (struct request *) cqe.pointed.user_data;
        if (cqe.pointed.res < 0) {
            fprintf(stderr, "Async request failed: %s for event: %d\n", strerror(-cqe.pointed.res), req.pointed.event_type);
            exit(1);
        }

        when  (req.pointed.event_type)  {
            EVENT_TYPE_ACCEPT -> 
            add_accept_request(server_socket, client_addr.ptr, client_addr_len.ptr);
            add_read_request(cqe.pointed.res);
            free(req);
            break;
            EVENT_TYPE_READ -> 
            if (!cqe.pointed.res) {
            fprintf(stderr, "Empty request!\n");
            break;
        }
            handle_client_request(req);
            free(req.pointed.iov[0].iov_base);
            free(req);
            break;
            EVENT_TYPE_WRITE -> 
            for (i/*as int */ in 0 until  req.pointed.iovec_count) {
            free(req.pointed.iov[i].iov_base);
        }
            close(req.pointed.client_socket);
            free(req);
            break;
        }
        /* Mark this request as processed */
        io_uring_cqe_seen(ring.ptr, cqe);
    }
}

fun sigint_handler(signo:Int):Unit{
	val __FUNCTION__="sigint_handler"

    printf("^C pressed. Shutting down.\n");
    io_uring_queue_exit(ring.ptr);
    exit(0);
}

fun main():Int{
	val __FUNCTION__="main"

    server_socket:Int = setup_listening_socket(DEFAULT_SERVER_PORT);

    signal(SIGINT, sigint_handler);
    io_uring_queue_init(QUEUE_DEPTH, ring.ptr, 0);
    server_loop(server_socket);

    return 0;
}