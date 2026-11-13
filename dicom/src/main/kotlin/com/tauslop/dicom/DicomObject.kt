package com.tauslop.dicom

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

class Element(
    val tag: Int,
    val vr: VR,
    val bytes: ByteArray,
    val bigEndian: Boolean,
    val items: List<DicomObject>? = null,
)

/** A dataset (or sequence item): tag → element, with typed accessors. */
class DicomObject internal constructor(val parent: DicomObject? = null) {
    private val elements = LinkedHashMap<Int, Element>()

    internal fun put(e: Element) { elements[e.tag] = e }

    operator fun get(tag: Int): Element? = elements[tag]
    operator fun contains(tag: Int): Boolean = elements.containsKey(tag)
    val tags: Set<Int> get() = elements.keys
    val size: Int get() = elements.size

    /** Character set for text values, from (0008,0005) here or in an enclosing dataset. */
    val charset: Charset by lazy {
        val own = elements[Tag.SpecificCharacterSet]
        if (own == null) parent?.charset ?: Charsets.ISO_8859_1
        else {
            val terms = String(own.bytes, Charsets.US_ASCII).split('\\').map { it.trim() }
            if (terms.any { it == "ISO_IR 192" }) Charsets.UTF_8 else Charsets.ISO_8859_1
        }
    }

    // ---- strings -----------------------------------------------------------

    /** All values as strings (text split on '\', binary numbers formatted). */
    fun strings(tag: Int): List<String> {
        val e = elements[tag] ?: return emptyList()
        return when (e.vr) {
            VR.US, VR.SS, VR.UL, VR.SL, VR.FL, VR.FD -> numbers(e).map { fmt(it) }
            VR.SQ, VR.OB, VR.OW, VR.OF, VR.OD, VR.OL, VR.OV, VR.UN, VR.AT, VR.SV, VR.UV ->
                if (e.vr == VR.UN) textValues(e) else emptyList()
            else -> textValues(e)
        }
    }

    fun string(tag: Int): String? = strings(tag).firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun textValues(e: Element): List<String> {
        if (e.bytes.isEmpty()) return emptyList()
        val cs = if (e.vr == VR.UI || e.vr == VR.CS || e.vr == VR.DS || e.vr == VR.IS) Charsets.US_ASCII else charset
        val s = String(e.bytes, cs)
        val parts = if (e.vr == VR.LT || e.vr == VR.ST || e.vr == VR.UT || e.vr == VR.UR) listOf(s) else s.split('\\')
        return parts.map { it.trim(' ', '\u0000') }
    }

    // ---- numbers -----------------------------------------------------------

    /** All values as doubles, from binary (US/SS/UL/SL/FL/FD) or decimal strings (DS/IS). */
    fun doubles(tag: Int): DoubleArray {
        val e = elements[tag] ?: return DoubleArray(0)
        return when (e.vr) {
            VR.US, VR.SS, VR.UL, VR.SL, VR.FL, VR.FD -> numbers(e)
            else -> textValues(e).mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
        }
    }

    fun double(tag: Int): Double? = doubles(tag).firstOrNull()
    fun int(tag: Int): Int? = double(tag)?.toInt()
    fun int(tag: Int, default: Int): Int = int(tag) ?: default

    private fun numbers(e: Element): DoubleArray {
        val bb = ByteBuffer.wrap(e.bytes).order(if (e.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        return when (e.vr) {
            VR.US -> DoubleArray(e.bytes.size / 2) { (bb.getShort(it * 2).toInt() and 0xFFFF).toDouble() }
            VR.SS -> DoubleArray(e.bytes.size / 2) { bb.getShort(it * 2).toDouble() }
            VR.UL -> DoubleArray(e.bytes.size / 4) { (bb.getInt(it * 4).toLong() and 0xFFFFFFFFL).toDouble() }
            VR.SL -> DoubleArray(e.bytes.size / 4) { bb.getInt(it * 4).toDouble() }
            VR.FL -> DoubleArray(e.bytes.size / 4) { bb.getFloat(it * 4).toDouble() }
            VR.FD -> DoubleArray(e.bytes.size / 8) { bb.getDouble(it * 8) }
            else -> DoubleArray(0)
        }
    }

    private fun fmt(d: Double): String = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    // ---- sequences / raw ---------------------------------------------------

    fun sequence(tag: Int): List<DicomObject> = elements[tag]?.items ?: emptyList()
    fun bytes(tag: Int): ByteArray? = elements[tag]?.bytes
}
