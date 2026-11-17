package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Margini e colonne: guide, specchio sul libro, aggancio, file. */
class GabbiaTest {
    private val k = mm(1f) / Formato.A5.ptPerPunto // punti del foglio in un mm
    private fun eq(a: Float, b: Float) = assertEquals(a, b, 0.01f)

    @Test fun iMarginiSonoInMillimetriSullaPaginaTagliata() {
        val g = Grafica("Q", Formato.A5, gabbia = Gabbia(10f, 20f, 15f, 5f))
        val q = Grafiche.guide(g, 0)!!
        eq(10f * k, q.t); eq(g.alto - 20f * k, q.b)
        // una pagina sola: interno = sinistra
        eq(15f * k, q.l); eq(Grafica.LARGO - 5f * k, q.r)
        // A5 largo 148 mm: 1080 punti
        eq(1080f, 148f * k)
    }

    @Test fun nelLibroLePagineDiSinistraHannoLInternoADestra() {
        val g = Grafica("Q", Formato.A5, pagine = 4, gabbia = Gabbia(10f, 10f, 20f, 5f))
        val destra = Grafiche.guide(g, 0)!!; val sinistra = Grafiche.guide(g, 1)!!
        eq(20f * k, destra.l); eq(Grafica.LARGO - 5f * k, destra.r)
        eq(5f * k, sinistra.l); eq(Grafica.LARGO - 20f * k, sinistra.r)
        // la madre si guarda come pagina di destra
        eq(destra.l, Grafiche.guide(g, -1)!!.l)
    }

    @Test fun treColonneConLoSpazioInMezzo() {
        val g = Grafica("Q", Formato.A5, gabbia = Gabbia(10f, 10f, 10f, 10f, colonne = 3, spazio = 4f))
        val q = Grafiche.guide(g, 0)!!
        assertEquals(3, q.colonne.size)
        eq(q.l, q.colonne.first().first); eq(q.r, q.colonne.last().second)
        eq(4f * k, q.colonne[1].first - q.colonne[0].second)
        val larghe = q.colonne.map { it.second - it.first }
        assertTrue(larghe.all { kotlin.math.abs(it - larghe[0]) < 0.01f })
        eq((128f - 8f) / 3f * k, larghe[0])
    }

    @Test fun marginiCheNonLascianoPostoNonFannoGuide() {
        assertNull(Grafiche.guide(Grafica("Q", Formato.A5, gabbia = Gabbia(10f, 10f, 80f, 80f)), 0))
        assertNull(Grafiche.guide(Grafica("Q", Formato.A5), 0))
    }

    @Test fun unOggettoSiAttaccaAlMargineEAllaColonna() {
        val g = Grafica("Q", Formato.A5, gabbia = Gabbia(10f, 10f, 10f, 10f, colonne = 2, spazio = 5f))
        val q = Grafiche.guide(g, 0)!!
        val e = Elemento(1, Elemento.FORMA, 0f, 0f, 100f, 50f)
        // a 3 punti dal margine sinistro e da quello in alto: si attacca a tutti e due
        val a = Grafiche.aggancia(g, e, q.l + 3f, q.t - 3f, 8f)
        eq(-3f, a.dx); eq(3f, a.dy); eq(q.l, a.guidaX!!); eq(q.t, a.guidaY!!)
        // guide nascoste: niente calamita lì (né il bordo del foglio, troppo lontano)
        val b = Grafiche.aggancia(g, e, q.l + 3f, q.t - 3f, 8f, conGuide = false)
        assertNull(b.guidaX); assertNull(b.guidaY)
        // il lato destro tirato fin quasi al bordo della prima colonna ci si attacca
        eq(q.colonne[0].second, Grafiche.agganciaLato(g, e.copy(x = q.l), q.colonne[0].second - 4f, 8f)!!)
    }

    @Test fun laGabbiaSiSalvaESiRilegge() {
        val g = Grafica("Q", Formato.A5, pagine = 2, gabbia = Gabbia(12.5f, 15f, 18f, 9f, 2, 4.2f))
        assertEquals(g.gabbia, Grafiche.leggi(Grafiche.scrivi(g))!!.gabbia)
        assertNull(Grafiche.leggi(Grafiche.scrivi(g.copy(gabbia = null)))!!.gabbia)
        // un file con numeri fuori misura si legge dentro i limiti
        val rotto = Grafiche.scrivi(g).replace("G\t12.5\t", "G\t900\t").replace("\t2\t4.2", "\t40\t4.2")
        val r = Grafiche.leggi(rotto)!!.gabbia!!
        eq(Gabbia.MM_MAX, r.alto); assertEquals(Gabbia.COLONNE_MAX, r.colonne)
    }
}
