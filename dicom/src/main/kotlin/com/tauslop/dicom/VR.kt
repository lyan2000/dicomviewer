package com.tauslop.dicom

/** DICOM value representations (PS3.5 §6.2). */
enum class VR(val longLength: Boolean = false, val text: Boolean = false) {
    AE(text = true), AS(text = true), AT, CS(text = true), DA(text = true), DS(text = true),
    DT(text = true), FL, FD, IS(text = true), LO(text = true), LT(text = true),
    OB(longLength = true), OD(longLength = true), OF(longLength = true), OL(longLength = true),
    OV(longLength = true), OW(longLength = true), PN(text = true), SH(text = true), SL,
    SQ(longLength = true), SS, ST(text = true), SV(longLength = true), TM(text = true),
    UC(longLength = true, text = true), UI(text = true), UL, UN(longLength = true),
    UR(longLength = true, text = true), US, UT(longLength = true, text = true), UV(longLength = true);

    companion object {
        private val byCode: Map<Int, VR> = entries.associateBy { (it.name[0].code shl 8) or it.name[1].code }

        /** Look up a VR from its two ASCII bytes; null if unknown. */
        fun of(b0: Int, b1: Int): VR? = byCode[(b0 shl 8) or b1]
    }
}
