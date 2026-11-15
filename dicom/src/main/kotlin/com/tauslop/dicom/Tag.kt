package com.tauslop.dicom

/** Tags used by the viewer, with the VR needed to interpret them in implicit-VR files. */
object Tag {
    // File meta (group 0002)
    const val MediaStorageSOPClassUID = 0x00020002
    const val TransferSyntaxUID = 0x00020010

    const val SpecificCharacterSet = 0x00080005
    const val ImageType = 0x00080008
    const val SOPClassUID = 0x00080016
    const val SOPInstanceUID = 0x00080018
    const val StudyDate = 0x00080020
    const val SeriesDate = 0x00080021
    const val StudyTime = 0x00080030
    const val SeriesTime = 0x00080031
    const val ContentTime = 0x00080033
    const val AccessionNumber = 0x00080050
    const val Modality = 0x00080060
    const val Manufacturer = 0x00080070
    const val InstitutionName = 0x00080080
    const val StudyDescription = 0x00081030
    const val SeriesDescription = 0x0008103E

    const val PatientName = 0x00100010
    const val PatientID = 0x00100020
    const val PatientBirthDate = 0x00100030
    const val PatientSex = 0x00100040
    const val PatientAge = 0x00101010

    const val SliceThickness = 0x00180050
    const val KVP = 0x00180060
    const val SpacingBetweenSlices = 0x00180088
    const val ExposureTime = 0x00181150
    const val XRayTubeCurrent = 0x00181151
    const val Exposure = 0x00181152

    const val StudyInstanceUID = 0x0020000D
    const val SeriesInstanceUID = 0x0020000E
    const val StudyID = 0x00200010
    const val SeriesNumber = 0x00200011
    const val AcquisitionNumber = 0x00200012
    const val InstanceNumber = 0x00200013
    const val ImagePositionPatient = 0x00200032
    const val ImageOrientationPatient = 0x00200037
    const val FrameOfReferenceUID = 0x00200052
    const val SliceLocation = 0x00201041

    const val SamplesPerPixel = 0x00280002
    const val PhotometricInterpretation = 0x00280004
    const val PlanarConfiguration = 0x00280006
    const val NumberOfFrames = 0x00280008
    const val Rows = 0x00280010
    const val Columns = 0x00280011
    const val PixelSpacing = 0x00280030
    const val BitsAllocated = 0x00280100
    const val BitsStored = 0x00280101
    const val HighBit = 0x00280102
    const val PixelRepresentation = 0x00280103
    const val WindowCenter = 0x00281050
    const val WindowWidth = 0x00281051
    const val RescaleIntercept = 0x00281052
    const val RescaleSlope = 0x00281053
    const val WindowCenterWidthExplanation = 0x00281055
    const val VOILUTFunction = 0x00281056

    const val PixelData = 0x7FE00010

    const val Item = 0xFFFEE000.toInt()
    const val ItemDelimitation = 0xFFFEE00D.toInt()
    const val SequenceDelimitation = 0xFFFEE0DD.toInt()

    /** VR dictionary for implicit-VR parsing. Unlisted tags become UN (raw bytes). */
    private val dictionary: Map<Int, VR> = mapOf(
        MediaStorageSOPClassUID to VR.UI, TransferSyntaxUID to VR.UI,
        SpecificCharacterSet to VR.CS, ImageType to VR.CS, SOPClassUID to VR.UI, SOPInstanceUID to VR.UI,
        StudyDate to VR.DA, SeriesDate to VR.DA, StudyTime to VR.TM, SeriesTime to VR.TM, ContentTime to VR.TM,
        AccessionNumber to VR.SH, Modality to VR.CS,
        Manufacturer to VR.LO, InstitutionName to VR.LO, StudyDescription to VR.LO, SeriesDescription to VR.LO,
        PatientName to VR.PN, PatientID to VR.LO, PatientBirthDate to VR.DA, PatientSex to VR.CS, PatientAge to VR.AS,
        SliceThickness to VR.DS, KVP to VR.DS, SpacingBetweenSlices to VR.DS,
        ExposureTime to VR.IS, XRayTubeCurrent to VR.IS, Exposure to VR.IS,
        StudyInstanceUID to VR.UI, SeriesInstanceUID to VR.UI, StudyID to VR.SH, SeriesNumber to VR.IS,
        AcquisitionNumber to VR.IS, InstanceNumber to VR.IS, ImagePositionPatient to VR.DS,
        ImageOrientationPatient to VR.DS, FrameOfReferenceUID to VR.UI, SliceLocation to VR.DS,
        SamplesPerPixel to VR.US, PhotometricInterpretation to VR.CS, PlanarConfiguration to VR.US,
        NumberOfFrames to VR.IS, Rows to VR.US, Columns to VR.US, PixelSpacing to VR.DS,
        BitsAllocated to VR.US, BitsStored to VR.US, HighBit to VR.US, PixelRepresentation to VR.US,
        WindowCenter to VR.DS, WindowWidth to VR.DS, RescaleIntercept to VR.DS, RescaleSlope to VR.DS,
        WindowCenterWidthExplanation to VR.LO, VOILUTFunction to VR.CS, PixelData to VR.OW,
    )

    fun implicitVr(tag: Int): VR? = when {
        dictionary.containsKey(tag) -> dictionary[tag]
        tag and 0xFFFF == 0x0000 -> VR.UL // group length
        else -> null
    }

    fun toString(tag: Int): String = "(%04X,%04X)".format(tag ushr 16, tag and 0xFFFF)
}
