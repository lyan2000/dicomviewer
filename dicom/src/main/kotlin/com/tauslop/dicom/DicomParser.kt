package com.tauslop.dicom

import java.io.File
import java.io.InputStream

/** Result of parsing one DICOM file. */
class DicomFile(
    val meta: DicomObject,
    val dataset: DicomObject,
    val transferSyntax: TransferSyntax,
    val transferSyntaxUid: String,
    /** True if a top-level (7FE0,0010) element exists. */
    val hasPixelData: Boolean,
    /** Pixel data stored as encapsulated fragments (compressed). */
    val encapsulated: Boolean,
    /** Raw native pixel bytes; null if not read (stopAtPixelData) or encapsulated. */
    val pixelData: ByteArray?,
    val pixelDataBigEndian: Boolean,
)

object DicomParser {

    fun parse(file: File, stopAtPixelData: Boolean = false): DicomFile =
        file.inputStream().use { parse(it, stopAtPixelData) }

    /**
     * Parses [stream]. With [stopAtPixelData] it stops at the top-level Pixel Data element,
     * which makes header scans fast. Throws [DicomException] if the stream is not DICOM.
     */
    fun parse(stream: InputStream, stopAtPixelData: Boolean = false): DicomFile {
        val inp = DicomInput(stream)
        val meta = DicomObject()

        val head = inp.peek(132)
        val hasPreamble = head.size == 132 &&
            head[128] == 'D'.code.toByte() && head[129] == 'I'.code.toByte() &&
            head[130] == 'C'.code.toByte() && head[131] == 'M'.code.toByte()
        if (hasPreamble) inp.skip(132)

        // File meta information: always explicit VR little endian.
        inp.bigEndian = false
        while (true) {
            val p = inp.peek(2)
            if (p.size < 2 || ((p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)) != 0x0002) break
            val reader = Reader(inp, TransferSyntax.EXPLICIT_LE, stopAtPixelData = true)
            reader.readOneElement(meta, depth = 0)
        }

        val tsUid = meta.string(Tag.TransferSyntaxUID)
        val ts = when {
            tsUid != null -> TransferSyntax.fromUid(tsUid)
            else -> guessSyntax(inp) ?: throw DicomException("Not a DICOM file")
        }
        if (!hasPreamble && meta.size == 0) {
            // Raw dataset without preamble: sanity check the first tag looks plausible.
            val p = inp.peek(4)
            if (p.size < 4) throw DicomException("Not a DICOM file")
            val group = (p[0].toInt() and 0xFF) or ((p[1].toInt() and 0xFF) shl 8)
            if (group == 0 || group > 0x0FFF) throw DicomException("Not a DICOM file")
        }

        val dataset = DicomObject()
        if (ts == TransferSyntax.DEFLATED_LE) {
            return DicomFile(meta, dataset, ts, tsUid ?: ts.uid, hasPixelData = false,
                encapsulated = false, pixelData = null, pixelDataBigEndian = false)
        }

        inp.bigEndian = ts.bigEndian
        val reader = Reader(inp, ts, stopAtPixelData)
        reader.readDataset(dataset)
        return DicomFile(meta, dataset, ts, tsUid ?: ts.uid, reader.pixelDataSeen, reader.encapsulated,
            reader.pixelData, ts.bigEndian)
    }

    /** For files with no meta header: explicit LE if bytes 4–5 look like a VR, else implicit LE. */
    private fun guessSyntax(inp: DicomInput): TransferSyntax? {
        val p = inp.peek(8)
        if (p.size < 8) return null
        return if (VR.of(p[4].toInt() and 0xFF, p[5].toInt() and 0xFF) != null) TransferSyntax.EXPLICIT_LE
        else TransferSyntax.IMPLICIT_LE
    }

    private class Reader(val inp: DicomInput, val ts: TransferSyntax, val stopAtPixelData: Boolean) {
        var pixelDataSeen = false
        var encapsulated = false
        var pixelData: ByteArray? = null
        private var stopped = false

        fun readDataset(obj: DicomObject) {
            while (!stopped && !inp.atEnd()) readOneElement(obj, depth = 0)
        }

        /** Reads one element into [obj]. Returns false if it was an item delimiter. */
        fun readOneElement(obj: DicomObject, depth: Int, syntax: TransferSyntax = ts): Boolean {
            val tag = inp.tag()
            if (tag == Tag.ItemDelimitation || tag == Tag.SequenceDelimitation) {
                inp.u32()
                return false
            }
            if (tag ushr 16 == 0xFFFE) { // stray item tag: skip
                val len = inp.u32()
                if (len != UNDEFINED_LENGTH) inp.skip(len)
                return true
            }

            val vr: VR
            val length: Long
            if (syntax.explicitVr) {
                val b0 = inp.readFully(2)
                vr = VR.of(b0[0].toInt() and 0xFF, b0[1].toInt() and 0xFF)
                    ?: throw DicomException("Invalid VR at ${inp.position - 2} for ${Tag.toString(tag)}")
                length = if (vr.longLength) { inp.skip(2); inp.u32() } else inp.u16().toLong()
            } else {
                length = inp.u32()
                vr = Tag.implicitVr(tag) ?: when {
                    length == UNDEFINED_LENGTH -> VR.SQ
                    // Unknown tag whose value starts with an Item tag: a defined-length sequence.
                    length >= 8 && startsWithItemTag(syntax.bigEndian) -> VR.SQ
                    else -> VR.UN
                }
            }

            if (tag == Tag.PixelData && depth == 0) {
                pixelDataSeen = true
                if (length == UNDEFINED_LENGTH) {
                    encapsulated = true
                    if (stopAtPixelData) { stopped = true; return true }
                    skipEncapsulated()
                    return true
                }
                if (stopAtPixelData) { stopped = true; return true }
                pixelData = readValue(length, tag)
                return true
            }

            if (vr == VR.SQ || length == UNDEFINED_LENGTH) {
                // UN with undefined length is a sequence encoded in implicit VR LE (PS3.5 §6.2.2).
                val seqSyntax = if (vr == VR.UN) TransferSyntax.IMPLICIT_LE else syntax
                val items = readSequence(obj, length, depth + 1, seqSyntax)
                obj.put(Element(tag, VR.SQ, ByteArray(0), syntax.bigEndian, items))
                return true
            }

            obj.put(Element(tag, vr, readValue(length, tag), syntax.bigEndian))
            return true
        }

        private fun startsWithItemTag(bigEndian: Boolean): Boolean {
            val p = inp.peek(4)
            if (p.size < 4) return false
            val b = p.map { it.toInt() and 0xFF }
            return if (bigEndian) b == listOf(0xFF, 0xFE, 0xE0, 0x00) else b == listOf(0xFE, 0xFF, 0x00, 0xE0)
        }

        private fun readValue(length: Long, tag: Int): ByteArray {
            if (length > Int.MAX_VALUE) throw DicomException("Element ${Tag.toString(tag)} too large: $length")
            return inp.readFully(length.toInt())
        }

        private fun readSequence(parent: DicomObject, length: Long, depth: Int, syntax: TransferSyntax): List<DicomObject> {
            val items = ArrayList<DicomObject>()
            val end = if (length == UNDEFINED_LENGTH) -1L else inp.position + length
            val savedEndian = inp.bigEndian
            inp.bigEndian = syntax.bigEndian
            try {
                while (end < 0 || inp.position < end) {
                    val tag = inp.tag()
                    val itemLen = inp.u32()
                    if (tag == Tag.SequenceDelimitation) break
                    if (tag != Tag.Item) throw DicomException("Expected item tag, found ${Tag.toString(tag)} at ${inp.position - 8}")
                    val item = DicomObject(parent)
                    if (itemLen == UNDEFINED_LENGTH) {
                        while (readOneElement(item, depth, syntax)) { /* until item delimiter */ }
                    } else {
                        val itemEnd = inp.position + itemLen
                        while (inp.position < itemEnd) readOneElement(item, depth, syntax)
                    }
                    items += item
                }
            } finally {
                inp.bigEndian = savedEndian
            }
            return items
        }

        private fun skipEncapsulated() {
            while (true) {
                val tag = inp.tag()
                val len = inp.u32()
                if (tag == Tag.SequenceDelimitation) return
                if (tag != Tag.Item) throw DicomException("Bad encapsulated pixel data at ${inp.position - 8}")
                inp.skip(len)
            }
        }
    }
}
