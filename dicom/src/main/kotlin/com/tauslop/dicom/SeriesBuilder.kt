package com.tauslop.dicom

import kotlin.math.abs

/** One displayable 2D image: a frame of a file identified by [source]. */
class Slice<S>(
    val source: S,
    val frame: Int,
    val header: DicomObject,
    val plane: ImagePlane?,
) {
    val instanceNumber: Int? get() = header.int(Tag.InstanceNumber)
}

class Series<S>(
    val seriesUid: String,
    val modality: String?,
    val description: String?,
    val seriesNumber: Int?,
    val slices: List<Slice<S>>,
) {
    val size: Int get() = slices.size
}

/** Header of one scanned file, as produced by a folder scan. */
class ScannedFile<S>(val source: S, val file: DicomFile)

object SeriesBuilder {

    class Result<S>(val series: List<Series<S>>, val skipped: Map<SkipReason, Int>)

    /**
     * Groups displayable files into series and sorts each spatially.
     * [notDicom] counts files the scan couldn't parse at all.
     */
    fun <S> build(files: List<ScannedFile<S>>, notDicom: Int = 0): Result<S> {
        val skipped = HashMap<SkipReason, Int>()
        if (notDicom > 0) skipped[SkipReason.NOT_DICOM] = notDicom

        val slices = ArrayList<Slice<S>>()
        for (f in files) {
            val reason = ImageInfo.skipReason(f.file)
            // Not Map.merge: java.util.Map default methods need Android API 24.
            if (reason != null) { skipped[reason] = (skipped[reason] ?: 0) + 1; continue }
            val ds = f.file.dataset
            val plane = ImagePlane.of(ds)
            val frames = ImageInfo.of(ds).frames
            for (i in 0 until frames) slices += Slice(f.source, i, ds, plane)
        }

        // Group by series UID, then split by orientation so each stack is a coherent plane set.
        val groups = LinkedHashMap<String, MutableList<MutableList<Slice<S>>>>()
        for (s in slices) {
            val uid = s.header.string(Tag.SeriesInstanceUID) ?: "unknown"
            val stacks = groups.getOrPut(uid) { ArrayList() }
            val stack = stacks.firstOrNull { st ->
                val a = st.first().plane
                val b = s.plane
                (a == null && b == null) || (a != null && b != null && a.sameOrientation(b))
            }
            if (stack != null) stack += s else stacks += arrayListOf(s)
        }

        val series = groups.flatMap { (uid, stacks) ->
            stacks.map { stack ->
                val h = stack.first().header
                Series(uid, h.string(Tag.Modality), h.string(Tag.SeriesDescription), h.int(Tag.SeriesNumber), sort(stack))
            }
        }.sortedWith(compareBy<Series<S>> { it.seriesNumber ?: Int.MAX_VALUE }.thenBy { it.seriesUid })
        return Result(series, skipped)
    }

    /**
     * Sorts by position along the slice normal when every slice has geometry; otherwise by
     * instance number then frame. Axial-ish stacks run head → feet (radiology convention).
     */
    fun <S> sort(stack: List<Slice<S>>): List<Slice<S>> {
        val byInstance = compareBy<Slice<S>>({ it.instanceNumber ?: Int.MAX_VALUE }, { it.frame })
        val first = stack.first().plane
        if (first == null || stack.any { it.plane == null }) return stack.sortedWith(byInstance)
        val n = first.normal
        val sorted = stack.sortedWith(compareBy<Slice<S>> { it.plane!!.distanceAlong(n) }.then(byInstance))
        // Patient +Z is superior. If the normal is mostly along Z, put the most superior slice first.
        val axial = abs(n[2]) >= abs(n[0]) && abs(n[2]) >= abs(n[1])
        return if (axial && sorted.first().plane!!.position[2] < sorted.last().plane!!.position[2]) sorted.asReversed().toList()
        else sorted
    }
}
