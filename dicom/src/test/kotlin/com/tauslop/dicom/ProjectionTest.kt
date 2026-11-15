package com.tauslop.dicom

import com.tauslop.dicom.vr.OrbitCamera
import com.tauslop.dicom.vr.Projection
import com.tauslop.dicom.vr.RenderMode
import com.tauslop.dicom.vr.TransferFunction
import com.tauslop.dicom.vr.VrPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {

    /** 1 mm isotropic n³ volume, standard axial orientation (as in VrMathTest). */
    private fun volume(n: Int, value: (i: Int, j: Int, k: Int) -> Int): Volume {
        val data = ShortArray(n * n * n)
        for (k in 0 until n) for (j in 0 until n) for (i in 0 until n) data[(k * n + j) * n + i] = value(i, j, k).toShort()
        return Volume(n, n, n, data, Vec3(0.0, 0.0, 0.0), Vec3.X, Vec3.Y, -Vec3.Z, 1.0, 1.0, 1.0,
            Voi.Rescale(1.0, 0.0), "MONOCHROME2")
    }

    /** Ray along +Y (anterior → posterior) through voxel column (i, k), starting outside. */
    private fun ray(i: Int, k: Int, vol: Volume) = vol.toPatient(i.toDouble(), -5.0, k.toDouble()) to Vec3.Y

    @Test fun mipTakesMaximumAlongRay() {
        val vol = volume(32) { i, j, k -> if (i == 10 && k == 12 && j == 20) 1200 else if (j in 4..27) 40 else -1000 }
        val (o, d) = ray(10, 12, vol)
        val v = Projection.project(vol, RenderMode.MIP, o, d, stepMm = 0.25)!!
        assertTrue("peak sampled, got $v", v > 1000)
        val (o2, d2) = ray(3, 3, vol)
        assertEquals(40.0, Projection.project(vol, RenderMode.MIP, o2, d2, 0.25)!!, 1e-6)
    }

    @Test fun minipIgnoresAirOutsideBody() {
        // Air (j<2, j>29) around a soft-tissue body containing a lung-like pocket at j 14..18.
        val vol = volume(32) { i, j, k ->
            when {
                j !in 2..29 -> -1000
                i in 8..20 && k in 8..20 && j in 14..18 -> -850
                else -> 40
            }
        }
        val (o, d) = ray(14, 14, vol)
        assertEquals(-850.0, Projection.project(vol, RenderMode.MINIP, o, d, 0.25)!!, 1e-6)
        // Solid body column: no hole, so the flat body level, not the surrounding air.
        val (o2, d2) = ray(2, 2, vol)
        assertEquals(Projection.BODY_HU, Projection.project(vol, RenderMode.MINIP, o2, d2, 0.25)!!, 1e-6)
        // A ray through air only never enters the body.
        val air = volume(16) { _, _, _ -> -1000 }
        val (o3, d3) = ray(8, 8, air)
        assertNull(Projection.project(air, RenderMode.MINIP, o3, d3, 0.5))
    }

    @Test fun minipSkipsGapToThinTable() {
        // Body j 2..19, air gap j 20..23, 3 mm "table" j 24..26 (thinner than the body run), air.
        val vol = volume(32) { _, j, _ ->
            when (j) {
                in 2..19 -> 40
                in 24..26 -> 300
                else -> -1000
            }
        }
        val (o, d) = ray(16, 16, vol)
        assertEquals(Projection.BODY_HU, Projection.project(vol, RenderMode.MINIP, o, d, 0.25)!!, 1e-6)
    }

    @Test fun mipMissesBox() {
        val vol = volume(8) { _, _, _ -> 0 }
        assertNull(Projection.project(vol, RenderMode.MIP, Vec3(100.0, -10.0, 100.0), Vec3.Y, 0.5))
        assertNotNull(Projection.project(vol, RenderMode.MIP, vol.center - Vec3.Y * 20.0, Vec3.Y, 0.5))
    }

    @Test fun pickFindsDepthPerMode() {
        // Body j 2..29; bone voxel at j 20; air pocket j 14..16; ray along +Y through (16, 16).
        val vol = volume(32) { _, j, _ ->
            when {
                j !in 2..29 -> -1000
                j == 20 -> 1500
                j in 14..16 -> -900
                else -> 40
            }
        }
        val (o, d) = ray(16, 16, vol)
        fun depth(m: RenderMode, tf: TransferFunction = TransferFunction.BONE) =
            Projection.pick(vol, m, tf, o, d, 0.25)?.let { vol.toVoxel(it).y }
        assertEquals(20.0, depth(RenderMode.MIP)!!, 0.2)
        assertEquals(15.0, depth(RenderMode.MINIP)!!, 1.1)
        // Bone preset: soft tissue is clear, so the visible surface is the bone voxel's front.
        val vr = depth(RenderMode.VR)!!
        assertTrue("VR surface at $vr", vr in 19.0..20.1)
        // Picked points lie on the ray.
        val p = Projection.pick(vol, RenderMode.MIP, TransferFunction.BONE, o, d, 0.25)!!
        assertEquals(o.x, p.x, 1e-9); assertEquals(o.z, p.z, 1e-9)
        // Nothing visible / no hole: no pick.
        val solid = volume(32) { _, j, _ -> if (j in 2..29) 40 else -1000 }
        val (o2, d2) = ray(16, 16, solid)
        assertNull(Projection.pick(solid, RenderMode.VR, TransferFunction.BONE, o2, d2, 0.5))
        assertNull(Projection.pick(solid, RenderMode.MINIP, TransferFunction.BONE, o2, d2, 0.5))
    }

    @Test fun grayWindowIsLinear() {
        val w = Window(300.0, 1500.0)
        assertEquals(0.0, Projection.gray(-450.0, w), 1e-12)
        assertEquals(0.5, Projection.gray(300.0, w), 1e-12)
        assertEquals(1.0, Projection.gray(1050.0, w), 1e-12)
        assertEquals(1.0, Projection.gray(3000.0, w), 1e-12)
    }

    @Test fun defaultsAndPresets() {
        assertEquals(Window(300.0, 1500.0, "MIP"), Projection.defaultWindow(RenderMode.MIP))
        assertEquals(Window(-600.0, 1500.0, "MinIP"), Projection.defaultWindow(RenderMode.MINIP))
        assertTrue(Projection.presets(RenderMode.VR).isEmpty())
        for (m in listOf(RenderMode.MIP, RenderMode.MINIP)) {
            val labels = Projection.presets(m).map { it.label!! }
            assertEquals(labels.size, labels.toSet().size)
            assertTrue(labels.none { it.any { c -> c in ";=," } })
        }
    }

    @Test fun vrPlaceRoundTrips() {
        val cam = OrbitCamera(Vec3.ZERO, 300.0).rotated(37f, -12f, 400f)
        val p = VrPlace(
            rotation = cam.rotation, zoom = 2.5, panX = -12.25, panY = 3.5, mode = RenderMode.MINIP,
            preset = TransferFunction.LUNG.name, tfWindow = Window(-500.0, 900.0),
            mipWindow = Window(250.0, 1400.0), minipWindow = Window(-850.0, 600.0, "Airways"),
            shading = false, threeDMain = true,
        )
        val back = VrPlace.decode(p.encode())!!
        assertEquals(p.copy(rotation = back.rotation), back)
        for ((a, b) in listOf(p.rotation.cx to back.rotation.cx, p.rotation.cy to back.rotation.cy, p.rotation.cz to back.rotation.cz)) {
            assertTrue((a - b).length < 1e-9)
        }
        // Defaults (no TF adjustment) round-trip too.
        assertEquals(VrPlace(), VrPlace.decode(VrPlace().encode()))
    }

    @Test fun vrPlaceRejectsMalformed() {
        val good = VrPlace().encode()
        assertNull(VrPlace.decode(null))
        assertNull(VrPlace.decode(""))
        assertNull(VrPlace.decode("garbage"))
        assertNull(VrPlace.decode(good.replace("v=1", "v=2")))
        assertNull(VrPlace.decode(good.replace("mode=VR", "mode=XRAY")))
        assertNull(VrPlace.decode(good.replace("preset=Bone", "preset=Nope")))
        assertNull(VrPlace.decode(good.replace("zoom=1.0", "zoom=NaN")))
        assertNull(VrPlace.decode(good.replace("rot=1.0", "rot=5.0")))
        assertNull(VrPlace.decode(good.replace("mip=300.0,1500.0", "mip=300.0,0.0")))
        assertNull(VrPlace.decode(good.replace(";shade=true", "")))
    }
}
