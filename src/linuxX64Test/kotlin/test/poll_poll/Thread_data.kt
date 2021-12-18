package test.poll_poll

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import linux_uring.io_uring

data class  thread_data (
	var ring: io_uring = nativeHeap.alloc(),
	var fd: Int = 0,
	var events: Int = 0,
	var test: String = "0",
	var out: IntArray = IntArray(2),
)