package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Test

/** dopo: sfogliare il libro col dito. */
class SfogliaTest {
    private val dp = 2.625f
    @Test fun unDitoDiLatoSfoglia() {
        assertEquals(1, Sfoglia.verso(-400f, 30f, 300, dp))     // a sinistra: la pagina dopo
        assertEquals(-1, Sfoglia.verso(400f, -20f, 300, dp))    // a destra: quella prima
        assertEquals(1, Sfoglia.verso(-100f, 0f, 120, dp))      // corto ma veloce
        assertEquals(0, Sfoglia.verso(-100f, 0f, 600, dp))      // corto e lento
        assertEquals(0, Sfoglia.verso(-300f, 280f, 300, dp))    // storto
        assertEquals(0, Sfoglia.verso(-400f, 0f, 2000, dp))     // trascinato a lungo
    }
}

/** dopo: le pagine-foto di un PDF aperto stanno ferme. */
class PagineFermeTest {
    private val u = "file:///data/user/0/com.the151universe.art/files/grafica/media/pmw3hmyg83993.jpg"
    @Test fun lePagineFotoNonSiPrendonoColDito() {
        val g0 = Grafica("b", Formato.A5, pagine = 1)
        val pf = Elemento(1, Elemento.FOTO, 0f, 0f, Grafica.LARGO, g0.alto, uri = u)
        val g = g0.copy(elementi = listOf(pf.copy(fermo = true)))
        assertEquals(null, Grafiche.tocca(g, 500f, 500f, 10f))
        assertEquals(1L, Grafiche.tocca(Grafiche.fermaPagina(g, 0, false), 500f, 500f, 10f))
        // si salva; i file di prima (senza riga L) fermano da soli le pagine-foto, una foto normale no
        assertEquals(true, Grafiche.leggi(Grafiche.scrivi(g))!!.elementi.single().fermo)
        assertEquals(false, Grafiche.leggi(Grafiche.scrivi(Grafiche.fermaPagina(g, 0, false)))!!.elementi.single().fermo)
        val vecchio = Grafiche.scrivi(g).lines().filterNot { it.startsWith("L\t") }.joinToString("\n")
        assertEquals(true, Grafiche.leggi(vecchio)!!.elementi.single().fermo)
        val normale = g0.copy(elementi = listOf(pf.copy(uri = "file:///data/user/0/com.the151universe.art/files/grafica/media/m123.jpg")))
        assertEquals(false, Grafiche.leggi(Grafiche.scrivi(normale))!!.elementi.single().fermo)
    }
}
