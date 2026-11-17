package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** dopo: il PDF di un libro di foto senza pixel in memoria. Foto finte fatte qui. */
class PdfFotoTest {
    private fun jpeg(w: Int, h: Int): ByteArray = javaClass.getResourceAsStream("/prova-${w}x$h.jpg")!!.use { it.readBytes() }

    @Test fun leMisureDelJpegSiLeggono() {
        assertEquals(Triple(120, 80, 3), PdfFoto.misure(jpeg(120, 80)))
        assertNull(PdfFoto.misure(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
    }

    @Test fun ilPdfHaLeFotoComeSonoEUnIndiceGiusto() {
        val j = jpeg(60, 90)
        val o = ByteArrayOutputStream()
        val ok = PdfFoto.scrivi(o, 2) { p ->
            PdfFoto.Pagina(436.535f, 612.283f, -1, Riquadro(8.504f, 8.504f, 428.031f, 603.779f), Riquadro(0f, 0f, 436.535f, 612.283f),
                listOf(PdfFoto.Posto({ j }, Riquadro(0f, 0f, 436.535f, 612.283f), Riquadro(0f, -10f, 436.535f, 622.283f))))
        }
        assertTrue(ok)
        val b = o.toByteArray(); val s = String(b, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4"))
        assertEquals(2, Regex("/Subtype /Image").findAll(s).count())
        assertTrue("/MediaBox [0 0 436.535 612.283]" in s && "/TrimBox [8.504 8.504 428.031 603.779]" in s)
        // il JPEG è dentro intero, byte per byte
        assertTrue(s.contains(String(j, Charsets.ISO_8859_1)))
        // ogni voce dell'indice punta al suo «N 0 obj»
        val x = s.substring(s.lastIndexOf("startxref") + 10).trim().lines()[0].toInt()
        val righe = s.substring(x).lines()
        val quante = righe[1].split(' ')[1].toInt()
        for (i in 1 until quante) {
            val off = righe[2 + i].substring(0, 10).toInt()
            assertTrue("oggetto $i", s.startsWith("$i 0 obj", off))
        }
        // una foto che non si legge: niente PDF a metà creduto buono
        assertFalse(PdfFoto.scrivi(ByteArrayOutputStream(), 1) { PdfFoto.Pagina(10f, 10f, 0, null, null, listOf(PdfFoto.Posto({ null }, Riquadro(0f, 0f, 1f, 1f), Riquadro(0f, 0f, 1f, 1f)))) })
    }

    @Test fun soloLibriDiFotoDritte() {
        val f = Elemento(1, Elemento.FOTO, 0f, 0f, 1080f, 1530f, uri = "file:///x.jpg")
        val g = Grafica("b", Formato.A5, elementi = listOf(f, f.copy(id = 2, pagina = 1)), pagine = 2)
        assertTrue(PdfFoto.adatto(g) { g.di(it) })
        assertFalse(PdfFoto.adatto(g.copy(elementi = g.elementi + Elemento(3, Elemento.TESTO, 0f, 0f, 9f, 9f, testo = "x"))) { p -> g.copy(elementi = g.elementi + Elemento(3, Elemento.TESTO, 0f, 0f, 9f, 9f, testo = "x")).di(p) })
        assertFalse(PdfFoto.adatto(g.copy(elementi = listOf(f.copy(rot = 5f)))) { p -> listOf(f.copy(rot = 5f)).filter { it.pagina == p } })
        assertFalse(PdfFoto.adatto(g.copy(perStampa = Stampa(segni = true))) { g.di(it) })
    }
}
