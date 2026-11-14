package com.tauslop.dicomviewer.mpr

import com.tauslop.dicom.MprView
import com.tauslop.dicom.Plane
import com.tauslop.dicom.Reslicer
import com.tauslop.dicom.Rotation
import com.tauslop.dicom.Vec3
import com.tauslop.dicom.Volume

/** MPR navigation state over a volume: shared cursor, plane rotation and which view is main. */
class MprSession(val volume: Volume) {
    var cursor: Vec3 = volume.center
    var rotation: Rotation = Rotation.IDENTITY
    var main: MprView = MprView.AXIAL

    /** Small views in fixed order, excluding the main one. */
    val smallViews: List<MprView> get() = MprView.entries.filter { it != main }

    fun scrollDir(view: MprView): Vec3 = rotation.apply(view.scroll).normalized()

    fun plane(view: MprView, maxDim: Int = 1024): Plane =
        Reslicer.planeFor(volume, view, cursor, rotation, maxDim = maxDim)

    fun depth(view: MprView): Reslicer.Depth = Reslicer.depth(volume, scrollDir(view), cursor)

    /** Moves the cursor [steps] slices along [view]'s scroll direction. */
    fun scroll(view: MprView, steps: Int) = goTo(view, depth(view).index + steps)

    fun goTo(view: MprView, index: Int) {
        cursor = Reslicer.moveToIndex(volume, scrollDir(view), cursor, index)
    }

    fun label(view: MprView): String {
        val deg = Reslicer.obliquity(view, rotation)
        return if (deg < 0.5) view.label else "Oblique (${view.label.take(3).lowercase()} ${"%+.0f".format(deg)}°)"
    }
}
