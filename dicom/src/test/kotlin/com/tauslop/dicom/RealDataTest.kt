package com.tauslop.dicom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Reads a real folder in place. Skipped unless run with
 * `-PDICOM_TEST_DIR=<dir>` (or env DICOM_TEST_DIR). Written against LIDC-IDRI-0006.
 */
class RealDataTest {

    private val dir: File? = System.getProperty("dicom.test.dir")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun scan(): SeriesBuilder.Result<File> {
        assumeTrue("DICOM_TEST_DIR not set", dir != null && dir.isDirectory)
        var notDicom = 0
        val scanned = dir!!.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            try { ScannedFile(f, DicomParser.parse(f, stopAtPixelData = true)) }
            catch (e: Exception) { notDicom++; null }
        }.toList()
        return SeriesBuilder.build(scanned, notDicom)
    }

    @Test fun lidcSeriesStructure() {
        val r = scan()
        println("series=${r.series.map { "${it.modality}:${it.size}" }} skipped=${r.skipped}")
        assertEquals(1, r.series.size)
        val ct = r.series.single()
        assertEquals("CT", ct.modality)
        assertEquals(133, ct.size)
        assertEquals(16, r.skipped.values.sum()) // 8 SEG + 8 SR

        // Positions strictly decrease in z (head first) with uniform spacing.
        val z = ct.slices.map { it.plane!!.position[2] }
        val steps = z.zipWithNext { a, b -> a - b }
        assertTrue("not head-first: ${z.take(3)}", steps.all { it > 0 })
        val mean = steps.average()
        assertTrue("uneven spacing $steps", steps.all { abs(it - mean) < 0.01 * mean + 1e-3 })
        println("z ${z.first()} → ${z.last()}, spacing $mean mm")
    }

    @Test fun lidcVolumeAndReslicing() {
        val ct = scan().series.single()
        val plan = (VolumeBuilder.plan(ct, Long.MAX_VALUE) as VolumeBuilder.Check.Ok).plan
        val decoded = HashMap<Int, Frame>()
        ct.slices.forEachIndexed { k, s ->
            val f = DicomParser.parse(s.source)
            val frame = PixelDecoder.decode(f, s.frame)
            if (k in listOf(0, 66, 132)) decoded[k] = frame
            plan.put(k, frame, Voi.rescale(f.dataset))
        }
        val vol = plan.finish()
        println("volume ${vol.nx}x${vol.ny}x${vol.nz} spacing ${vol.dx}, ${vol.dy}, ${vol.dz} mm, ${vol.byteSize / 1_000_000} MB")
        assertEquals(listOf(512, 512, 133), listOf(vol.nx, vol.ny, vol.nz))
        assertEquals(2.5, vol.dz, 1e-3)

        for ((k, frame) in decoded) {
            val plane = Reslicer.planeFor(vol, MprView.AXIAL, vol.toPatient(0.0, 0.0, k.toDouble()))
            val axial = Reslicer.sample(vol, plane)
            assertEquals(512, axial.width); assertEquals(512, axial.height)
            assertTrue("axial reslice differs from slice $k", (0 until 512 * 512).all { axial.stored(it) == frame.stored(it) })
        }

        val center = vol.center
        val cor = Reslicer.planeFor(vol, MprView.CORONAL, center)
        val expectedH = (132 * vol.dz / vol.dx).toInt() + 1
        assertTrue("coronal ${cor.width}x${cor.height}, expected height ~$expectedH", kotlin.math.abs(cor.height - expectedH) <= 1)
        val obl = Reslicer.planeFor(vol, MprView.CORONAL, center, Rotation.about(Vec3.X, Math.toRadians(30.0)))
        fun time(p: Plane): Double {
            repeat(3) { Reslicer.sample(vol, p) } // warm up the JIT
            val t = System.nanoTime()
            repeat(10) { Reslicer.sample(vol, p) }
            return (System.nanoTime() - t) / 10 / 1e6
        }
        println("reslice coronal ${cor.width}x${cor.height}: ${"%.1f".format(time(cor))} ms; " +
            "oblique 30° ${obl.width}x${obl.height}: ${"%.1f".format(time(obl))} ms (desktop JVM)")
    }

    @Test fun lidcSlicesDecodeToPlausibleHu() {
        val ct = scan().series.single()
        for (s in ct.slices) {
            val f = DicomParser.parse(s.source)
            val frame = PixelDecoder.decode(f, s.frame)
            assertEquals(512, frame.width); assertEquals(512, frame.height)
            val r = Voi.rescale(f.dataset)
            // Corner pixels sit outside the body: air or padding, well below soft tissue.
            assertTrue("corner HU ${r.apply(frame.stored(0))} in ${s.source.name}", r.apply(frame.stored(0)) < -500)
            // The image centre includes body tissue somewhere along the middle row.
            val mid = 256 * 512
            val maxMid = (0 until 512).maxOf { r.apply(frame.stored(mid + it)) }
            assertTrue("no tissue on middle row in ${s.source.name}", maxMid > -200)
        }
        val first = DicomParser.parse(ct.slices.first().source).dataset
        println("windows=${Voi.fileWindows(first)} rescale=${Voi.rescale(first).slope},${Voi.rescale(first).intercept} info=${ImageInfo.of(first).let { "${it.bitsStored}/${it.bitsAllocated} signed=${it.signed} ${it.photometric}" }}")
    }
}
