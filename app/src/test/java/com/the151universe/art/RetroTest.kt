package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dopo: fronte e retro delle carte, foto prese dalla lista. Dati finti. */
class RetroTest {
    private fun t(id: Long, s: String, y: Float = 10f, p: Int = 0) = Elemento(id, Elemento.TESTO, 10f, y, 300f, 40f, testo = s, pagina = p)
    private fun f(id: Long, uri: String = "", y: Float = 400f, p: Int = 0) = Elemento(id, Elemento.FOTO, 10f, y, 300f, 300f, uri = uri, pagina = p)

    @Test fun fronteERetroDiventanoCoppieDiPagine() {
        val g = Grafica("Deck", Formato.CARTA, pagine = 2, elementi = listOf(t(1, "{Word}"), t(2, "{Translation}", p = 1), t(3, "back logo", 500f, p = 1)))
        assertEquals(listOf("Word", "Translation"), Carte.campi(g, 0, 1))
        val n = Carte.unisci(g, 0, listOf(listOf("cat", "gatto"), listOf("dog", "cane")), "Deck · cards", retro = 1)
        assertEquals(4, n.pagine)
        assertEquals(listOf("cat"), n.di(0).map { it.testo })
        assertEquals(listOf("gatto", "back logo"), n.di(1).map { it.testo })
        assertEquals(listOf("dog"), n.di(2).map { it.testo })
        assertEquals(listOf("cane", "back logo"), n.di(3).map { it.testo })
        assertTrue(n.perStampa.retro)
        // senza retro: una pagina per carta, il retro non entra
        val solo = Carte.unisci(g, 0, listOf(listOf("cat", "gatto")), "x")
        assertEquals(1, solo.pagine); assertTrue(!solo.perStampa.retro)
        // il fronte e retro si salva e si rilegge
        assertTrue(Grafiche.leggi(Grafiche.scrivi(n))!!.perStampa.retro)
        // i file di prima (X con due valori) si leggono ancora
        val vecchio = Grafiche.scrivi(n.copy(perStampa = Stampa(segni = true, foglio = true))).replace("X\t1\t1\t0", "X\t1\t1")
        assertEquals(Stampa(true, true, false), Grafiche.leggi(vecchio)!!.perStampa)
    }

    @Test fun leFotoSiAbbinanoPerNomeOInFila() {
        val righe = listOf(listOf("cat", "gatto"), listOf("ice cream", "gelato"), listOf("dog", "cane"))
        assertEquals(listOf(1, 0, null), Carte.abbina(righe, listOf("Ice_Cream.JPG", "cat.jpeg", "bird.png")))
        // nessun nome che combacia: in fila
        assertEquals(listOf(0, 1, null), Carte.abbina(righe, listOf("IMG_001.jpg", "IMG_002.jpg")))
        assertEquals(listOf(null, null, null), Carte.abbina(righe, emptyList()))
        assertEquals("ice cream", Carte.chiave("/storage/Pictures/ice-cream.png"))
    }

    @Test fun laFotoVaNelRiquadroVuotoDellaCarta() {
        val g = Grafica("Deck", Formato.CARTA, elementi = listOf(t(1, "{Word}"), f(2, "content://piena", y = 50f), f(3, "", y = 400f)))
        assertEquals(3L, Carte.cornice(g, 0)!!.id)
        val n = Carte.unisci(g, 0, listOf(listOf("cat"), listOf("dog")), "x", foto = listOf("file:///a.jpg", null))
        assertEquals(listOf("content://piena", "file:///a.jpg"), n.di(0).filter { it.tipo == Elemento.FOTO }.map { it.uri })
        assertEquals(listOf("content://piena", ""), n.di(1).filter { it.tipo == Elemento.FOTO }.map { it.uri })
        assertNull(Carte.cornice(Grafica("v", Formato.CARTA, elementi = listOf(t(1, "x"))), 0))
    }

    @Test fun ilRetroStaSulFoglioDopoASpecchio() {
        val fc = Formato.CARTA
        val fg = Impagina.foglio(fc.pdfW, fc.pdfH, mm(3f))!!     // 4 × 2, coricato
        // 10 carte fronte/retro = 20 pagine → 2 fogli di fronti, 2 di retri
        assertEquals(4, Impagina.fogli(20, 8, true))
        assertEquals(3, Impagina.fogli(20, 8, false))
        val fronti = Impagina.sulFoglio(fg, 0, 20, true)
        val retri = Impagina.sulFoglio(fg, 1, 20, true)
        assertEquals((0 until 8).map { 2 * it }, fronti.map { it.first })
        assertEquals((0 until 8).map { 2 * it + 1 }, retri.map { it.first })
        // il retro di ogni carta cade dietro il suo fronte girando il foglio sul lato corto (specchio destra ↔ sinistra)
        for (k in 0 until 8) {
            val (fx, fy) = fronti[k].second; val (rx, ry) = retri[k].second
            assertEquals(fy, ry, 0.01f)
            assertEquals(fg.w - (fx + fg.cw), rx, 0.01f)
            assertTrue(fg.posti.any { kotlin.math.abs(it.first - rx) < 0.01f && kotlin.math.abs(it.second - ry) < 0.01f })
        }
        // ultimo foglio: carte 8 e 9 sole
        assertEquals(listOf(16, 18), Impagina.sulFoglio(fg, 2, 20, true).map { it.first })
        assertEquals(listOf(17, 19), Impagina.sulFoglio(fg, 3, 20, true).map { it.first })
        assertEquals("flip on the short edge", Impagina.giro(fg))
        // senza retro come prima
        assertEquals((8 until 16).toList(), Impagina.sulFoglio(fg, 1, 20, false).map { it.first })
    }
}
