package com.tauslop.dicom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.PI

class MprTest {

    // 6 columns (i), 4 rows (j), 5 slices (k) at z = 0, -1, ... (head first, 1 mm apart), 0.5 mm pixels.
    private val nx = 6
    private val ny = 4
    private fun value(i: Int, j: Int, k: Int) = 1000 + i + 10 * j + 100 * k

    private fun files(zs: List<Double>, rows: Int = ny): List<ScannedFile<DicomFile>> = zs.mapIndexed { k, z ->
        val px = ShortArray(rows * nx) { val i = it % nx; val j = it / nx; value(i, j, k).toShort() }
        val f = DicomParser.parse(ByteArrayInputStream(ctImage(TransferSyntax.EXPLICIT_LE, px, rows, nx, z = z, instance = k + 1)))
        ScannedFile(f, f)
    }

    private fun build(zs: List<Double> = listOf(0.0, -1.0, -2.0, -3.0, -4.0), maxBytes: Long = Long.MAX_VALUE): Volume {
        val series = SeriesBuilder.build(files(zs)).series.single()
        val plan = (VolumeBuilder.plan(series, maxBytes) as VolumeBuilder.Check.Ok).plan
        series.slices.forEachIndexed { k, s -> plan.put(k, PixelDecoder.decode(s.source, s.frame), Voi.rescale(s.header)) }
        return plan.finish()
    }

    private fun Frame.at(u: Int, v: Int) = stored(v * width + u)

    @Test fun builderGeometry() {
        val vol = build()
        assertEquals(listOf(6, 4, 5), listOf(vol.nx, vol.ny, vol.nz))
        assertEquals(0.5, vol.dx, 1e-9); assertEquals(0.5, vol.dy, 1e-9); assertEquals(1.0, vol.dz, 1e-9)
        assertEquals(Vec3(0.0, 0.0, -1.0), vol.sliceDir)
        assertEquals(Vec3(-100.0, -100.0, 0.0), vol.origin)
        assertEquals(-1024.0, vol.rescale.intercept, 0.0)
        assertEquals(value(5, 3, 4), vol.data[vol.index(5, 3, 4)].toInt())
    }

    @Test fun axialReproducesSlices() {
        val vol = build()
        for (k in 0 until 5) {
            val cursor = vol.toPatient(0.0, 0.0, k.toDouble())
            val plane = Reslicer.planeFor(vol, MprView.AXIAL, cursor, pixelMm = 0.5)
            assertEquals(6, plane.width); assertEquals(4, plane.height)
            val f = Reslicer.sample(vol, plane)
            for (j in 0 until ny) for (i in 0 until nx) assertEquals(value(i, j, k), f.at(i, j))
            val d = Reslicer.depth(vol, MprView.AXIAL.scroll, cursor)
            assertEquals(k, d.index); assertEquals(5, d.count)
        }
    }

    @Test fun coronalIsHeadUpWithInterpolation() {
        val vol = build()
        val f = Reslicer.sample(vol, Reslicer.planeFor(vol, MprView.CORONAL, vol.toPatient(0.0, 2.0, 0.0), pixelMm = 0.5))
        assertEquals(6, f.width)
        assertEquals(9, f.height) // 4 mm of z at 0.5 mm per pixel
        for (i in 0 until nx) for (k in 0 until 5) {
            assertEquals(value(i, 2, k), f.at(i, 2 * k)) // row 0 = most superior slice
            if (k < 4) assertEquals(value(i, 2, k) + 50, f.at(i, 2 * k + 1)) // halfway between slices
        }
    }

    @Test fun sagittalIsAnteriorLeft() {
        val vol = build()
        val f = Reslicer.sample(vol, Reslicer.planeFor(vol, MprView.SAGITTAL, vol.toPatient(3.0, 0.0, 0.0), pixelMm = 0.5))
        assertEquals(4, f.width)
        for (j in 0 until ny) for (k in 0 until 5) assertEquals(value(3, j, k), f.at(j, 2 * k))
    }

    @Test fun rotatingCoronal90AboutZGivesSagittal() {
        val vol = build()
        val cursor = vol.toPatient(3.0, 1.0, 2.0)
        val r = Rotation.about(Vec3.Z, PI / 2)
        val rotated = Reslicer.sample(vol, Reslicer.planeFor(vol, MprView.CORONAL, cursor, r, pixelMm = 0.5))
        val sag = Reslicer.sample(vol, Reslicer.planeFor(vol, MprView.SAGITTAL, cursor, pixelMm = 0.5))
        assertEquals(sag.width, rotated.width); assertEquals(sag.height, rotated.height)
        assertArrayEquals(sag.gray, rotated.gray)
        assertEquals(90.0, Reslicer.obliquity(MprView.CORONAL, r), 1e-6)
        assertEquals(0.0, Reslicer.obliquity(MprView.AXIAL, r), 1e-6)
    }

    @Test fun obliqueOutsideIsFilled() {
        val vol = build()
        val plane = Reslicer.planeFor(vol, MprView.AXIAL, vol.center, Rotation.about(Vec3.Z, PI / 4), pixelMm = 0.25)
        val f = Reslicer.sample(vol, plane)
        val corner = vol.toVoxel(plane.pointAt(0.0, 0.0))
        assertTrue("corner should be outside: $corner", corner.x < 0 || corner.y < 0 || corner.x > nx - 1 || corner.y > ny - 1)
        assertEquals(vol.minValue.toInt(), f.at(0, 0))
        // Centre of an oblique plane through the volume centre interpolates the centre voxel value.
        val c = vol.toVoxel(plane.pointAt((plane.width - 1) / 2.0, (plane.height - 1) / 2.0))
        assertEquals(Vec3(2.5, 1.5, 2.0).toString(), Vec3(round(c.x), round(c.y), round(c.z)).toString())
    }

    private fun round(d: Double) = Math.round(d * 1e6) / 1e6

    @Test fun moveToIndexSnapsAlongScroll() {
        val vol = build()
        val p = Reslicer.moveToIndex(vol, MprView.AXIAL.scroll, vol.toPatient(2.0, 1.0, 0.0), 3)
        val v = vol.toVoxel(p)
        assertEquals(3.0, v.z, 1e-9); assertEquals(2.0, v.x, 1e-9); assertEquals(1.0, v.y, 1e-9)
    }

    @Test fun downsamplesWhenOverBudget() {
        val vol = build(maxBytes = 6L * 4 * 5 * 2 - 1)
        assertEquals(3, vol.nx); assertEquals(2, vol.ny)
        assertEquals(1.0, vol.dx, 1e-9)
        assertEquals(1006, vol.data[vol.index(0, 0, 0)].toInt()) // mean of 1000,1001,1010,1011 = 1005.5 → 1006
    }

    @Test fun rejectsIrregularSeries() {
        val uneven = SeriesBuilder.build(files(listOf(0.0, -1.0, -3.0))).series.single()
        assertTrue(VolumeBuilder.plan(uneven, Long.MAX_VALUE) is VolumeBuilder.Check.Rejected)
        val one = SeriesBuilder.build(files(listOf(0.0))).series.single()
        assertTrue(VolumeBuilder.plan(one, Long.MAX_VALUE) is VolumeBuilder.Check.Rejected)
    }
}
