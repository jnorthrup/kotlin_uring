# Kotlin liburing

this is a holding buffer for abstracting network patterns leveraging io-uring native API

the goals of this project are specific to linux kernels 5.15+ and whatever may happen to work below that.

https://github.com/shuveb/io_uring-by-example provides a straw man from which a webserver in test.web_uring

## adjacent goals

it makes sense to co-develop the abstractions with K-Uring:

* [x] ByteBuffer port from jdk (exists)
* [ ] ByteBuffer fluent DSL for inlinable expressions and lexers (successor to bbcursive)
* [ ] low-level network daemons and tcpd pipes

## near-term

* isolate samples but keep -liburing shims 
  