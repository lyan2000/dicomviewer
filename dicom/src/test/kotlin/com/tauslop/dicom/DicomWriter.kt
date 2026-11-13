package com.tauslop.dicom

import java.io.ByteArrayOutputStream

/** Minimal DICOM encoder for building synthetic test files. */
class DicomWriter(
    private val ts: TransferSyntax,
    private val preamble: Boolean = true,
    private val meta: Boolean = true,
    private val tsUid: String = ts.uid,
) {
    private val body = ByteArrayOutputStream()
    private val be get() = ts.bigEndian

    fun str(tag: Int, vr: VR, value: String): DicomWriter {
        var b = value.toByteArray(Charsets.ISO_8859_1)
        if (b.size % 2 == 1) b += (if (vr == VR.UI) 0 else ' '.code).toByte()
        element(body, tag, vr, b, ts)
        return this
    }

    fun us(tag: Int, vararg v: Int): DicomWriter {
        val out = ByteArrayOutputStream()
        v.forEach { u16(out, it, be) }
        element(body, tag, VR.US, out.toByteArray(), ts)
        return this
    }

    fun raw(tag: Int, vr: VR, bytes: ByteArray): DicomWriter {
        element(body, tag, vr, bytes, ts); return this
    }

    /** Sequence of items, each built by a nested writer sharing this syntax. */
    fun seq(tag: Int, undefinedLength: Boolean, vararg items: DicomWriter.() -> Unit): DicomWriter {
        val content = ByteArrayOutputStream()
        for (build in items) {
            val item = DicomWriter(ts, preamble = false, meta = false).apply(build).body.toByteArray()
            u16(content, 0xFFFE, be); u16(content, 0xE000, be)
            if (undefinedLength) {
                u32(content, 0xFFFFFFFFL, be); content.write(item)
                u16(content, 0xFFFE, be); u16(content, 0xE00D, be); u32(content, 0, be)
            } else {
                u32(content, item.size.toLong(), be); content.write(item)
            }
        }
        if (undefinedLength) { u16(content, 0xFFFE, be); u16(content, 0xE0DD, be); u32(content, 0, be) }
        header(body, tag, VR.SQ, if (undefinedLength) 0xFFFFFFFFL else content.size().toLong(), ts)
        content.writeTo(body)
        return this
    }

    fun pixels16(values: ShortArray): DicomWriter {
        val out = ByteArrayOutputStream()
        values.forEach { u16(out, it.toInt() and 0xFFFF, be) }
        element(body, Tag.PixelData, VR.OW, out.toByteArray(), ts)
        return this
    }

    fun pixels8(values: ByteArray): DicomWriter {
        val b = if (values.size % 2 == 1) values + 0 else values
        element(body, Tag.PixelData, VR.OB, b, ts)
        return this
    }

    /** Encapsulated pixel data with one empty offset table and one fragment. */
    fun encapsulatedPixels(fragment: ByteArray): DicomWriter {
        header(body, Tag.PixelData, VR.OB, 0xFFFFFFFFL, ts)
        u16(body, 0xFFFE, false); u16(body, 0xE000, false); u32(body, 0, false)
        u16(body, 0xFFFE, false); u16(body, 0xE000, false); u32(body, fragment.size.toLong(), false); body.write(fragment)
        u16(body, 0xFFFE, false); u16(body, 0xE0DD, false); u32(body, 0, false)
        return this
    }

    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        if (preamble) { out.write(ByteArray(128)); out.write("DICM".toByteArray()) }
        if (meta) {
            var uid = tsUid.toByteArray()
            if (uid.size % 2 == 1) uid += 0
            element(out, Tag.TransferSyntaxUID, VR.UI, uid, TransferSyntax.EXPLICIT_LE)
        }
        body.writeTo(out)
        return out.toByteArray()
    }

    companion object {
        private fun u16(out: ByteArrayOutputStream, v: Int, be: Boolean) {
            if (be) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
            else { out.write(v and 0xFF); out.write(v ushr 8 and 0xFF) }
        }

        private fun u32(out: ByteArrayOutputStream, v: Long, be: Boolean) {
            val hi = (v ushr 16 and 0xFFFF).toInt(); val lo = (v and 0xFFFF).toInt()
            if (be) { u16(out, hi, true); u16(out, lo, true) } else { u16(out, lo, false); u16(out, hi, false) }
        }

        private fun header(out: ByteArrayOutputStream, tag: Int, vr: VR, len: Long, ts: TransferSyntax) {
            val be = ts.bigEndian
            u16(out, tag ushr 16, be); u16(out, tag and 0xFFFF, be)
            if (ts.explicitVr) {
                out.write(vr.name[0].code); out.write(vr.name[1].code)
                if (vr.longLength) { u16(out, 0, be); u32(out, len, be) } else u16(out, len.toInt(), be)
            } else u32(out, len, be)
        }

        private fun element(out: ByteArrayOutputStream, tag: Int, vr: VR, bytes: ByteArray, ts: TransferSyntax) {
            header(out, tag, vr, bytes.size.toLong(), ts)
            out.write(bytes)
        }
    }
}

/** A standard small CT-like grayscale image. */
fun ctImage(
    ts: TransferSyntax,
    pixels: ShortArray,
    rows: Int,
    cols: Int,
    z: Double = 0.0,
    seriesUid: String = "1.2.3.4",
    instance: Int = 1,
    preamble: Boolean = true,
    extra: DicomWriter.() -> Unit = {},
): ByteArray = DicomWriter(ts, preamble).apply {
    str(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2")
    str(Tag.Modality, VR.CS, "CT")
    str(Tag.PatientName, VR.PN, "Test^Patient")
    str(Tag.SeriesInstanceUID, VR.UI, seriesUid)
    str(Tag.InstanceNumber, VR.IS, instance.toString())
    str(Tag.ImagePositionPatient, VR.DS, "-100\\-100\\$z")
    str(Tag.ImageOrientationPatient, VR.DS, "1\\0\\0\\0\\1\\0")
    us(Tag.SamplesPerPixel, 1)
    str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
    us(Tag.Rows, rows); us(Tag.Columns, cols)
    str(Tag.PixelSpacing, VR.DS, "0.5\\0.5")
    us(Tag.BitsAllocated, 16); us(Tag.BitsStored, 16); us(Tag.HighBit, 15); us(Tag.PixelRepresentation, 1)
    str(Tag.WindowCenter, VR.DS, "40\\-600"); str(Tag.WindowWidth, VR.DS, "400\\1500")
    str(Tag.RescaleIntercept, VR.DS, "-1024"); str(Tag.RescaleSlope, VR.DS, "1")
    extra()
    pixels16(pixels)
}.bytes()
