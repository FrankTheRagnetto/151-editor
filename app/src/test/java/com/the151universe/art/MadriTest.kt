package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pagine madri: file, pagine che cambiano, numero di pagina. */
class MadriTest {
    private fun riga(id: Long, p: Int, t: String = "x") = Elemento(id, Elemento.TESTO, 10f, 10f, 200f, 40f, testo = t, pagina = p)

    private fun quaderno(): Grafica {
        val (g0, k) = Grafiche.nuovaMadre(Grafica("Q", Formato.A5, pagine = 3), "Lined")
        val g = g0.copy(elementi = listOf(riga(1, -k, "Page #"), riga(2, 0, "cover"), riga(3, 2, "two")))
        return Grafiche.usaMadre(g, k, listOf(1, 2))
    }

    @Test fun laMadreSiSalvaESiRilegge() {
        val g = quaderno()
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(listOf("Lined"), r.nomiMadri)
        assertEquals(listOf(0, 1, 1), r.madreDi)
        assertEquals(mapOf(1L to -1, 2L to 0, 3L to 2), r.elementi.associate { it.id to it.pagina })
        assertEquals(3, r.pagine)
    }

    @Test fun unFileDiPrimaNonHaMadri() {
        val g = Grafica("Old", Formato.A5, elementi = listOf(riga(1, 0)), pagine = 1)
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(0, r.madri); assertEquals(0, r.madre(0)); assertEquals(0, r.elementi.single().pagina)
    }

    @Test fun sessantaPagineNuoveHannoLaMadreDellaPagina() {
        val (g, prima) = Grafiche.aggiungiPagine(quaderno(), 2, 60)
        assertEquals(63, g.pagine); assertEquals(3, prima)
        assertTrue((3 until 63).all { g.madre(it) == 1 })
        // gli oggetti della madre restano sulla madre
        assertEquals(-1, g.trova(1)!!.pagina)
    }

    @Test fun lePagineCheSiSpostanoPortanoLaLoroMadre() {
        var g = quaderno()                                   // madri 0,1,1
        g = Grafiche.spostaPagina(g, 2, 0)                   // la pagina 2 va in testa
        assertEquals(listOf(1, 0, 1), g.madreDi)
        assertEquals(0, g.trova(3)!!.pagina); assertEquals(-1, g.trova(1)!!.pagina)
        g = Grafiche.eliminaPagina(g, 1)
        assertEquals(listOf(1, 1), g.madreDi)
        g = Grafiche.duplicaPagina(g, 0).first
        assertEquals(listOf(1, 1, 1), g.madreDi)
    }

    @Test fun viaLaMadreVannoViaISuoiOggetti() {
        val g = Grafiche.eliminaMadre(quaderno(), 1)
        assertEquals(0, g.madri); assertEquals(listOf(0, 0, 0), g.madreDi)
        assertTrue(g.elementi.none { it.pagina < 0 })
    }

    @Test fun ilCancellettoDiventaIlNumeroDiPagina() {
        val e = riga(1, -1, "Page # of 72").copy(segni = listOf(Segno(5, 6, Ricco.GRASSETTO), Segno(10, 12, Ricco.CORSIVO)))
        val n = Grafiche.numera(e, 12)
        assertEquals("Page 12 of 72", n.testo)
        assertEquals(listOf(Segno(5, 7, Ricco.GRASSETTO), Segno(11, 13, Ricco.CORSIVO)), n.segni)
        assertEquals("Score /", Grafiche.numera(riga(2, -1, "Score /"), 3).testo)
    }

    @Test fun unOggettoNuovoSullaMadreRestaSullaMadre() {
        val g = Grafiche.aggiungi(quaderno(), riga(0, 0), -1)
        assertEquals(-1, g.elementi.last().pagina)
    }
}
