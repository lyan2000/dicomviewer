package com.tauslop.dicom

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream

class DicomException(message: String) : Exception(message)

/** Position-tracking byte reader with switchable endianness. */
internal class DicomInput(stream: InputStream) {
    private val inp: InputStream =
        if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 64 * 1024)

    var position: Long = 0L
        private set
    var bigEndian: Boolean = false

    fun readFully(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw EOFException("Unexpected end of file at ${position + off}")
            off += r
        }
        position += n
        return buf
    }

    private fun u8(): Int {
        val b = inp.read()
        if (b < 0) throw EOFException("Unexpected end of file at $position")
        position++
        return b
    }

    fun u16(): Int {
        val a = u8()
        val b = u8()
        return if (bigEndian) (a shl 8) or b else (b shl 8) or a
    }

    /** Unsigned 32-bit value as Long (0xFFFFFFFF = undefined length). */
    fun u32(): Long {
        val a = u16().toLong()
        val b = u16().toLong()
        return if (bigEndian) (a shl 16) or b else (b shl 16) or a
    }

    fun tag(): Int {
        val g = u16()
        val e = u16()
        return (g shl 16) or e
    }

    fun skip(n: Long) {
        var left = n
        while (left > 0) {
            val s = inp.skip(left)
            if (s <= 0) {
                if (inp.read() < 0) throw EOFException("Unexpected end of file at $position")
                left--
                position++
            } else {
                left -= s
                position += s
            }
        }
    }

    /** Returns up to [n] upcoming bytes without consuming them (fewer at end of stream). */
    fun peek(n: Int): ByteArray {
        inp.mark(n)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        inp.reset()
        return if (off == n) buf else buf.copyOf(off)
    }

    fun atEnd(): Boolean = peek(1).isEmpty()
}

internal const val UNDEFINED_LENGTH = 0xFFFFFFFFL
