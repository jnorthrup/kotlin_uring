package simple

import platform.posix.__off_t

interface HasSize : HasDescriptor {
    val size: __off_t /* = kotlin.Long */ get() = st.st_size
}
