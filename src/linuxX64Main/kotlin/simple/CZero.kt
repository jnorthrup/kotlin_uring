package simple

import kotlinx.cinterop.*

object CZero {
    val Byte.nz get() = 0 != this.toInt()
    val Short.nz get() = 0 != this.toInt()
    val Char.nz get() = 0 != this.code
    val Int.nz get() = 0 != this
    val Long.nz get() = 0L != this
    val UByte.nz get() = 0 != this.toInt()
    val UShort.nz get() = 0 != this.toInt()
    val UInt.nz get() = 0U != this
    val ULong.nz get() = 0UL != this
    val Byte.z get() = 0 == this.toInt()
    val Short.z get() = 0 == this.toInt()
    val Char.z get() = 0 == this.code
    val Int.z get() = 0 == this
    val Long.z get() = 0L == this
    val UByte.z get() = 0 == this.toInt()
    val UShort.z get() = 0 == this.toInt()
    val UInt.z get() = 0U == this
    val ULong.z get() = 0UL == this
    val Boolean.bool get() = if (this) 1 else 0
}

inline fun NativeFreeablePlacement.alloc(v: Byte): ByteVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: Short): ShortVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: Int): IntVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: Long): LongVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: UByte): UByteVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: UShort): UShortVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: UInt): UIntVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: ULong): ULongVar = alloc { value = v }
inline fun NativeFreeablePlacement.alloc(v: Boolean): BooleanVar = alloc { value = v }
