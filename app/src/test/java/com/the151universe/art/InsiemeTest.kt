package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**: più oggetti (allinea, spazi, gruppi), foto nel riquadro, testo che continua (filo). */
class InsiemeTest {
    private val eps = 0.01f
    private fun f(id: Long, x: Float, y: Float, w: Float = 100f, h: Float = 50f) = Elemento(id, Elemento.FORMA, x, y, w, h)

    @Test fun allineaASinistraEAlCentro() {
        val g = Grafica("x", Formato.A5, elementi = listOf(f(1, 100f, 0f), f(2, 300f, 100f, 50f), f(3, 200f, 300f, 200f)))
        val a = Grafiche.allinea(g, setOf(1, 2, 3), Grafiche.A_SINISTRA)
        assertEquals(listOf(100f, 100f, 100f), a.elementi.map { it.x })
        val c = Grafiche.allinea(g, setOf(1, 2, 3), Grafiche.A_CENTRO)
        // il riquadro va da 100 a 400: centro 250
        assertTrue(c.elementi.all { kotlin.math.abs(it.cx - 250f) < eps })
        // uno solo: alla pagina
        assertEquals(Grafica.LARGO - 100f, Grafiche.allinea(g, setOf(1), Grafiche.A_DESTRA).trova(1)!!.x, eps)
    }

    @Test fun ilGruppoSiMuoveIntero() {
        val g0 = Grafica("x", Formato.A5, elementi = listOf(f(1, 0f, 0f), f(2, 120f, 0f), f(3, 500f, 400f)))
        val g = Grafiche.raggruppa(g0, setOf(1, 2))
        assertEquals(setOf(1L, 2L), Grafiche.insiemeDi(g, 2))
        val a = Grafiche.allinea(g, setOf(1, 2, 3), Grafiche.A_DESTRA)
        // il gruppo (0..220) va a destra intero: tiene la sua forma
        assertEquals(120f, a.trova(2)!!.x - a.trova(1)!!.x, eps)
        assertEquals(600f, a.trova(2)!!.x + 100f, eps)
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(Grafiche.insiemeDi(g, 1), Grafiche.insiemeDi(r, 1))
        assertEquals(setOf(1L), Grafiche.insiemeDi(Grafiche.separa(g, setOf(1, 2)), 1))
        // la copia di uno solo non entra nel gruppo
        val (d, nid) = Grafiche.duplica(g, 1)!!
        assertEquals(setOf(nid), Grafiche.insiemeDi(d, nid))
    }

    @Test fun spaziUguali() {
        val g = Grafica("x", Formato.A5, elementi = listOf(f(1, 0f, 0f), f(2, 130f, 0f), f(3, 600f, 0f)))
        val d = Grafiche.distribuisci(g, setOf(1, 2, 3), true)
        // 0..700, pieno 300: spazi da 200
        assertEquals(listOf(0f, 300f, 600f), d.elementi.map { it.x })
    }

    @Test fun laFotoRiempieSiSpostaENonLasciaBuchi() {
        val e = Elemento(1, Elemento.FOTO, 0f, 0f, 200f, 100f, uri = "u")
        // foto 400×400 che riempie 200×100: 200×200, centrata
        val r = Grafiche.rettangoloFoto(e, 400f, 400f)
        assertEquals(-50f, r.t, eps); assertEquals(200f, r.r - r.l, eps)
        val giu = Grafiche.spostaFoto(e, 400f, 400f, 0f, 30f)
        assertEquals(-20f, Grafiche.rettangoloFoto(giu, 400f, 400f).t, eps)
        // troppo: si ferma al bordo, e il fuoco dice dove si è fermata
        val tanto = Grafiche.spostaFoto(e, 400f, 400f, 0f, 500f)
        assertEquals(0f, Grafiche.rettangoloFoto(tanto, 400f, 400f).t, eps)
        assertEquals(tanto, Grafiche.spostaFoto(tanto, 400f, 400f, 0f, 10f))
        // intera: 100×100 al centro
        val intera = Grafiche.rettangoloFoto(e.copy(adatta = true), 400f, 400f)
        assertEquals(50f, intera.l, eps); assertEquals(100f, intera.r - intera.l, eps)
        val g = Grafica("x", Formato.A5, elementi = listOf(giu.copy(adatta = true, zoomFoto = 2f)))
        assertEquals(g.elementi, Grafiche.leggi(Grafiche.scrivi(g))!!.elementi)
    }

    @Test fun ilFiloTieneOrdineTestoESegni() {
        val a = Elemento(1, Elemento.TESTO, 0f, 0f, 100f, 100f, testo = "Hello ", segni = listOf(Segno(0, 5, Ricco.GRASSETTO)), filo = 7, filoN = 0)
        val b = Elemento(2, Elemento.TESTO, 0f, 0f, 100f, 100f, testo = "world", segni = listOf(Segno(0, 5, Ricco.CORSIVO)), filo = 7, filoN = 1, pagina = 1)
        val g = Grafica("x", Formato.A5, pagine = 2, elementi = listOf(a, b))
        val (t, sg) = Grafiche.testoDelFilo(Grafiche.filoDi(g, 7))
        assertEquals("Hello world", t)
        assertEquals(listOf(Segno(0, 5, Ricco.GRASSETTO), Segno(6, 11, Ricco.CORSIVO)), sg)
        assertEquals(listOf(Segno(0, 3, Ricco.GRASSETTO), Segno(4, 6, Ricco.CORSIVO)), Grafiche.segniDi(sg, 2, 8))
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(listOf(1L, 2L), Grafiche.filoDi(r, 7).map { it.id })
    }

    @Test fun dopoUnaColonnaLaColonnaDopoPoiLaPaginaDopo() {
        val g = Grafica("x", Formato.A5, pagine = 1, gabbia = Gabbia(10f, 10f, 10f, 10f, colonne = 2, spazio = 5f))
        val q = Grafiche.guide(g, 0)!!
        val prima = Elemento(1, Elemento.TESTO, q.colonne[0].first, q.t, q.colonne[0].second - q.colonne[0].first, q.b - q.t)
        val d = Grafiche.dopo(g, prima)
        assertEquals(0f, d[0], 0f); assertEquals(q.colonne[1].first, d[1], eps)
        val d2 = Grafiche.dopo(g, prima.copy(x = q.colonne[1].first))
        assertEquals(1f, d2[0], 0f)
    }
}
