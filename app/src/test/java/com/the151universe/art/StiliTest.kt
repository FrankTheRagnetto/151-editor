package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stili di testo (Title, Heading, Body) e margini propri delle madri. */
class StiliTest {
    private fun t(id: Long, s: String, p: Int = 0) = Elemento(id, Elemento.TESTO, 10f, 10f, 300f, 40f, testo = s, pagina = p, misura = 30f)
    private val titolo = StileTesto("Title", "liberation", 60f, Elemento.NERO, Elemento.CENTRO, Ricco.GRASSETTO)

    private fun libro(): Grafica {
        val g = Grafica("Q", Formato.A5, pagine = 2, elementi = listOf(t(1, "Animals"), t(2, "Food", 1), t(3, "cat")))
        val (n, k) = Grafiche.nuovoStile(g, titolo)
        return Grafiche.usaStile(n, setOf(1, 2), k)
    }

    @Test fun unTestoPrendeTuttoLoStile() {
        val e = libro().trova(1)!!
        assertEquals(1, e.classe); assertEquals(60f, e.misura); assertEquals(Elemento.CENTRO, e.allinea)
        assertTrue(Ricco.tutto(e.testo.length, e.segni, 0, e.testo.length, Ricco.GRASSETTO))
        assertEquals(0, libro().trova(3)!!.classe)
    }

    @Test fun cambiatoLoStileCambianoTuttiISuoiTestiSuOgniPagina() {
        val g = Grafiche.cambiaStile(libro(), 1, titolo.copy(misura = 72f, segno = 0), alto = { 99f })
        for (id in listOf(1L, 2L)) { val e = g.trova(id)!!; assertEquals(72f, e.misura); assertTrue(e.segni.isEmpty()); assertEquals(99f, e.h) }
        assertEquals(30f, g.trova(3)!!.misura)
    }

    @Test fun ilTestoCambiatoAManoSiVedeESiPuoRifareLoStileDaLui() {
        val g = libro()
        assertFalse(Grafiche.cambiatoDalloStile(g, g.trova(1)!!))
        val mano = g.trova(1)!!.copy(misura = 80f)
        val g2 = Grafiche.sostituisci(g, mano)
        assertTrue(Grafiche.cambiatoDalloStile(g2, mano))
        val g3 = Grafiche.cambiaStile(g2, 1, Grafiche.stileDa("Title", mano))
        assertEquals(80f, g3.trova(2)!!.misura)
        assertEquals(Ricco.GRASSETTO, g3.stili[0].segno)
    }

    @Test fun eliminatoLoStileITestiRestanoComeSono() {
        val (g0, _) = Grafiche.nuovoStile(libro(), titolo.copy(nome = "Word"))
        val g1 = Grafiche.usaStile(g0, setOf(3), 2)
        val g = Grafiche.eliminaStile(g1, 1)
        assertEquals(listOf("Word"), g.stili.map { it.nome })
        assertEquals(0, g.trova(1)!!.classe); assertEquals(60f, g.trova(1)!!.misura)
        assertEquals(1, g.trova(3)!!.classe)
    }

    @Test fun stiliELegamiSiSalvanoESiRileggono() {
        val g = libro()
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(g.stili, r.stili)
        assertEquals(mapOf(1L to 1, 2L to 1, 3L to 0), r.elementi.associate { it.id to it.classe })
        // un file senza stili si legge uguale
        assertTrue(Grafiche.leggi(Grafiche.scrivi(g.copy(stili = emptyList(), elementi = g.elementi.map { it.copy(classe = 0) })))!!.stili.isEmpty())
    }

    @Test fun iNomiPropostiSonoTitleHeadingBody() {
        val g = Grafica("Q", Formato.A5)
        assertEquals("Title", Grafiche.nomeNuovoStile(g))
        val (g2, _) = Grafiche.nuovoStile(g, titolo)
        assertEquals("Heading", Grafiche.nomeNuovoStile(g2))
    }

    @Test fun unaMadreHaIMarginiSuoiEPassanoAlleSuePagine() {
        val (g0, k) = Grafiche.nuovaMadre(Grafica("Q", Formato.A5, pagine = 3, gabbia = Gabbia(10f, 10f, 10f, 10f)), "Lined")
        val g = Grafiche.gabbiaMadre(Grafiche.usaMadre(g0, k, listOf(1, 2)), k, Gabbia(20f, 20f, 20f, 20f, 2))
        assertEquals(10f, Grafiche.gabbiaDi(g, 0)!!.alto)
        assertEquals(20f, Grafiche.gabbiaDi(g, 1)!!.alto); assertEquals(20f, Grafiche.gabbiaDi(g, -1)!!.alto)
        val r = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(g.gabbia, r.gabbia); assertEquals(g.gabbieMadri, r.gabbieMadri)
        // via la madre, via i suoi margini
        assertTrue(Grafiche.eliminaMadre(g, k).gabbieMadri.isEmpty())
        assertNull(Grafiche.gabbiaDi(Grafiche.gabbiaMadre(g, k, null).copy(gabbia = null), 1))
    }

    @Test fun ilTestoNuovoTrovaLaColonnaPiuVicina() {
        val g = Grafica("Q", Formato.A5, gabbia = Gabbia(10f, 10f, 10f, 10f, colonne = 2, spazio = 5f))
        val q = Grafiche.guide(g, 0)!!
        assertEquals(q.colonne[0], Grafiche.colonnaVicina(g, 0, 200f))
        assertEquals(q.colonne[1], Grafiche.colonnaVicina(g, 0, 900f))
        val una = g.copy(gabbia = Gabbia())
        val u = Grafiche.guide(una, 0)!!
        assertEquals(u.l to u.r, Grafiche.colonnaVicina(una, 0, 540f))
    }
}

/** dopo: lo stile scelto vale anche per il testo nuovo. */
class StileNuovoTest {
    @Test fun ilTestoNuovoPrendeLoStileScelto() {
        val a = StileTesto("Title", "liberation", 40f, 0, Elemento.CENTRO)
        val b = StileTesto("Word", "liberation", 20f, 0, Elemento.SINISTRA)
        val t = Elemento(1, Elemento.TESTO, 0f, 0f, 100f, 40f, testo = "x", font = "liberation", misura = 40f, colore = 0, allinea = Elemento.CENTRO, classe = 1)
        val g = Grafica("s", Formato.A5, elementi = listOf(t), stili = listOf(a, b))
        assertEquals(2, Grafiche.stileDelNuovo(g, 2, t))      // l'ultimo toccato vince
        assertEquals(0, Grafiche.stileDelNuovo(g, 0, t))      // «None» toccato
        assertEquals(1, Grafiche.stileDelNuovo(g, null, t))   // niente toccato: quello dell'ultimo testo
        assertEquals(0, Grafiche.stileDelNuovo(g, null, t.copy(misura = 33f)))  // l'ultimo è cambiato a mano
        assertEquals(0, Grafiche.stileDelNuovo(g, 7, t))      // stile che non c'è più
        assertEquals(0, Grafiche.stileDelNuovo(g, null, null))
    }
}

/** dopo: gli stili nelle celle delle tabelle. */
class StileCellaTest {
    private fun tab(celle: List<Cella>) = Elemento(9, Elemento.TABELLA, 0f, 0f, 400f, 100f, misura = 20f, colore = -16777216, font = "liberation",
        colonne = listOf(0.5f, 0.5f), righe = listOf(1f), celle = celle)

    @Test fun loStileVaNellaCellaESiAggiornaOvunque() {
        val titolo = StileTesto("Title", "liberation", 30f, -16777216, Elemento.SINISTRA, Ricco.GRASSETTO)
        val g = Grafica("t", Formato.A5, elementi = listOf(tab(listOf(Cella("verb"), Cella("")))), stili = listOf(titolo))
        val n = Grafiche.usaStileCella(g, 9, 0, 0, 1)
        val c = Tabelle.cella(n.trova(9)!!, 0, 0)!!
        assertEquals(1, c.classe); assertEquals(Elemento.SINISTRA, c.allinea)
        // carattere e colore uguali alla tabella: non entrano nei segni; misura e grassetto sì
        assertEquals(listOf(Segno(0, 4, Ricco.GRASSETTO, 0, "", 30f)), c.segni)
        assertTrue(!Grafiche.cambiataDalloStile(n, n.trova(9)!!, c))
        // lo stile cambia: cambia anche la cella
        val m = Grafiche.cambiaStile(n, 1, titolo.copy(misura = 12f))
        assertEquals(12f, Tabelle.cella(m.trova(9)!!, 0, 0)!!.segni.single().misura, 0f)
        // si salva e si rilegge (la classe in fondo alla cella), le celle senza stile come prima
        val l = Grafiche.leggi(Grafiche.scrivi(m))!!
        assertEquals(1, Tabelle.cella(l.trova(9)!!, 0, 0)!!.classe); assertEquals(0, Tabelle.cella(l.trova(9)!!, 0, 1)!!.classe)
        // stile da una cella: misura dai segni, carattere dalla tabella
        assertEquals(StileTesto("X", "liberation", 12f, -16777216, Elemento.SINISTRA, Ricco.GRASSETTO), Grafiche.stileDaCella("X", m.trova(9)!!, Tabelle.cella(m.trova(9)!!, 0, 0)!!))
        // via lo stile: la cella resta com'è, staccata
        assertEquals(0, Tabelle.cella(Grafiche.eliminaStile(m, 1).trova(9)!!, 0, 0)!!.classe)
    }

    @Test fun unaCellaVuotaConLoStileLoDaAlleLetterePrime() {
        val titolo = StileTesto("Title", "liberation", 30f, -16777216, Elemento.CENTRO, Ricco.CORSIVO)
        val g0 = Grafica("t", Formato.A5, elementi = listOf(tab(listOf(Cella(""), Cella("")))), stili = listOf(titolo))
        val g = Grafiche.usaStileCella(g0, 9, 0, 1, 1)
        val c = Grafiche.scrittaInCella(g, g.trova(9)!!, 0, 1, "go", emptyList())!!
        assertEquals(listOf(Segno(0, 2, Ricco.CORSIVO, 0, "", 30f)), c.segni)
        // una cella senza stile resta com'è scritta
        assertEquals(emptyList<Segno>(), Grafiche.scrittaInCella(g, g.trova(9)!!, 0, 0, "go", emptyList())!!.segni)
    }
}
