package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfScatoleTest {
    /** Un PDF piccolo fatto come quelli di Android (Skia): MediaBox interi, xref classica, un flusso binario. */
    private fun skia(pagine: Int): ByteArray {
        val b = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val dove = ArrayList<Int>()
        fun obj(n: Int, corpo: String) { dove.add(b.length); b.append("$n 0 obj\n").append(corpo).append("\nendobj\n") }
        obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        obj(2, "<</Type /Pages /Count $pagine /Kids [${(0 until pagine).joinToString(" ") { "${3 + 2 * it} 0 R" }}]>>")
        for (i in 0 until pagine) {
            obj(3 + 2 * i, "<</Type /Page /Parent 2 0 R /MediaBox [0 0 437 613] /Contents ${4 + 2 * i} 0 R>>")
            // dentro il flusso un finto «/MediaBox [0 0 1 1]» e un finto «9 0 obj»: non vanno toccati
            val dati = "\u0000ÿ/MediaBox [0 0 1 1]\n9 0 obj\u0001"
            obj(4 + 2 * i, "<</Length ${dati.length}>> stream\n$dati\nendstream")
        }
        val x = b.length
        b.append("xref\n0 ${dove.size + 1}\n0000000000 65535 f \n")
        for (o in dove) b.append(String.format("%010d 00000 n \n", o))
        b.append("trailer\n<</Size ${dove.size + 1} /Root 1 0 R>>\nstartxref\n$x\n%%EOF\n")
        return b.toString().toByteArray(Charsets.ISO_8859_1)
    }

    @Test fun laPaginaPrendeLaMisuraVeraEIndirizziGiusti() {
        val f = Formato.A5
        val out = String(PdfScatole.correggi(skia(3), f.mediaW, f.mediaH, f.abbondanzaPt), Charsets.ISO_8859_1)
        assertEquals(3, Regex("/MediaBox \\[0 0 436.535 612.283\\] /BleedBox \\[0 0 436.535 612.283\\] /TrimBox \\[8.504 8.504 428.031 603.78\\]").findAll(out).count())
        // il flusso binario è intatto
        assertEquals(3, Regex("/MediaBox \\[0 0 1 1\\]").findAll(out).count())
        // ogni riga della xref porta a «N 0 obj» vero, startxref porta a «xref»
        val sx = Regex("startxref\\n(\\d+)").findAll(out).last().groupValues[1].toInt()
        assertTrue(out.startsWith("xref\n", sx))
        val righe = out.substring(sx).lines().drop(2).takeWhile { it.endsWith(" n ") || it.endsWith(" f ") }
        assertEquals(9, righe.size) // 0 + 8 oggetti; il finto «9 0 obj» nel flusso non conta
        righe.drop(1).forEachIndexed { i, r ->
            val o = r.substring(0, 10).toInt()
            assertTrue("oggetto ${i + 1} a $o", out.startsWith("${i + 1} 0 obj", o))
        }
        assertTrue(out.contains("/Size 9"))
        assertTrue(out.endsWith("%%EOF\n"))
    }

    @Test fun unPdfCheNonSiConosceEUnErroreMaiUnFileStorto() {
        for (rotto in listOf("niente".toByteArray(), "%PDF-1.5\n1 0 obj\n<</Type /XRef>>\nendobj\n".toByteArray(),
            "%PDF-1.4\n1 0 obj\n<</Type /Page>>\nendobj\nxref\n0 1\ntrailer\n<<>>\nstartxref\n0\n%%EOF".toByteArray())) {
            try { PdfScatole.correggi(rotto, 10f, 10f, 1f); fail("doveva rifiutare") } catch (_: IllegalStateException) {}
        }
    }
}
