package com.tauslop.dicom

/** Transfer syntaxes this library recognises. Only the uncompressed ones are decodable in phase 1. */
enum class TransferSyntax(
    val uid: String,
    val explicitVr: Boolean,
    val bigEndian: Boolean,
    val encapsulated: Boolean,
    val isSupported: Boolean,
) {
    IMPLICIT_LE("1.2.840.10008.1.2", explicitVr = false, bigEndian = false, encapsulated = false, isSupported = true),
    EXPLICIT_LE("1.2.840.10008.1.2.1", explicitVr = true, bigEndian = false, encapsulated = false, isSupported = true),
    EXPLICIT_BE("1.2.840.10008.1.2.2", explicitVr = true, bigEndian = true, encapsulated = false, isSupported = true),
    // Deflate compresses the whole dataset after the meta header; not parsed in phase 1.
    DEFLATED_LE("1.2.840.10008.1.2.1.99", explicitVr = true, bigEndian = false, encapsulated = false, isSupported = false),
    /** Any other UID (JPEG, JPEG 2000, JPEG-LS, RLE, ...): explicit LE with encapsulated pixel data. */
    ENCAPSULATED("", explicitVr = true, bigEndian = false, encapsulated = true, isSupported = false);

    companion object {
        fun fromUid(uid: String): TransferSyntax {
            val clean = uid.trim().trimEnd('\u0000')
            return entries.firstOrNull { it.uid.isNotEmpty() && it.uid == clean } ?: ENCAPSULATED
        }
    }
}
