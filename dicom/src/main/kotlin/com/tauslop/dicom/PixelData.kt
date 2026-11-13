package com.tauslop.dicom

/** Why a file can't be shown as an image (null = displayable). */
enum class SkipReason { NOT_DICOM, NO_PIXEL_DATA, UNSUPPORTED_TRANSFER_SYNTAX, UNSUPPORTED_PIXEL_FORMAT }

/** Image Pixel module attributes (PS3.3 C.7.6.3). */
class ImageInfo(
    val rows: Int,
    val columns: Int,
    val samplesPerPixel: Int,
    val photometric: String,
    val planarConfiguration: Int,
    val bitsAllocated: Int,
    val bitsStored: Int,
    val highBit: Int,
    val signed: Boolean,
    val frames: Int,
) {
    val isMonochrome: Boolean get() = photometric.startsWith("MONOCHROME")
    val invertedGrayscale: Boolean get() = photometric == "MONOCHROME1"
    val frameBytes: Int get() = rows * columns * samplesPerPixel * (bitsAllocated / 8)

    /** Smallest and largest representable stored value. */
    val minStored: Int get() = if (signed) -(1 shl (bitsStored - 1)) else 0
    val maxStored: Int get() = if (signed) (1 shl (bitsStored - 1)) - 1 else (1 shl bitsStored) - 1

    companion object {
        fun of(ds: DicomObject): ImageInfo {
            val bitsAllocated = ds.int(Tag.BitsAllocated, 0)
            val bitsStored = ds.int(Tag.BitsStored) ?: bitsAllocated
            return ImageInfo(
                rows = ds.int(Tag.Rows, 0),
                columns = ds.int(Tag.Columns, 0),
                samplesPerPixel = ds.int(Tag.SamplesPerPixel, 1),
                photometric = ds.string(Tag.PhotometricInterpretation)?.uppercase() ?: "MONOCHROME2",
                planarConfiguration = ds.int(Tag.PlanarConfiguration, 0),
                bitsAllocated = bitsAllocated,
                bitsStored = bitsStored,
                highBit = ds.int(Tag.HighBit) ?: (bitsStored - 1),
                signed = ds.int(Tag.PixelRepresentation, 0) == 1,
                frames = maxOf(1, ds.int(Tag.NumberOfFrames, 1)),
            )
        }

        private val COLOR = setOf("RGB", "YBR_FULL")

        /** Decides whether a parsed file is displayable by this phase-1 decoder. */
        fun skipReason(file: DicomFile): SkipReason? {
            if (!file.hasPixelData) return SkipReason.NO_PIXEL_DATA
            if (!file.transferSyntax.isSupported || file.encapsulated) return SkipReason.UNSUPPORTED_TRANSFER_SYNTAX
            val info = of(file.dataset)
            val ok = info.rows > 0 && info.columns > 0 &&
                (info.bitsAllocated == 8 || info.bitsAllocated == 16) &&
                info.bitsStored in 1..info.bitsAllocated &&
                when (info.samplesPerPixel) {
                    1 -> info.isMonochrome
                    3 -> info.photometric in COLOR && info.bitsAllocated == 8
                    else -> false
                }
            return if (ok) null else SkipReason.UNSUPPORTED_PIXEL_FORMAT
        }
    }
}

/**
 * One decoded frame. Grayscale frames keep stored values (masked and sign-extended)
 * in [gray]; color frames are packed ARGB in [argb].
 */
class Frame(
    val width: Int,
    val height: Int,
    val info: ImageInfo,
    val gray: ShortArray?,
    val argb: IntArray?,
) {
    /** Stored value at index i (grayscale only). */
    fun stored(i: Int): Int {
        val s = gray!![i].toInt()
        return if (info.signed) s else s and 0xFFFF
    }

    val byteSize: Int get() = (gray?.size ?: 0) * 2 + (argb?.size ?: 0) * 4

    /** Minimum and maximum stored value in this frame (grayscale only). */
    fun storedRange(): IntArray {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (i in gray!!.indices) {
            val v = stored(i)
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return intArrayOf(lo, hi)
    }
}

object PixelDecoder {

    /** Decodes frame [index] from a fully parsed file. */
    fun decode(file: DicomFile, index: Int = 0): Frame {
        ImageInfo.skipReason(file)?.let { throw DicomException("Cannot decode: $it") }
        val info = ImageInfo.of(file.dataset)
        val data = file.pixelData ?: throw DicomException("Pixel data not loaded")
        if (index !in 0 until info.frames) throw DicomException("Frame $index out of range (${info.frames})")
        val offset = index.toLong() * info.frameBytes
        if (offset + info.frameBytes > data.size) {
            throw DicomException("Pixel data too short: ${data.size} bytes, need ${offset + info.frameBytes}")
        }
        return if (info.samplesPerPixel == 1) decodeGray(data, offset.toInt(), info, file.pixelDataBigEndian)
        else decodeColor(data, offset.toInt(), info)
    }

    private fun decodeGray(data: ByteArray, off: Int, info: ImageInfo, bigEndian: Boolean): Frame {
        val n = info.rows * info.columns
        val out = ShortArray(n)
        val shift = (info.highBit + 1 - info.bitsStored).coerceAtLeast(0)
        val mask = if (info.bitsStored >= 32) -1 else (1 shl info.bitsStored) - 1
        val signBit = 1 shl (info.bitsStored - 1)
        val two = info.bitsAllocated == 16
        for (i in 0 until n) {
            var raw = if (two) {
                val a = data[off + 2 * i].toInt() and 0xFF
                val b = data[off + 2 * i + 1].toInt() and 0xFF
                if (bigEndian) (a shl 8) or b else (b shl 8) or a
            } else data[off + i].toInt() and 0xFF
            raw = (raw ushr shift) and mask
            if (info.signed && raw and signBit != 0) raw -= (1 shl info.bitsStored)
            out[i] = raw.toShort()
        }
        return Frame(info.columns, info.rows, info, out, null)
    }

    private fun decodeColor(data: ByteArray, off: Int, info: ImageInfo): Frame {
        val n = info.rows * info.columns
        val out = IntArray(n)
        val planar = info.planarConfiguration == 1
        val ybr = info.photometric == "YBR_FULL"
        for (i in 0 until n) {
            var c0: Int; var c1: Int; var c2: Int
            if (planar) {
                c0 = data[off + i].toInt() and 0xFF
                c1 = data[off + n + i].toInt() and 0xFF
                c2 = data[off + 2 * n + i].toInt() and 0xFF
            } else {
                c0 = data[off + 3 * i].toInt() and 0xFF
                c1 = data[off + 3 * i + 1].toInt() and 0xFF
                c2 = data[off + 3 * i + 2].toInt() and 0xFF
            }
            if (ybr) { // PS3.3 C.7.6.3.1.2, full range
                val y = c0.toDouble(); val cb = c1 - 128.0; val cr = c2 - 128.0
                val r = (y + 1.402 * cr).toInt().coerceIn(0, 255)
                val g = (y - 0.344136 * cb - 0.714136 * cr).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * cb).toInt().coerceIn(0, 255)
                c0 = r; c1 = g; c2 = b
            }
            out[i] = (0xFF shl 24) or (c0 shl 16) or (c1 shl 8) or c2
        }
        return Frame(info.columns, info.rows, info, null, out)
    }
}
