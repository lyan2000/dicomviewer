package com.tauslop.dicom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DicomParserTest {

    private fun parse(b: ByteArray, stop: Boolean = false) = DicomParser.parse(ByteArrayInputStream(b), stop)

    private val ctPixels = shortArrayOf(-2000, -1024, 0, 24, 1000, 3071, 32767, -32768, 1, 2, 3, 4)

    @Test fun uncompressedSyntaxesRoundTrip() {
        for (ts in listOf(TransferSyntax.IMPLICIT_LE, TransferSyntax.EXPLICIT_LE, TransferSyntax.EXPLICIT_BE)) {
            val f = parse(ctImage(ts, ctPixels, rows = 3, cols = 4))
            assertEquals(ts, f.transferSyntax)
            val ds = f.dataset
            assertEquals("CT", ds.string(Tag.Modality))
            assertEquals("Test^Patient", ds.string(Tag.PatientName))
            assertEquals("1.2.3.4", ds.string(Tag.SeriesInstanceUID))
            assertEquals(3, ds.int(Tag.Rows)); assertEquals(4, ds.int(Tag.Columns))
            assertArrayEquals(doubleArrayOf(-100.0, -100.0, 0.0), ds.doubles(Tag.ImagePositionPatient), 1e-9)
            assertEquals(listOf(Window(40.0, 400.0), Window(-600.0, 1500.0)), Voi.fileWindows(ds))
            assertNull(ImageInfo.skipReason(f))
            val frame = PixelDecoder.decode(f)
            assertEquals(4, frame.width); assertEquals(3, frame.height)
            assertArrayEquals("$ts", ctPixels.map { it.toInt() }.toIntArray(), IntArray(12) { frame.stored(it) })
            assertEquals(-1024.0, Voi.rescale(ds).apply(0), 0.0)
        }
    }

    @Test fun stopAtPixelDataSkipsPixels() {
        val f = parse(ctImage(TransferSyntax.EXPLICIT_LE, ctPixels, 3, 4), stop = true)
        assertTrue(f.hasPixelData)
        assertNull(f.pixelData)
        assertEquals(3, f.dataset.int(Tag.Rows))
        assertNull(ImageInfo.skipReason(f))
    }

    @Test fun noPreambleNoMetaImplicit8Bit() {
        val px = byteArrayOf(0, 10, 127, -128, -1, 5)
        val bytes = DicomWriter(TransferSyntax.IMPLICIT_LE, preamble = false, meta = false).apply {
            str(Tag.Modality, VR.CS, "OT")
            us(Tag.SamplesPerPixel, 1)
            str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            us(Tag.Rows, 2); us(Tag.Columns, 3)
            us(Tag.BitsAllocated, 8); us(Tag.BitsStored, 8); us(Tag.HighBit, 7); us(Tag.PixelRepresentation, 0)
            pixels8(px)
        }.bytes()
        val f = parse(bytes)
        assertEquals(TransferSyntax.IMPLICIT_LE, f.transferSyntax)
        val fr = PixelDecoder.decode(f)
        assertArrayEquals(intArrayOf(0, 10, 127, 128, 255, 5), IntArray(6) { fr.stored(it) })
    }

    @Test fun bitsStoredMaskingAndSignExtension() {
        // 12 bits stored in 16 allocated, junk in the top nibble.
        val raw = shortArrayOf(0xF7FF.toShort(), 0x0800, 0xA001.toShort(), 0x0FFF)
        fun img(signed: Int) = DicomWriter(TransferSyntax.EXPLICIT_LE).apply {
            us(Tag.SamplesPerPixel, 1); str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            us(Tag.Rows, 2); us(Tag.Columns, 2)
            us(Tag.BitsAllocated, 16); us(Tag.BitsStored, 12); us(Tag.HighBit, 11); us(Tag.PixelRepresentation, signed)
            pixels16(raw)
        }.bytes()
        val u = PixelDecoder.decode(parse(img(0)))
        assertArrayEquals(intArrayOf(0x7FF, 0x800, 0x001, 0xFFF), IntArray(4) { u.stored(it) })
        val s = PixelDecoder.decode(parse(img(1)))
        assertArrayEquals(intArrayOf(2047, -2048, 1, -1), IntArray(4) { s.stored(it) })
        assertEquals(-2048, s.info.minStored); assertEquals(2047, s.info.maxStored)
    }

    @Test fun monochrome1IsFlagged() {
        val f = parse(ctImage(TransferSyntax.EXPLICIT_LE, ctPixels, 3, 4) {
            // A later duplicate of (0028,0004) overrides the MONOCHROME2 written earlier.
            str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME1")
        })
        assertTrue(ImageInfo.of(f.dataset).invertedGrayscale)
    }

    @Test fun rgbPlanarAndInterleavedMatch() {
        // 2 pixels: red, teal
        fun img(planar: Int, data: ByteArray) = DicomWriter(TransferSyntax.EXPLICIT_LE).apply {
            us(Tag.SamplesPerPixel, 3); str(Tag.PhotometricInterpretation, VR.CS, "RGB"); us(Tag.PlanarConfiguration, planar)
            us(Tag.Rows, 1); us(Tag.Columns, 2)
            us(Tag.BitsAllocated, 8); us(Tag.BitsStored, 8); us(Tag.HighBit, 7); us(Tag.PixelRepresentation, 0)
            pixels8(data)
        }.bytes()
        val inter = PixelDecoder.decode(parse(img(0, byteArrayOf(-1, 0, 0, 0, -128, -128))))
        val planar = PixelDecoder.decode(parse(img(1, byteArrayOf(-1, 0, 0, -128, 0, -128))))
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF008080.toInt()), inter.argb)
        assertArrayEquals(inter.argb, planar.argb)
    }

    @Test fun multiFrame() {
        val px = ShortArray(12) { (it * 10).toShort() } // 3 frames of 2x2
        val f = parse(ctImage(TransferSyntax.EXPLICIT_LE, px, 2, 2) { str(Tag.NumberOfFrames, VR.IS, "3") })
        assertEquals(3, ImageInfo.of(f.dataset).frames)
        val fr = PixelDecoder.decode(f, 2)
        assertArrayEquals(intArrayOf(80, 90, 100, 110), IntArray(4) { fr.stored(it) })
    }

    @Test fun nestedSequencesUndefinedAndDefinedLength() {
        for (ts in listOf(TransferSyntax.IMPLICIT_LE, TransferSyntax.EXPLICIT_LE, TransferSyntax.EXPLICIT_BE)) {
            for (undefined in listOf(true, false)) {
                val bytes = ctImage(ts, ctPixels, 3, 4) {
                    seq(0x00081115, undefined, {
                        str(Tag.SOPInstanceUID, VR.UI, "9.9")
                        seq(0x0008114A, undefined, { str(Tag.Modality, VR.CS, "SR") }, { us(Tag.Rows, 7) })
                    }, { str(Tag.StudyDescription, VR.LO, "item2") })
                }
                val f = parse(bytes)
                val items = f.dataset.sequence(0x00081115)
                assertEquals("$ts undefined=$undefined", 2, items.size)
                assertEquals("9.9", items[0].string(Tag.SOPInstanceUID))
                val inner = items[0].sequence(0x0008114A)
                assertEquals(2, inner.size)
                assertEquals(7, inner[1].int(Tag.Rows))
                assertEquals("item2", items[1].string(Tag.StudyDescription))
                // Top-level elements after the sequence still parse correctly.
                assertEquals(3, f.dataset.int(Tag.Rows))
                assertEquals(-1024, PixelDecoder.decode(f).stored(1))
            }
        }
    }

    @Test fun encapsulatedIsUnsupportedNotCrash() {
        val bytes = DicomWriter(TransferSyntax.EXPLICIT_LE, tsUid = "1.2.840.10008.1.2.4.70").apply {
            str(Tag.Modality, VR.CS, "CT")
            us(Tag.SamplesPerPixel, 1); str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            us(Tag.Rows, 2); us(Tag.Columns, 2); us(Tag.BitsAllocated, 16); us(Tag.BitsStored, 16)
            encapsulatedPixels(byteArrayOf(1, 2, 3, 4))
            str(0xFFFCFFFC.toInt(), VR.OB, "") // trailing padding after pixel data
        }.bytes()
        for (stop in listOf(true, false)) {
            val f = parse(bytes, stop)
            assertEquals(TransferSyntax.ENCAPSULATED, f.transferSyntax)
            assertTrue(f.encapsulated)
            assertEquals(SkipReason.UNSUPPORTED_TRANSFER_SYNTAX, ImageInfo.skipReason(f))
        }
    }

    @Test fun noPixelDataIsSkipped() {
        val bytes = DicomWriter(TransferSyntax.EXPLICIT_LE).str(Tag.Modality, VR.CS, "SR").bytes()
        assertEquals(SkipReason.NO_PIXEL_DATA, ImageInfo.skipReason(parse(bytes)))
    }

    @Test fun oneBitSegmentationIsSkipped() {
        val bytes = DicomWriter(TransferSyntax.EXPLICIT_LE).apply {
            str(Tag.Modality, VR.CS, "SEG")
            us(Tag.SamplesPerPixel, 1); str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
            us(Tag.Rows, 4); us(Tag.Columns, 4); us(Tag.BitsAllocated, 1); us(Tag.BitsStored, 1)
            pixels8(byteArrayOf(1, 2))
        }.bytes()
        assertEquals(SkipReason.UNSUPPORTED_PIXEL_FORMAT, ImageInfo.skipReason(parse(bytes)))
    }

    @Test(expected = DicomException::class)
    fun textFileIsNotDicom() {
        parse("hello, this is definitely not a DICOM file at all\n".toByteArray(), stop = true)
    }

    @Test fun utf8CharacterSet() {
        val bytes = DicomWriter(TransferSyntax.EXPLICIT_LE).apply {
            str(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192")
            raw(Tag.PatientName, VR.PN, "Müller^Jörg".toByteArray(Charsets.UTF_8).let { if (it.size % 2 == 1) it + ' '.code.toByte() else it })
        }.bytes()
        assertEquals("Müller^Jörg", parse(bytes).dataset.string(Tag.PatientName))
        assertFalse(parse(bytes).hasPixelData)
    }
}
