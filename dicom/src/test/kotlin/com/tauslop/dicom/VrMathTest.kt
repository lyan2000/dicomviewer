package com.tauslop.dicom

import com.tauslop.dicom.vr.OrbitCamera
import com.tauslop.dicom.vr.RayGeometry
import com.tauslop.dicom.vr.TransferFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VrMathTest {

    private fun assertVec(e: Vec3, a: Vec3, eps: Double = 1e-9) {
        assertTrue("expected $e, got $a", (e - a).length < eps)
    }

    /** 1 mm isotropic, standard axial orientation, slices head-first (k increases toward feet). */
    private fun volume(n: Int, value: (i: Int, j: Int, k: Int) -> Int): Volume {
        val data = ShortArray(n * n * n)
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) data[(k * n + j) * n + i] = value(i, j, k).toShort()
        return Volume(n, n, n, data, Vec3(0.0, 0.0, 0.0), Vec3.X, Vec3.Y, -Vec3.Z, 1.0, 1.0, 1.0,
            Voi.Rescale(1.0, 0.0), "MONOCHROME2")
    }

    @Test fun coronalDefaultAxes() {
        val cam = OrbitCamera(Vec3.ZERO, 100.0)
        assertVec(Vec3.Y, cam.forward)   // anterior → posterior
        assertVec(Vec3.Z, cam.up)        // head up
        assertVec(Vec3.X, cam.right)     // patient left on screen right
        assertVec(cam.right, cam.forward cross cam.up)
    }

    @Test fun quickViewsMatchMprAxes() {
        val cam = OrbitCamera(Vec3.ZERO, 100.0).rotated(30f, 20f, 100f).zoomed(3.0).panned(5f, 5f, 100, 100)
        for (v in MprView.entries) {
            val c = cam.facing(v)
            assertVec(v.right, c.right)
            assertVec(-v.down, c.up)
            assertVec(c.right, c.forward cross c.up) // still a proper rotation
            assertEquals(1.0, c.zoom, 0.0); assertEquals(0.0, c.panX, 0.0); assertEquals(0.0, c.panY, 0.0)
        }
        assertTrue(cam.facing(MprView.CORONAL).rotation.isIdentity)
        assertVec(-Vec3.X, cam.facing(MprView.SAGITTAL).forward) // from the patient's left
        assertVec(Vec3.Z, cam.facing(MprView.AXIAL).forward)     // from the feet
    }

    @Test fun projectInvertsRay() {
        val cam = OrbitCamera(Vec3(5.0, -3.0, 40.0), 250.0).rotated(41f, -17f, 300f).zoomed(1.7).panned(12f, -7f, 300, 200)
        val (o, d) = cam.ray(73.0, 151.0, 300, 200)
        for (depth in listOf(0.0, 100.0, 517.0)) { // any point on the ray lands on the same pixel
            val (u, v) = cam.project(o + d * depth, 300, 200)
            assertEquals(73.5, u, 1e-9); assertEquals(151.5, v, 1e-9)
        }
        // The target sits at the view centre when there is no pan.
        val (cu, cv) = cam.copy(panX = 0.0, panY = 0.0).project(cam.target, 300, 200)
        assertEquals(150.0, cu, 1e-9); assertEquals(100.0, cv, 1e-9)
    }

    @Test fun dragDirections() {
        val cam = OrbitCamera(Vec3.ZERO, 100.0)
        // Half-view drag = 90°. Dragging right shows the patient's right side: camera looks toward +X.
        val r = cam.rotated(50f, 0f, 100f)
        assertVec(Vec3.X, r.forward, 1e-6); assertVec(Vec3.Z, r.up, 1e-6)
        // Dragging down tips the head toward the viewer: camera looks down (−Z) from above.
        val d = cam.rotated(0f, 50f, 100f)
        assertVec(-Vec3.Z, d.forward, 1e-6)
    }

    @Test fun rotationStaysOrthonormalAndResets() {
        var cam = OrbitCamera(Vec3.ZERO, 100.0)
        val rnd = Random(1)
        repeat(2000) { cam = cam.rotated(rnd.nextFloat() * 10 - 5, rnd.nextFloat() * 10 - 5, 300f) }
        val r = cam.rotation
        for (v in listOf(r.cx, r.cy, r.cz)) assertEquals(1.0, v.length, 1e-9)
        assertEquals(0.0, r.cx dot r.cy, 1e-9); assertEquals(0.0, r.cy dot r.cz, 1e-9)
        assertVec(r.cz, r.cx cross r.cy) // right-handed
        assertTrue(cam.reset().rotation.isIdentity)
    }

    @Test fun defaultFramingFitsFrontView() {
        // 20×20 voxels at 1 mm, 10 slices at 2 mm: front view spans 19 mm (x) by 18 mm (z).
        val vol = Volume(20, 20, 10, ShortArray(4000), Vec3.ZERO, Vec3.X, Vec3.Y, -Vec3.Z, 1.0, 1.0, 2.0,
            Voi.Rescale(1.0, 0.0), "MONOCHROME2")
        val cam = OrbitCamera.forVolume(vol)
        assertEquals(19.0 * 1.02, cam.fieldMm, 1e-9)
        assertVec(vol.center, cam.target)
        // The ray origin starts outside the volume so the whole box is traversed.
        val (o, d) = cam.ray(0.0, 0.0, 10, 10)
        assertTrue(((vol.center - o) dot d) > 20.0)
    }

    @Test fun centreRayPassesThroughTarget() {
        val target = Vec3(10.0, -20.0, 30.0)
        val cam = OrbitCamera(target, 200.0).rotated(17f, -9f, 100f).zoomed(2.0)
        val (o, d) = cam.ray(50.0, 50.0, 101, 101)
        val toTarget = target - o
        assertVec(Vec3.ZERO, toTarget - d * (toTarget dot d), 1e-9) // target lies on the ray
        assertEquals(1.0, d.length, 1e-12)
        // Pan moves the image: a ray offset by the pan now hits the target.
        val panned = cam.panned(10f, 0f, 101, 101)
        val (o2, d2) = panned.ray(60.0, 50.0, 101, 101)
        val t2 = target - o2
        assertVec(Vec3.ZERO, t2 - d2 * (t2 dot d2), 1e-9)
    }

    @Test fun patientToTextureMapsVoxelCentres() {
        val vol = Volume(4, 3, 5, ShortArray(60), Vec3(-10.0, 5.0, 0.0), Vec3.X, Vec3.Y, -Vec3.Z,
            0.5, 0.5, 2.0, Voi.Rescale(1.0, 0.0), "MONOCHROME2")
        val m = RayGeometry.patientToTexture(vol)
        assertVec(Vec3(0.5 / 4, 0.5 / 3, 0.5 / 5), RayGeometry.applyPoint(m, vol.toPatient(0.0, 0.0, 0.0)), 1e-6)
        assertVec(Vec3(3.5 / 4, 2.5 / 3, 4.5 / 5), RayGeometry.applyPoint(m, vol.toPatient(3.0, 2.0, 4.0)), 1e-6)
    }

    @Test fun boxIntersection() {
        val hit = RayGeometry.intersectBox(Vec3(-1.0, 0.5, 0.5), Vec3(1.0, 0.0, 0.0))!!
        assertEquals(1.0, hit.first, 1e-12); assertEquals(2.0, hit.second, 1e-12)
        assertNull(RayGeometry.intersectBox(Vec3(-1.0, 2.0, 0.5), Vec3(1.0, 0.0, 0.0)))
        assertNull(RayGeometry.intersectBox(Vec3(2.0, 0.5, 0.5), Vec3(1.0, 0.0, 0.0))) // box behind
    }

    @Test fun transferFunctionInterpolationAndLut() {
        val tf = TransferFunction.BONE
        val mid = tf.sample(225.0) // halfway between 150 (a=0) and 300 (a=0.35)
        assertEquals(0.175f, mid[3], 1e-6f)
        assertEquals(0f, tf.sample(-1000.0)[3], 0f)
        val lut = tf.lut()
        assertEquals(TransferFunction.LUT_SIZE * 4, lut.size)
        assertEquals(255, lut[lut.size - 1].toInt() and 0xFF) // opaque at 3071 HU
        assertEquals(0, lut[3].toInt())                         // transparent at -1024 HU
    }

    @Test fun tissuesPresetColourBands() {
        val tf = TransferFunction.TISSUES
        val fat = tf.sample(-110.0)
        assertTrue("fat yellow", fat[0] > fat[2] && fat[1] > fat[2] && fat[3] < 0.01f)
        val soft = tf.sample(40.0)
        assertTrue("soft tissue red, faint", soft[0] > 2 * soft[1] && soft[0] > 2 * soft[2] && soft[3] < 0.02f)
        val disc = tf.sample(110.0)
        assertTrue("disc blue", disc[2] > disc[0] && disc[2] > disc[1])
        val spongy = tf.sample(320.0)
        assertTrue("cancellous tan", spongy[0] > spongy[2] && spongy[3] > 0.3f)
        val cortical = tf.sample(1500.0)
        assertTrue("cortical white, opaque", cortical.take(3).all { it > 0.95f } && cortical[3] > 0.9f)
        assertTrue(TransferFunction.PRESETS.contains(tf))
    }

    @Test fun opacityCorrectionComposes() {
        val a = 0.4f
        assertEquals(a, TransferFunction.correctOpacity(a, TransferFunction.REFERENCE_STEP_MM), 1e-6f)
        val half = TransferFunction.correctOpacity(a, TransferFunction.REFERENCE_STEP_MM / 2)
        assertEquals(a, 1 - (1 - half) * (1 - half), 1e-6f) // two half steps = one full step
    }

    @Test fun windowedMovesVisibleRange() {
        val w = TransferFunction.BONE.windowed(center = 1000.0, width = 500.0)
        val r = w.visibleRange
        assertEquals(1000.0, (r.start + r.endInclusive) / 2, 1e-9)
        assertEquals(500.0, r.endInclusive - r.start, 1e-9)
    }

    @Test fun compositeShowsPatientLeftOnScreenRight() {
        // Air everywhere except a bone block on the patient's left (+x, high i).
        val n = 20
        val vol = volume(n) { i, j, k -> if (i in 14..17 && j in 8..11 && k in 8..11) 1500 else -1000 }
        val cam = OrbitCamera.forVolume(vol)
        val w = 41
        val block = vol.toPatient(15.5, 9.5, 9.5)
        val s = cam.mmPerPixel(w, w)
        val px = ((block - cam.target) dot cam.right) / s + w / 2.0 - 0.5
        val py = -((block - cam.target) dot cam.up) / s + w / 2.0 - 0.5
        assertTrue("block should be on screen right, px=$px", px > w / 2.0)
        val (o, d) = cam.ray(Math.round(px).toDouble(), Math.round(py).toDouble(), w, w)
        val hit = RayGeometry.composite(vol, TransferFunction.BONE, o, d, stepMm = 0.5)
        assertTrue("block pixel opacity ${hit[3]}", hit[3] > 0.9f)
        val (o2, d2) = cam.ray((w - 1 - Math.round(px)).toDouble(), Math.round(py).toDouble(), w, w)
        assertEquals(0f, RayGeometry.composite(vol, TransferFunction.BONE, o2, d2, stepMm = 0.5)[3], 0f)
    }
}
