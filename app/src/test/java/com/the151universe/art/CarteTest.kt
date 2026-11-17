package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**: misure libere, carte da una lista, carte su A4, segni di taglio. */
class CarteTest {
    private fun t(id: Long, s: String, y: Float = 10f, p: Int = 0) = Elemento(id, Elemento.TESTO, 10f, y, 300f, 40f, testo = s, pagina = p)

    @Test fun leMisureLibereSiSalvanoESiRileggono() {
        val f = Formato.suMisura(63.5f, 88f)
        assertEquals("mm:63.5x88.0", f.id)
        assertEquals(f, Formato.da(f.id))
        assertEquals(Grafica.LARGO * 88f / 63.5f, f.alto, 0.01f)
        assertTrue(f.stampa); assertEquals(3f, f.abbondanzaMm, 0f)
        // una misura uguale a un formato pronto è quel formato
        assertEquals(Formato.CARTA, Formato.suMisura(63f, 88f))
        assertEquals(Formato.A6, Formato.da("a6"))
        val g = Grafica("x", f)
        assertEquals(f, Grafiche.leggi(Grafiche.scrivi(g))!!.formato)
        // fuori misura: dentro i limiti
        assertEquals(Formato.MM_MIN, Formato.suMisura(1f, 50f).mmW, 0.01f)
    }

    @Test fun iCampiSiLeggonoDallAltoEUnaVoltaSola() {
        val g = Grafica("c", Formato.CARTA, elementi = listOf(t(1, "{Translation}", 300f), t(2, "{Word} / {Word}", 100f), t(3, "fisso", 500f)))
        assertEquals(listOf("Word", "Translation"), Carte.campi(g, 0))
    }

    @Test fun leRigheSiDividonoEInestazioneSiSalta() {
        val c = listOf("Word", "Translation")
        assertEquals(listOf(listOf("cat", "gatto"), listOf("dog", "cane, cagnolino")),
            Carte.righe("Word;Translation\ncat ; gatto\n\n dog - cane, cagnolino \n", c))
        assertEquals(listOf(listOf("a", "b")), Carte.righe("a\tb", c))
        assertEquals(listOf(listOf("well-known")), Carte.righe("well-known", listOf("Word")))
    }

    @Test fun unaCartaPerRigaConICampiRiempitiEIGrassettiAlPosto() {
        val parola = t(1, "{Word}!", 100f).copy(segni = listOf(Segno(0, 7, Ricco.GRASSETTO)))
        val (g0, k) = Grafiche.nuovaMadre(Grafica("Deck", Formato.CARTA, pagine = 2, elementi = listOf(parola, t(2, "= {Translation}", 300f), t(9, "altra pagina", p = 1))), "Back")
        val g = Grafiche.usaMadre(g0, k, listOf(0)).let { it.copy(elementi = it.elementi + t(5, "logo", p = -k)) }
        val n = Carte.unisci(g, 0, listOf(listOf("cat", "gatto"), listOf("elephant")), "Deck · cards")
        assertEquals(2, n.pagine); assertEquals("Deck · cards", n.nome)
        assertEquals(listOf("cat!", "= gatto"), n.di(0).map { it.testo })
        assertEquals(listOf("elephant!", "= "), n.di(1).map { it.testo })
        assertEquals(listOf(Segno(0, 9, Ricco.GRASSETTO)), Ricco.normalizza(9, n.di(1)[0].segni))
        assertEquals(listOf(1, 1), n.madreDi); assertEquals(listOf("logo"), n.di(-1).map { it.testo })
        assertEquals(n.elementi.size, n.elementi.map { it.id }.distinct().size)
    }

    @Test fun leCarteStannoSuUnA4ENonSiToccano() {
        val f = Formato.CARTA
        val fg = Impagina.foglio(f.pdfW, f.pdfH, mm(3f))!!
        assertEquals(8, fg.perFoglio)              // A4 coricato: 4 × 2
        assertEquals(842f, fg.w, 0f)
        for (a in fg.posti) for (b in fg.posti) if (a != b) {
            val lontani = kotlin.math.abs(a.first - b.first) >= f.pdfW + 2 * mm(3f) - 0.01f || kotlin.math.abs(a.second - b.second) >= f.pdfH + 2 * mm(3f) - 0.01f
            assertTrue(lontani)
        }
        // tutte dentro il bordo bianco
        assertTrue(fg.posti.all { it.first - fg.ab >= Impagina.BORDO_FOGLIO - 0.01f && it.second + f.pdfH + fg.ab <= fg.h - Impagina.BORDO_FOGLIO + 0.01f })
        assertEquals(9, Impagina.foglio(Formato.BIGLIETTO.pdfW, Formato.BIGLIETTO.pdfH, mm(3f))!!.perFoglio)
        assertEquals(1, Impagina.foglio(Formato.A5.pdfW, Formato.A5.pdfH, mm(3f))!!.perFoglio)
        assertNull(Impagina.foglio(Formato.A4.pdfW, Formato.A4.pdfH, mm(3f)))
    }

    @Test fun iSegniDiTaglioStannoFuoriDalleCarte() {
        val f = Formato.CARTA
        val fg = Impagina.foglio(f.pdfW, f.pdfH, mm(3f))!!
        val segni = Impagina.segniFoglio(fg)
        assertTrue(segni.isNotEmpty())
        for (l in segni) for (p in fg.posti) {
            val dentro = { x: Float, y: Float -> x > p.first - fg.ab && x < p.first + f.pdfW + fg.ab && y > p.second - fg.ab && y < p.second + f.pdfH + fg.ab }
            assertTrue(!dentro(l.x1, l.y1) && !dentro(l.x2, l.y2))
        }
        assertTrue(segni.all { minOf(it.x1, it.x2) >= 0f && maxOf(it.x1, it.x2) <= fg.w && minOf(it.y1, it.y2) >= 0f && maxOf(it.y1, it.y2) <= fg.h })
        assertEquals(8, Impagina.segniPagina(10f, 10f, 100f, 200f, mm(3f)).size)
    }

    @Test fun leSceltePerLaStampaSiRicordano() {
        val g = Grafica("x", Formato.CARTA, perStampa = Stampa(segni = true, foglio = true))
        assertEquals(g.perStampa, Grafiche.leggi(Grafiche.scrivi(g))!!.perStampa)
        assertEquals(Stampa(), Grafiche.leggi(Grafiche.scrivi(g.copy(perStampa = Stampa())))!!.perStampa)
        assertNotNull(Grafiche.leggi(Grafiche.scrivi(g)))
    }
}
