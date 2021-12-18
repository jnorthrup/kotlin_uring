package test.web_uring

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import linux_uring.iovec

data class WebUringRequest(
    var event_type: Int = 0,
    var client_socket: Int = 0,
    var iovec_count: Int = 1,
    var iov: CArrayPointer<iovec> = nativeHeap.allocArray(iovec_count),
)