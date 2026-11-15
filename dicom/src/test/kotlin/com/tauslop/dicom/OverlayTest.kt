package com.tauslop.dicom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OverlayTest {

    private fun header(ts: TransferSyntax = TransferSyntax.EXPLICIT_LE, build: DicomWriter.() -> Unit): DicomObject =
        DicomParser.parse(DicomWriter(ts).apply(build).bytes().inputStream()).dataset

    // ---- DicomFormat --------------------------------------------------------

    @Test fun personNames() {
        assertEquals("Doe John", DicomFormat.personName("Doe^John"))
        assertEquals("Doe John Dr", DicomFormat.personName("Doe^John^^Dr^"))
        assertEquals("LIDC-IDRI-0006", DicomFormat.personName("LIDC-IDRI-0006"))
        assertNull(DicomFormat.personName("^^^^"))
        assertNull(DicomFormat.personName("  "))
        assertNull(DicomFormat.personName(null))
        // Alphabetic group first; an empty alphabetic group falls back to the next group.
        assertEquals("Yamada Tarou", DicomFormat.personName("Yamada^Tarou=山田^太郎=やまだ^たろう"))
        assertEquals("山田 太郎", DicomFormat.personName("=山田^太郎"))
    }

    @Test fun dates() {
        assertEquals("2000-01-31", DicomFormat.date("20000131"))
        assertEquals("2000-01-31", DicomFormat.date("2000.01.31")) // retired ACR-NEMA form
        assertEquals("2004-02-29", DicomFormat.date("20040229"))
        for (bad in listOf("20030229", "20001301", "20000100", "2000013", "2000-01-31x", "abcdefgh", "", "00000000", null)) {
            assertNull("'$bad' should be rejected", DicomFormat.date(bad))
        }
    }

    @Test fun times() {
        assertEquals("14:30:05", DicomFormat.time("143005.123456"))
        assertEquals("14:30:05", DicomFormat.time("143005"))
        assertEquals("14:30", DicomFormat.time("1430"))
        assertEquals("09", DicomFormat.time("09"))
        assertEquals("14:30:05", DicomFormat.time("14:30:05")) // retired form
        for (bad in listOf("246000", "126000", "1", "12345", "ab", "", null)) {
            assertNull("'$bad' should be rejected", DicomFormat.time(bad))
        }
    }

    @Test fun ages() {
        assertEquals("45Y", DicomFormat.age("045Y"))
        assertEquals("3M", DicomFormat.age("003m"))
        assertEquals("0D", DicomFormat.age("000D"))
        for (bad in listOf("45Y", "045", "045X", "", null)) assertNull(DicomFormat.age(bad))

        assertEquals("45Y", DicomFormat.ageBetween("19600615", "20050615"))
        assertEquals("44Y", DicomFormat.ageBetween("19600615", "20050614")) // day before the birthday
        assertEquals("7M", DicomFormat.ageBetween("20040110", "20040815"))
        assertEquals("10M", DicomFormat.ageBetween("20040131", "20041230")) // 30 Dec is before the 31st
        assertEquals("12D", DicomFormat.ageBetween("20040220", "20040303")) // across a leap day
        assertEquals("0D", DicomFormat.ageBetween("20040220", "20040220"))
        assertNull(DicomFormat.ageBetween("20050101", "20040101")) // study before birth
        assertNull(DicomFormat.ageBetween("", "20040101"))
        assertNull(DicomFormat.ageBetween("19600615", "bogus"))
    }

    @Test fun sexAndNumbers() {
        assertEquals("F", DicomFormat.sex("F"))
        assertEquals("M", DicomFormat.sex(" m "))
        assertNull(DicomFormat.sex("U"))
        assertNull(DicomFormat.sex(""))
        assertEquals("2.5", DicomFormat.number(2.50))
        assertEquals("120", DicomFormat.number(120.0))
        assertEquals("-123.5", DicomFormat.number(-123.46))
        assertEquals("0", DicomFormat.number(-0.01))
        assertNull(DicomFormat.number(Double.NaN))
        assertNull(DicomFormat.number(null))
    }

    // ---- OverlayInfo / SliceInfo --------------------------------------------

    @Test fun fullHeaderGivesAllLines() {
        for (ts in listOf(TransferSyntax.EXPLICIT_LE, TransferSyntax.IMPLICIT_LE)) {
            val h = header(ts) {
                str(Tag.PatientName, VR.PN, "Doe^Jane")
                str(Tag.PatientID, VR.LO, "12345")
                str(Tag.PatientSex, VR.CS, "F")
                str(Tag.PatientAge, VR.AS, "052Y")
                str(Tag.PatientBirthDate, VR.DA, "19520301")
                str(Tag.InstitutionName, VR.LO, "General Hospital")
                str(Tag.StudyDate, VR.DA, "20040615")
                str(Tag.StudyTime, VR.TM, "093000.5")
                str(Tag.StudyDescription, VR.LO, "CT CHEST")
                str(Tag.AccessionNumber, VR.SH, "A77")
                str(Tag.Modality, VR.CS, "CT")
                str(Tag.SeriesNumber, VR.IS, "3")
                str(Tag.SeriesDescription, VR.LO, "Lung 2.5mm")
            }
            val o = OverlayInfo.from(h)
            assertEquals("$ts", listOf("Doe Jane", "12345", "F 52Y", "DOB 1952-03-01"), o.patientLines())
            assertEquals("$ts", listOf("General Hospital", "2004-06-15 09:30:00", "CT CHEST", "Acc A77"), o.studyLines())
            assertEquals("#3 Lung 2.5mm", o.seriesLine())
            assertEquals("CT", o.modality)
        }
    }

    @Test fun missingAndBlankFieldsLeaveNoGaps() {
        val empty = OverlayInfo.from(header { str(Tag.Modality, VR.CS, "CT") })
        assertTrue(empty.patientLines().isEmpty())
        assertTrue(empty.studyLines().isEmpty())
        assertNull(empty.seriesLine())

        // Present but blank, `^`-only, or malformed: all dropped, never "null" or a bare label.
        val o = OverlayInfo.from(header {
            str(Tag.PatientName, VR.PN, "^^")
            str(Tag.PatientID, VR.LO, "  ")
            str(Tag.PatientSex, VR.CS, "")
            str(Tag.PatientBirthDate, VR.DA, "19601399")
            str(Tag.StudyDate, VR.DA, "notadate")
            str(Tag.StudyTime, VR.TM, "999999")
            str(Tag.AccessionNumber, VR.SH, "")
            str(Tag.SeriesDescription, VR.LO, "Axial")
        })
        assertEquals(emptyList<String>(), o.patientLines())
        assertEquals(emptyList<String>(), o.studyLines())
        assertEquals("Axial", o.seriesLine())

        // Partial lines: only what is there.
        val p = OverlayInfo.from(header {
            str(Tag.PatientSex, VR.CS, "M")
            str(Tag.StudyTime, VR.TM, "1015")
            str(Tag.SeriesNumber, VR.IS, "7")
        })
        assertEquals(listOf("M"), p.patientLines())
        assertEquals(listOf("10:15"), p.studyLines())
        assertEquals("#7", p.seriesLine())
    }

    @Test fun withoutPatientBlanksEveryPatientField() {
        val full = OverlayInfo(
            patientName = "Doe Jane", patientId = "12345", sex = "F", age = "52Y", birthDate = "1952-03-01",
            institution = "General Hospital", studyDate = "2004-06-15", studyTime = "09:30:00",
            studyDescription = "CT CHEST", accession = "A77", modality = "CT", seriesNumber = 3, seriesDescription = "Lung",
        )
        val hidden = full.withoutPatient()
        assertTrue(hidden.patientLines().isEmpty())
        assertEquals(listOf("General Hospital", "2004-06-15 09:30:00", "CT CHEST"), hidden.studyLines())
        assertEquals("#3 Lung", hidden.seriesLine())
        // No line anywhere still contains a patient value.
        val all = (hidden.patientLines() + hidden.studyLines() + listOfNotNull(hidden.seriesLine())).joinToString("|")
        for (v in listOf("Doe", "12345", "52Y", "1952", "A77")) assertTrue("'$v' leaked: $all", v !in all)
    }

    @Test fun ageFromBirthDateWhenPatientAgeMissing() {
        val h = header {
            str(Tag.PatientBirthDate, VR.DA, "19600615")
            str(Tag.SeriesDate, VR.DA, "20050614") // no StudyDate: falls back to the series date
        }
        assertEquals("44Y", OverlayInfo.from(h).age)
        // A malformed PatientAge also falls back.
        val h2 = header {
            str(Tag.PatientAge, VR.AS, "XXXY")
            str(Tag.PatientBirthDate, VR.DA, "19600615")
            str(Tag.StudyDate, VR.DA, "20050615")
        }
        assertEquals("45Y", OverlayInfo.from(h2).age)
    }

    @Test fun multiValuedTextUsesFirstValue() {
        val h = header {
            str(Tag.PatientID, VR.LO, "ID1\\ID2")
            str(Tag.StudyDescription, VR.LO, "\\Second")
        }
        val o = OverlayInfo.from(h)
        assertEquals("ID1", o.patientId)
        assertNull(o.studyDescription) // first value blank: dropped, not the second
    }

    @Test fun sliceInfoLines() {
        val h = header(TransferSyntax.IMPLICIT_LE) {
            str(Tag.SliceLocation, VR.DS, "-123.46")
            str(Tag.SliceThickness, VR.DS, "2.50")
            str(Tag.KVP, VR.DS, "120")
            str(Tag.Exposure, VR.IS, "250")
        }
        assertEquals(listOf("Loc -123.5 mm", "Thk 2.5 mm", "120 kV 250 mAs"), SliceInfo.from(h).lines())
        // mAs from tube current × exposure time (ms) when Exposure is missing.
        val h2 = header {
            str(Tag.XRayTubeCurrent, VR.IS, "400")
            str(Tag.ExposureTime, VR.IS, "500")
        }
        assertEquals(listOf("200 mAs"), SliceInfo.from(h2).lines())
        // Zero, blank or garbage values are dropped.
        val h3 = header {
            str(Tag.SliceThickness, VR.DS, "0")
            str(Tag.KVP, VR.DS, "")
            str(Tag.SliceLocation, VR.DS, "abc")
        }
        assertEquals(emptyList<String>(), SliceInfo.from(h3).lines())
        assertEquals(listOf("Loc 0 mm"), SliceInfo(location = 0.0).lines())
    }

    // ---- Orientation --------------------------------------------------------

    @Test fun orientationAxes() {
        assertEquals("L", Orientation.label(Vec3.X))
        assertEquals("R", Orientation.label(-Vec3.X))
        assertEquals("P", Orientation.label(Vec3.Y))
        assertEquals("A", Orientation.label(-Vec3.Y))
        assertEquals("H", Orientation.label(Vec3.Z))
        assertEquals("F", Orientation.label(-Vec3.Z))
        assertEquals("", Orientation.label(Vec3.ZERO))
        assertEquals("L", Orientation.label(Vec3.X * 7.0)) // not unit: normalized first
    }

    @Test fun orientationObliquesAndCutoff() {
        fun tilt(deg: Double) = Math.toRadians(deg).let { Vec3(-cos(it), -sin(it), 0.0) } // R towards A
        assertEquals("RA", Orientation.label(tilt(30.0)))
        assertEquals("R", Orientation.label(tilt(10.0)))   // below the ~14.5° cut-off
        assertEquals("AR", Orientation.label(tilt(60.0)))  // larger component first
        assertEquals("RA", Orientation.label(tilt(16.0)))
        // Three letters for a direction off all three axes.
        assertEquals("LPH", Orientation.label(Vec3(0.8, 0.5, 0.33)))
        // Custom cut-off.
        assertEquals("R", Orientation.label(tilt(30.0), cutoff = 0.6))
    }

    @Test fun orientationEdgesMatchMprViews() {
        // Axial: patient right on screen left, anterior up.
        assertEquals(Orientation.Edges("R", "A", "L", "P"), Orientation.edges(MprView.AXIAL.right, MprView.AXIAL.down))
        // Coronal: patient right on screen left, head up.
        assertEquals(Orientation.Edges("R", "H", "L", "F"), Orientation.edges(MprView.CORONAL.right, MprView.CORONAL.down))
        // Sagittal: anterior on screen left, head up.
        assertEquals(Orientation.Edges("A", "H", "P", "F"), Orientation.edges(MprView.SAGITTAL.right, MprView.SAGITTAL.down))
    }

    // ---- ScaleBar -----------------------------------------------------------

    @Test fun scaleBarRoundLengths() {
        // 0.7 mm/px, up to 200 px (140 mm): 100 mm = 10 cm.
        val b = ScaleBar.choose(0.7, 200.0)!!
        assertEquals(100.0, b.mm, 0.0)
        assertEquals(100.0 / 0.7, b.px, 1e-9)
        assertEquals("10 cm", b.label)
        assertEquals(50.0, ScaleBar.choose(0.7, 100.0)!!.mm, 0.0)   // 70 mm → 5 cm
        assertEquals(20.0, ScaleBar.choose(0.25, 100.0)!!.mm, 0.0)  // 25 mm → 2 cm
        assertEquals("2 cm", ScaleBar.choose(0.25, 100.0)!!.label)
        assertEquals(5.0, ScaleBar.choose(0.05, 100.0)!!.mm, 0.0)   // 5 mm exactly fits
        assertEquals("5 mm", ScaleBar.choose(0.05, 100.0)!!.label)
        assertEquals(1000.0, ScaleBar.choose(10.0, 100.0)!!.mm, 0.0) // exact power of ten
        assertEquals("0.2 mm", ScaleBar.choose(0.003, 100.0)!!.label)
        for (mmPerPx in listOf(0.013, 0.7, 3.3)) for (maxPx in listOf(37.0, 150.0, 999.0)) {
            val bar = ScaleBar.choose(mmPerPx, maxPx)!!
            assertTrue("fits", bar.px <= maxPx + 1e-9)
            assertTrue("uses more than ~1/5 of the space", bar.px > maxPx / 5.01)
            val lead = bar.mm / Math.pow(10.0, Math.floor(Math.log10(bar.mm) + 1e-9))
            assertTrue("round: ${bar.mm}", listOf(1.0, 2.0, 5.0).any { kotlin.math.abs(it - lead) < 1e-6 })
        }
    }

    @Test fun scaleBarUnknownScale() {
        assertNull(ScaleBar.choose(0.0, 100.0))
        assertNull(ScaleBar.choose(-1.0, 100.0))
        assertNull(ScaleBar.choose(Double.NaN, 100.0))
        assertNull(ScaleBar.choose(0.7, 0.0))
        assertNotNull(ScaleBar.choose(0.7, 1.0))
    }
}
