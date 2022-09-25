# Kotlin liburing

kotlin native io_uring adaptation 

progress to date includes most (interesting) io_uring tests ported to KN.



## adjacent goals

it makes sense to co-develop the abstractions with K-Uring:

* [x] ByteBuffer port from jdk (exists)
* [ ] ByteBuffer fluent DSL for inlinable expressions and lexers (successor to bbcursive)
* [ ] low-level network daemons and tcpd pipes

## near-term

* isolate samples but keep -liburing shims 
  