package com.tauslop.dicomviewer.viewer

import com.tauslop.dicom.Window

object Presets {
    const val DEFAULT = "Default"
    const val AUTO = "Auto"

    /** CT windows in HU (level/center, width). */
    val ct: List<Window> = listOf(
        Window(-600.0, 1500.0, "Lung"),
        Window(50.0, 350.0, "Mediastinum"),
        Window(40.0, 400.0, "Soft tissue"),
        Window(400.0, 1800.0, "Bone"),
        Window(60.0, 400.0, "Abdomen"),
        Window(40.0, 80.0, "Brain"),
    )

    /** Chip labels for a series: Default (if the file has windows), Auto, then modality presets. */
    fun labelsFor(modality: String?, hasFileWindows: Boolean): List<String> =
        listOfNotNull(DEFAULT.takeIf { hasFileWindows }, AUTO) +
            (if (modality == "CT") ct.map { it.label!! } else emptyList())
}
