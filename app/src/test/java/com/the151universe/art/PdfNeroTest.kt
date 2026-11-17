package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

class PdfNeroTest {
    @Test fun ilNeroDelQuadernoDiventaSoloK() {
        assertEquals(1f, PdfNero.k(.1373f, .1216f, .1255f)!!, 0.01f)       // #231F20 = 100% K, come InDesign
        assertEquals(0.17f, PdfNero.k(.8431f, .851f, .8549f)!!, 0.01f)     // il grigio della tabella = 17% K
        assertEquals(0f, PdfNero.k(1f, 1f, 1f)!!, 0.001f)                  // la carta
        assertNull(PdfNero.k(.8431f, .149f, .2392f))                       // il rosso resta rosso
        val c = PdfNero.inChiaro("q .1373 .1216 .1255 RG .1373 .1216 .1255 rg BT (x) Tj ET .8431 .149 .2392 rg 12.5 3 m")
        assertEquals("q 0 0 0 1 K 0 0 0 1 k BT (x) Tj ET .8431 .149 .2392 rg 12.5 3 m", c)
    }

    private fun stretto(t: String): String {
        val o = ByteArrayOutputStream(); DeflaterOutputStream(o).use { it.write(t.toByteArray(Charsets.ISO_8859_1)) }
        return String(o.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Un PDF come quelli di Android (Skia): un font con /Length1 (non si tocca) e i disegni compressi. */
    private fun pdf(): ByteArray {
        val b = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val dove = ArrayList<Int>()
        fun obj(n: Int, corpo: String) { dove.add(b.length); b.append("$n 0 obj\n").append(corpo).append("\nendobj\n") }
        obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        obj(2, "<</Type /Pages /Count 1 /Kids [3 0 R]>>")
        obj(3, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 437 613] /Contents 4 0 R>>")
        val d = stretto(".1373 .1216 .1255 RG .1373 .1216 .1255 rg\n0 0 m 10 0 l S\n.8431 .149 .2392 rg BT (jumped) Tj ET\n")
        obj(4, "<</Filter /FlateDecode\n/Length ${d.length}>> stream\n$d\nendstream")
        val font = stretto(".1373 .1216 .1255 rg")   // dati di un font che per caso somigliano: intoccabili
        obj(5, "<</Filter /FlateDecode\n/Length1 ${font.length}\n/Length ${font.length}>> stream\n$font\nendstream")
        val x = b.length
        b.append("xref\n0 ${dove.size + 1}\n0000000000 65535 f \n")
        for (o in dove) b.append(String.format("%010d 00000 n \n", o))
        b.append("trailer\n<</Size ${dove.size + 1} /Root 1 0 R>>\nstartxref\n$x\n%%EOF\n")
        return b.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun flusso(s: String, n: Int): String {
        val m = Regex("$n 0 obj\\s*<<[^<>]*/Length (\\d+)>> stream\\n").find(s)!!
        val a = m.range.last + 1
        val dati = s.substring(a, a + m.groupValues[1].toInt()).toByteArray(Charsets.ISO_8859_1)
        return String(InflaterInputStream(dati.inputStream()).readBytes(), Charsets.ISO_8859_1)
    }

    @Test fun nelPdfLeLineeEIlTestoSonoSoloNeroIColoriNo() {
        val f = Formato.A5
        val out = PdfScatole.correggi(PdfNero.soloNero(pdf()), f.mediaW, f.mediaH, f.abbondanzaPt)
        val s = String(out, Charsets.ISO_8859_1)
        val c = flusso(s, 4)
        assertTrue(c, c.startsWith("0 0 0 1 K 0 0 0 1 k\n"))
        assertTrue(c.contains(".8431 .149 .2392 rg BT (jumped) Tj ET"))
        assertFalse(c.contains(".1373"))
        // il font non si tocca
        assertEquals(".1373 .1216 .1255 rg", String(InflaterInputStream(
            Regex("5 0 obj\\s*<<[^<>]*/Length (\\d+)>> stream\\n").find(s)!!.let { m ->
                s.substring(m.range.last + 1, m.range.last + 1 + m.groupValues[1].toInt()).toByteArray(Charsets.ISO_8859_1).inputStream()
            }).readBytes(), Charsets.ISO_8859_1))
        // gli indirizzi puntano agli oggetti veri
        val x = Regex("startxref\\n(\\d+)").find(s)!!.groupValues[1].toInt()
        val righe = s.substring(x).lines()
        for (i in 1..5) {
            val o = righe[2 + i].substring(0, 10).toInt()
            assertTrue("oggetto $i", s.startsWith("$i 0 obj", o))
        }
    }

    /** 15/11: le carte su A4 passano da soloNero senza correggi: gli indirizzi vanno rifatti a parte. */
    @Test fun carteSuA4GliIndirizziPuntanoAgliOggettiVeri() {
        fun giusti(s: String): Boolean {
            val x = Regex("startxref\\n(\\d+)").find(s)!!.groupValues[1].toInt()
            if (!s.startsWith("xref", x)) return false
            val righe = s.substring(x).lines()
            return (1..5).all { i -> s.startsWith("$i 0 obj", righe[2 + i].substring(0, 10).toInt()) }
        }
        val nero = PdfNero.soloNero(pdf())
        assertFalse("soloNero da solo sposta gli oggetti (se no la prova non prova niente)", giusti(String(nero, Charsets.ISO_8859_1)))
        assertTrue(giusti(String(PdfScatole.indice(nero), Charsets.ISO_8859_1)))
    }

    /**: la lettura a mano dei colori dà lo stesso testo della regex di prima, byte per byte. */
    @Test fun loScannerFaComeLaRegex() {
        val vecchia = Regex("(?<![\\w.])(-?\\d*\\.?\\d+)\\s+(-?\\d*\\.?\\d+)\\s+(-?\\d*\\.?\\d+)\\s+(rg|RG)(?!\\w)")
        fun prima(c: String) = vecchia.replace(c) { m ->
            val (r, g, b) = (1..3).map { m.groupValues[it].toFloat() }
            val kk = PdfNero.k(r, g, b) ?: return@replace m.value
            val t = String.format(java.util.Locale.ROOT, "%.4f", kk).trimEnd('0').trimEnd('.').ifEmpty { "0" }
            "0 0 0 $t ${if (m.groupValues[4] == "rg") "k" else "K"}"
        }
        val pezzi = listOf(" ", " ", "\n", "\r\n", "\t", "rg", "RG", "rg", "Rg", "rgb", "-", ".", ".1373", ".1216", ".1255", "0", "1", "12.5",
            "-.5", "5.", "1.2.3", ".8431", ".851", ".8549", ".149", "a", ")", "(", "_", "<0012>", "/G0", "gs", "Tm", "\u000C")
        val r = java.util.Random(151)
        repeat(20_000) {
            val t = StringBuilder()
            repeat(1 + r.nextInt(40)) { t.append(pezzi[r.nextInt(pezzi.size)]) }
            val c = t.toString()
            assertEquals(c, prima(c), PdfNero.inChiaro(c))
        }
        // le pagine del libro finto di 177 pagine (le stesse del cronometro)
        val libro = String(PdfTempoTest().libro(20), Charsets.ISO_8859_1)
        for (m in Regex("/Length (\\d+)>> stream\\n").findAll(libro)) {
            val a = m.range.last + 1; val n = m.groupValues[1].toInt()
            if (n > 50_000) continue
            val c = String(InflaterInputStream(libro.substring(a, a + n).toByteArray(Charsets.ISO_8859_1).inputStream()).readBytes(), Charsets.ISO_8859_1)
            assertEquals(prima(c), PdfNero.inChiaro(c))
        }
    }
}
