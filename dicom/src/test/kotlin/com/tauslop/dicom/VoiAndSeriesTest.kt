package com.tauslop.dicom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class VoiAndSeriesTest {

    @Test fun linearWindowMatchesStandard() {
        // c=40, w=400 → [-160, 240] maps to [0, 255]
        assertEquals(0, Voi.linear(-1000.0, 40.0, 400.0))
        assertEquals(0, Voi.linear(-160.0, 40.0, 400.0))
        assertEquals(128, Voi.linear(40.0, 40.0, 400.0))
        assertEquals(255, Voi.linear(240.0, 40.0, 400.0))
        assertEquals(255, Voi.linear(3000.0, 40.0, 400.0))
        // Width 1: hard threshold at c - 0.5
        assertEquals(0, Voi.linear(39.5, 40.0, 1.0))
        assertEquals(255, Voi.linear(39.6, 40.0, 1.0))
    }

    @Test fun lutAppliesRescaleAndInvert() {
        val r = Voi.Rescale(1.0, -1024.0)
        val lut = Voi.buildLut(-2048, 4095, r, Window(-600.0, 1500.0), invert = false)
        fun at(stored: Int) = lut[stored + 2048].toInt() and 0xFF
        assertEquals(Voi.linear(-1024.0, -600.0, 1500.0), at(0)) // stored 0 = -1024 HU
        assertEquals(0, at(-2048))  // -3072 HU, below the window
        assertEquals(128, at(424))  // -600 HU = window centre
        assertEquals(255, at(4095))
        val inv = Voi.buildLut(-2048, 4095, r, Window(-600.0, 1500.0), invert = true)
        assertEquals(255 - at(0), inv[2048].toInt() and 0xFF)
    }

    @Test fun autoWindowUsesPercentiles() {
        val px = ShortArray(100) { it.toShort() }
        val f = DicomParser.parse(ByteArrayInputStream(ctImage(TransferSyntax.EXPLICIT_LE, px, 10, 10)))
        val w = Voi.autoWindow(PixelDecoder.decode(f), Voi.rescale(f.dataset))
        // 1st percentile → stored 1, 99th → stored 99, minus 1024
        assertEquals(-1024.0 + 50, w.center, 0.0)
        assertEquals(98.0, w.width, 0.0)
    }

    private fun scanned(bytes: ByteArray, name: String) =
        ScannedFile(name, DicomParser.parse(ByteArrayInputStream(bytes), stopAtPixelData = true))

    @Test fun seriesGroupedAndSortedHeadFirst() {
        val px = ShortArray(4)
        // Instance numbers deliberately disagree with geometry; z is shuffled.
        val zs = listOf(-10.0, 20.0, 0.0, 10.0, -20.0)
        val files = zs.mapIndexed { i, z -> scanned(ctImage(TransferSyntax.EXPLICIT_LE, px, 2, 2, z = z, instance = 5 - i), "a$z") } +
            listOf(scanned(ctImage(TransferSyntax.IMPLICIT_LE, px, 2, 2, seriesUid = "9.9", instance = 2), "b2"),
                scanned(ctImage(TransferSyntax.IMPLICIT_LE, px, 2, 2, seriesUid = "9.9", instance = 1), "b1"),
                scanned(DicomWriter(TransferSyntax.EXPLICIT_LE).str(Tag.Modality, VR.CS, "SR").bytes(), "sr"))
        val r = SeriesBuilder.build(files, notDicom = 2)
        assertEquals(2, r.series.size)
        val a = r.series.first { it.seriesUid == "1.2.3.4" }
        assertEquals(listOf(20.0, 10.0, 0.0, -10.0, -20.0), a.slices.map { it.plane!!.position[2] })
        val b = r.series.first { it.seriesUid == "9.9" }
        // Same position for both → tie broken by instance number.
        assertEquals(listOf("b1", "b2"), b.slices.map { it.source })
        assertEquals(1, r.skipped[SkipReason.NO_PIXEL_DATA])
        assertEquals(2, r.skipped[SkipReason.NOT_DICOM])
    }

    @Test fun fallsBackToInstanceNumberWithoutGeometry() {
        val files = listOf(3, 1, 2).map { n ->
            val b = DicomWriter(TransferSyntax.EXPLICIT_LE).apply {
                str(Tag.SeriesInstanceUID, VR.UI, "5.5"); str(Tag.InstanceNumber, VR.IS, "$n")
                us(Tag.SamplesPerPixel, 1); str(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2")
                us(Tag.Rows, 1); us(Tag.Columns, 2); us(Tag.BitsAllocated, 8); us(Tag.BitsStored, 8)
                pixels8(byteArrayOf(0, 1))
            }.bytes()
            scanned(b, "i$n")
        }
        val s = SeriesBuilder.build(files).series.single()
        assertEquals(listOf("i1", "i2", "i3"), s.slices.map { it.source })
        assertTrue(s.slices.all { it.plane == null })
    }
}
