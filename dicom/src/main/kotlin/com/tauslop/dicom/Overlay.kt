package com.tauslop.dicom

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Display formatting for DICOM text values (PS3.5 §6.2). Every function returns null for a
 * missing, blank or malformed value, so callers can simply drop it. Avoids java.time, which needs
 * API 26 on Android.
 */
object DicomFormat {

    /**
     * Person name: the first non-empty component group (alphabetic before ideographic/phonetic),
     * with the `^` separators turned into spaces and empty components dropped. `"Doe^John^^Dr"` →
     * `"Doe John Dr"`; `"^^^"` → null.
     */
    fun personName(pn: String?): String? {
        if (pn == null) return null
        return pn.split('=').asSequence()
            .map { g -> g.split('^').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }
            .firstOrNull { it.isNotEmpty() }
    }

    /** Calendar date (year, month, day), or null if [da] is not a valid DA value. */
    fun parseDate(da: String?): Triple<Int, Int, Int>? {
        // DA is YYYYMMDD; the retired ACR-NEMA form YYYY.MM.DD still turns up.
        val s = da?.trim()?.replace(".", "") ?: return null
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        val y = s.substring(0, 4).toInt()
        val m = s.substring(4, 6).toInt()
        val d = s.substring(6, 8).toInt()
        if (y < 1 || m !in 1..12 || d !in 1..daysIn(y, m)) return null
        return Triple(y, m, d)
    }

    /** `"20000131"` → `"2000-01-31"`. */
    fun date(da: String?): String? = parseDate(da)?.let { (y, m, d) -> "%04d-%02d-%02d".format(Locale.ROOT, y, m, d) }

    /**
     * Time of day to the second: `"143005.123"` → `"14:30:05"`, `"1430"` → `"14:30"`. Also accepts
     * the retired `HH:MM:SS` form. Out-of-range fields give null.
     */
    fun time(tm: String?): String? {
        val s = tm?.trim()?.replace(":", "")?.substringBefore('.') ?: return null
        if (s.length !in setOf(2, 4, 6) || !s.all { it.isDigit() }) return null
        val parts = s.chunked(2).map { it.toInt() }
        if (parts[0] > 23 || parts.drop(1).any { it > 59 }) return null
        return parts.joinToString(":") { "%02d".format(Locale.ROOT, it) }
    }

    /** Age string (AS, `nnnD/W/M/Y`): `"045Y"` → `"45Y"`. */
    fun age(asValue: String?): String? {
        val s = asValue?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (s.length != 4 || !s.substring(0, 3).all { it.isDigit() } || s[3] !in "DWMY") return null
        return "${s.substring(0, 3).toInt()}${s[3]}"
    }

    /**
     * Age on [at] for someone born on [birth], both DA values, in the largest whole unit:
     * years, else months, else days (`"45Y"`, `"7M"`, `"12D"`). Null if either date is invalid or
     * [at] is before [birth].
     */
    fun ageBetween(birth: String?, at: String?): String? {
        val (by, bm, bd) = parseDate(birth) ?: return null
        val (ay, am, ad) = parseDate(at) ?: return null
        var months = (ay - by) * 12 + (am - bm)
        if (ad < bd) months--
        if (months < 0) return null
        if (months >= 12) return "${months / 12}Y"
        if (months >= 1) return "${months}M"
        val days = dayNumber(ay, am, ad) - dayNumber(by, bm, bd)
        return if (days >= 0) "${days}D" else null
    }

    /** Sex code: M, F or O; anything else (blank, unknown) is null. */
    fun sex(cs: String?): String? = cs?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("M", "F", "O") }

    /** Number with at most [decimals] decimals and no trailing zeros: 2.50 → "2.5", 120.0 → "120". */
    fun number(v: Double?, decimals: Int = 1): String? {
        if (v == null || !v.isFinite()) return null
        val s = "%.${decimals}f".format(Locale.ROOT, v)
        val t = if ('.' in s) s.trimEnd('0').trimEnd('.') else s
        return if (t == "-0") "0" else t
    }

    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    private fun daysIn(y: Int, m: Int) = when (m) {
        2 -> if (leap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since a fixed epoch (proleptic Gregorian), for date differences. */
    private fun dayNumber(y: Int, m: Int, d: Int): Long {
        val a = if (m <= 2) 1 else 0
        val yy = y.toLong() - a
        val mm = m + 12 * a - 3
        return d + (153L * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400
    }
}

/**
 * Patient, study and series text for the corner overlays, from one image header. Each `…Lines`
 * function returns only non-blank lines, so missing or anonymised fields leave no gaps.
 */
data class OverlayInfo(
    val patientName: String? = null,
    val patientId: String? = null,
    val sex: String? = null,
    /** From PatientAge, else from the birth date and the study (or series) date. */
    val age: String? = null,
    val birthDate: String? = null,
    val institution: String? = null,
    val studyDate: String? = null,
    val studyTime: String? = null,
    val studyDescription: String? = null,
    val accession: String? = null,
    val modality: String? = null,
    val seriesNumber: Int? = null,
    val seriesDescription: String? = null,
) {
    /** Top left: name, ID, "sex age", "DOB date". */
    fun patientLines(): List<String> = clean(
        patientName,
        patientId,
        listOfNotNull(sex, age).joinToString(" "),
        birthDate?.let { "DOB $it" },
    )

    /** Top right: institution, "date time", study description, "Acc number". */
    fun studyLines(): List<String> = clean(
        institution,
        listOfNotNull(studyDate, studyTime).joinToString(" "),
        studyDescription,
        accession?.let { "Acc $it" },
    )

    /** Series heading for the bottom-left corner: "#3 CHEST", "#3", "CHEST", or null. */
    fun seriesLine(): String? =
        listOfNotNull(seriesNumber?.let { "#$it" }, seriesDescription).joinToString(" ").ifEmpty { null }

    companion object {
        fun from(h: DicomObject): OverlayInfo {
            fun text(tag: Int) = h.string(tag)?.trim()?.takeIf { it.isNotEmpty() }
            val birth = h.string(Tag.PatientBirthDate)
            val onDate = h.string(Tag.StudyDate) ?: h.string(Tag.SeriesDate)
            return OverlayInfo(
                patientName = DicomFormat.personName(h.string(Tag.PatientName)),
                patientId = text(Tag.PatientID),
                sex = DicomFormat.sex(h.string(Tag.PatientSex)),
                age = DicomFormat.age(h.string(Tag.PatientAge)) ?: DicomFormat.ageBetween(birth, onDate),
                birthDate = DicomFormat.date(birth),
                institution = text(Tag.InstitutionName),
                studyDate = DicomFormat.date(h.string(Tag.StudyDate)),
                studyTime = DicomFormat.time(h.string(Tag.StudyTime)),
                studyDescription = text(Tag.StudyDescription),
                accession = text(Tag.AccessionNumber),
                modality = text(Tag.Modality),
                seriesNumber = h.int(Tag.SeriesNumber),
                seriesDescription = text(Tag.SeriesDescription),
            )
        }

        internal fun clean(vararg lines: String?): List<String> =
            lines.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}

/** Per-slice acquisition text for the bottom-left corner of the stack view. */
data class SliceInfo(
    val location: Double? = null,
    val thickness: Double? = null,
    val kvp: Double? = null,
    /** Tube current-time product in mAs: Exposure, else tube current × exposure time. */
    val mAs: Double? = null,
) {
    /** "Loc -123.5 mm", "Thk 2.5 mm", "120 kV 250 mAs"; blanks dropped. */
    fun lines(): List<String> = OverlayInfo.clean(
        DicomFormat.number(location)?.let { "Loc $it mm" },
        DicomFormat.number(thickness, 2)?.let { "Thk $it mm" },
        listOfNotNull(
            DicomFormat.number(kvp, 0)?.let { "$it kV" },
            DicomFormat.number(mAs, 0)?.let { "$it mAs" },
        ).joinToString(" "),
    )

    companion object {
        fun from(h: DicomObject): SliceInfo {
            fun positive(tag: Int) = h.double(tag)?.takeIf { it.isFinite() && it > 0 }
            val mAs = positive(Tag.Exposure) ?: run {
                val mA = positive(Tag.XRayTubeCurrent)
                val ms = positive(Tag.ExposureTime)
                if (mA != null && ms != null) mA * ms / 1000 else null
            }
            return SliceInfo(
                location = h.double(Tag.SliceLocation)?.takeIf { it.isFinite() },
                thickness = positive(Tag.SliceThickness),
                kvp = positive(Tag.KVP),
                mAs = mAs,
            )
        }
    }
}

/**
 * Anatomical direction letters for an LPS direction: +x = L, −x = R, +y = P, −y = A,
 * +z = H (head), −z = F (feet).
 */
object Orientation {
    /** A secondary letter appears once its component of the unit direction reaches this (~14.5°). */
    const val SECONDARY = 0.25

    /** Edge labels of a view whose screen axes are [right] and [down] (patient LPS directions). */
    data class Edges(val left: String, val top: String, val right: String, val bottom: String)

    /**
     * Letters for [d], largest component first, plus up to two more whose components reach
     * [cutoff]: (1, 0, 0) → "L"; a 30° tilt from R towards A → "RA". Zero vector → "".
     */
    fun label(d: Vec3, cutoff: Double = SECONDARY): String {
        val len = d.length
        if (!(len > 1e-12)) return ""
        val u = d * (1 / len)
        val comps = listOf(
            u.x to (if (u.x > 0) 'L' else 'R'),
            u.y to (if (u.y > 0) 'P' else 'A'),
            u.z to (if (u.z > 0) 'H' else 'F'),
        ).sortedByDescending { abs(it.first) }
        return comps.filterIndexed { i, (c, _) -> i == 0 || abs(c) >= cutoff }.map { it.second }.joinToString("")
    }

    fun edges(right: Vec3, down: Vec3, cutoff: Double = SECONDARY) =
        Edges(label(-right, cutoff), label(-down, cutoff), label(right, cutoff), label(down, cutoff))
}

/** Scale bar of a round length (1, 2 or 5 × 10ⁿ mm). */
object ScaleBar {
    data class Bar(val mm: Double, val px: Double) {
        /** "5 cm", "2 cm", "5 mm", "0.5 mm". */
        val label: String get() =
            if (mm >= 10) "${DicomFormat.number(mm / 10, 1)} cm" else "${DicomFormat.number(mm, 3)} mm"
    }

    /**
     * The longest round length that fits in [maxPx] screen pixels at [mmPerPx]. Null if the scale is
     * unknown (not finite or ≤ 0) or [maxPx] is not positive.
     */
    fun choose(mmPerPx: Double, maxPx: Double): Bar? {
        if (!mmPerPx.isFinite() || mmPerPx <= 0 || !maxPx.isFinite() || maxPx <= 0) return null
        val maxMm = mmPerPx * maxPx
        var p = Math.pow(10.0, Math.floor(Math.log10(maxMm)))
        // Guard against log10 rounding just below a power of ten.
        if (p * 10 <= maxMm * (1 + 1e-12)) p *= 10
        val mm = listOf(5.0, 2.0, 1.0).map { (it * p) }.first { it <= maxMm * (1 + 1e-12) }
        val rounded = (mm * 1e6).roundToLong() / 1e6
        return Bar(rounded, rounded / mmPerPx)
    }
}
