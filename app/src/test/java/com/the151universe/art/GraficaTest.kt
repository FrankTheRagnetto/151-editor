package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraficaTest {
    private val eps = 1e-3f
    private fun forma(id: Long, x: Float, y: Float, w: Float, h: Float, rot: Float = 0f) =
        Elemento(id, Elemento.FORMA, x, y, w, h, rot = rot, riempi = 0xFFFF0000.toInt())
    private fun foglio(f: Formato, vararg e: Elemento) = Grafica("x", f, elementi = e.toList())

    // ------------------------------------------------------------ formati

    @Test fun iFormatiHannoLaFormaGiusta() {
        assertEquals(297f / 210f, Formato.A4.alto / Grafica.LARGO, eps)
        assertEquals(1f, Formato.POST.alto / Grafica.LARGO, eps)
        assertEquals(16f / 9f, Formato.STORIA.alto / Grafica.LARGO, eps)
        for (f in Formato.entries) {
            // il file e il PDF hanno la stessa forma del foglio (niente deformazioni)
            assertEquals(f.id, f.alto / Grafica.LARGO, f.pxH.toFloat() / f.pxW, 0.002f)
            assertEquals(f.id, f.alto / Grafica.LARGO, f.pdfH.toFloat() / f.pdfW, 0.002f)
        }
        assertEquals(Formato.A4, Formato.da("rotto"))
    }

    // ------------------------------------------------------------ salvataggio

    @Test fun unDesignSalvatoSiRileggeUguale() {
        val g = Grafica("Camp\tsummer", Formato.STORIA, 0xFF112233.toInt(), "file:///x/m1", 35, listOf(
            Elemento(3, Elemento.TESTO, 10f, 20f, 500f, 130f, rot = -12.5f, opacita = 70, testo = "Hi\nthere\ttab \\ ok",
                font = "pacifico", misura = 88f, colore = 0xFFFF5C8A.toInt(), stile = Testo.RIQUADRO, allinea = Elemento.DESTRA),
            Elemento(7, Elemento.FOTO, 0f, 0f, 640f, 480f, uri = "content://media/1", maschera = Elemento.CERCHIO, bordo = -1),
            Elemento(9, Elemento.FORMA, 1f, 2f, 500f, 40f, forma = Elemento.LINEA, riempi = -1, spessore = 12f, tratto = Elemento.FRECCIA),
            Elemento(11, Elemento.FORMA, 5f, 6f, 300f, 200f, forma = Elemento.RETTANGOLO, riempi = 0, contorno = -16777216, raggio = 30f),
        ))
        assertEquals(g, Grafiche.leggi(Grafiche.scrivi(g)))
    }

    @Test fun righeRotteSiSaltanoMaiUnCrash() {
        val buono = Grafiche.scrivi(foglio(Formato.POST, forma(1, 0f, 0f, 100f, 100f), forma(2, 5f, 5f, 50f, 50f)))
        val righe = buono.trimEnd('\n').split('\n')
        val rotto = listOf(righe[0], righe[1].replace("\t100.0\t", "\tnove\t"), "spazzatura", righe[2], righe[2]).joinToString("\n")
        val g = Grafiche.leggi(rotto)!!
        assertEquals(listOf(2L), g.elementi.map { it.id }) // la rotta salta, il doppione pure
        assertNull(Grafiche.leggi("151foto 1\nqualcosa"))
        assertNull(Grafiche.leggi(""))
    }

    // ------------------------------------------------------------ dita

    @Test fun ilToccoTrovaQuelloPiuInAltoAncheGirato() {
        val sotto = forma(1, 0f, 0f, 1080f, 1080f)
        val girato = forma(2, 440f, 340f, 200f, 400f, rot = 90f) // girato: largo 400, alto 200 attorno a (540,540)
        val g = foglio(Formato.POST, sotto, girato)
        assertEquals(2L, Grafiche.tocca(g, 720f, 540f, 0f))   // dentro solo da girato
        assertEquals(1L, Grafiche.tocca(g, 540f, 360f, 0f))   // dentro solo da dritto: lì c'è quello sotto
        assertNull(Grafiche.tocca(foglio(Formato.POST, girato), 540f, 360f, 0f))
    }

    @Test fun righeFitteSiPrendonoAlPrimoColpo() {
        // la pagina 71: righe di testo alte 18 a passo 21 sopra una tabella che le contiene tutte
        val tab = forma(1, 0f, 0f, 1000f, 400f)
        fun riga(id: Long, y: Float) = Elemento(id, Elemento.TESTO, 100f, y, 600f, 18f, testo = "x")
        val g = foglio(Formato.POST, tab, riga(2, 100f), riga(3, 121f), riga(4, 142f))
        assertEquals(3L, Grafiche.tocca(g, 300f, 136f, 0f))  // metà bassa della riga di mezzo: lei, non quella sotto
        assertEquals(3L, Grafiche.tocca(g, 300f, 123f, 0f))  // metà alta: lei, non quella sopra
        assertEquals(2L, Grafiche.tocca(g, 300f, 119f, 0f))  // fra due righe (fuori da entrambe): la più vicina, non la tabella
        assertEquals(1L, Grafiche.tocca(g, 300f, 300f, 0f))  // lontano dalle righe: la tabella
    }

    @Test fun unOggettoNuovoNasceDoveSiGuarda() {
        // pagina ingrandita su un angolo: il testo nuovo nasce al centro di ciò che si vede, non a metà pagina
        val g = foglio(Formato.POST)
        val e = Elemento(0, Elemento.TESTO, 0f, 0f, 200f, 40f, testo = "x")
        val n = Grafiche.aggiungi(g, e, 0, 150f to 900f).elementi.last()
        assertEquals(50f, n.x, eps); assertEquals(880f, n.y, eps)
        // senza centro: in mezzo alla pagina, come prima
        val m = Grafiche.aggiungi(g, e, 0).elementi.last()
        assertEquals((Grafica.LARGO - 200f) / 2f, m.x, eps)
        // il centro vicino al bordo: l'oggetto resta dentro il foglio
        val b = Grafiche.aggiungi(g, e, 0, 10f to 5f).elementi.last()
        assertEquals(0f, b.x, eps); assertEquals(0f, b.y, eps)
    }

    @Test fun ilFormatoDiUnPdfApertoVieneDallaPagina() {
        // il libro del vocabolario (Typst): 419,528 × 595,276 = A5 esatto, senza abbondanza
        assertEquals(Formato.A5 to 0f, Grafiche.formatoPer(419.528f, 595.276f))
        // il quaderno di InDesign: A5 + 3 mm per lato
        assertEquals(Formato.A5 to 3f, Grafiche.formatoPer(436.535f, 612.283f))
        assertEquals(Formato.A4 to 0f, Grafiche.formatoPer(595f, 842f))
        // né A5 né A4: la forma più vicina
        assertEquals(Formato.POST, Grafiche.formatoPer(800f, 790f).first)
        assertEquals(Formato.STORIA, Grafiche.formatoPer(540f, 960f).first)
    }

    @Test fun unaLineaSottileSiToccaLostesso() {
        val l = Elemento(1, Elemento.FORMA, 100f, 500f, 400f, 4f, forma = Elemento.LINEA, riempi = -1)
        assertEquals(1L, Grafiche.tocca(foglio(Formato.POST, l), 300f, 518f, 0f))
        assertNull(Grafiche.tocca(foglio(Formato.POST, l), 300f, 560f, 0f))
    }

    @Test fun ilCentroDelFoglioAttira() {
        val e = forma(1, 0f, 0f, 200f, 100f)
        val g = foglio(Formato.POST, e)
        val a = Grafiche.aggancia(g, e, 437f, 700f, 8f) // centro x a 537: 3 punti dal centro del foglio
        assertEquals(3f, a.dx, eps)
        assertEquals(540f, a.guidaX!!, eps)
        assertNull(a.guidaY)
        val lontano = Grafiche.aggancia(g, e, 400f, 700f, 8f)
        assertEquals(0f, lontano.dx, eps); assertNull(lontano.guidaX)
    }

    @Test fun siAttaccaAgliAltriMaNonASeStesso() {
        val altro = forma(2, 600f, 100f, 200f, 200f)
        val io = forma(1, 100f, 100f, 100f, 100f)
        val g = foglio(Formato.POST, io, altro)
        val a = Grafiche.aggancia(g, io, 497f, 150f, 8f) // bordo destro a 597: 3 dal bordo sinistro dell'altro (il centro è a 7 da quello del foglio)
        assertEquals(3f, a.dx, eps)
        assertEquals(600f, a.guidaX!!, eps)
        // da solo, alla stessa posizione, vince il centro del foglio
        assertEquals(540f, Grafiche.aggancia(foglio(Formato.POST, io), io, 497f, 150f, 8f).guidaX!!, eps)
    }

    @Test fun ilGiroSiAttaccaAgliAngoliGiusti() {
        assertEquals(0f, Grafiche.gradi(3f), eps)
        assertEquals(45f, Grafiche.gradi(47.5f), eps)
        assertEquals(-90f, Grafiche.gradi(270f), eps)
        assertEquals(20f, Grafiche.gradi(20f), eps)
        assertEquals(180f, Grafiche.gradi(-179f), eps)
        assertEquals(-170f, Grafiche.gradi(190f), eps)
    }

    @Test fun ingrandireTieneIlCentroELaLineaResta() {
        val e = forma(1, 100f, 100f, 200f, 100f)
        val k = Grafiche.scala(e, 2f)
        assertEquals(e.cx, k.cx, eps); assertEquals(e.cy, k.cy, eps)
        assertEquals(400f, k.w, eps); assertEquals(200f, k.h, eps)
        val l = Elemento(2, Elemento.FORMA, 0f, 0f, 300f, Elemento.ALTO_LINEA, forma = Elemento.LINEA)
        assertEquals(Elemento.ALTO_LINEA, Grafiche.scala(l, 3f).h, eps)
        val t = Elemento(3, Elemento.TESTO, 0f, 0f, 400f, 100f, testo = "Hi", misura = 80f)
        assertEquals(40f, Grafiche.scala(t, 0.5f).misura, eps)
        assertTrue(Grafiche.scala(e, 0.0001f).w >= Elemento.MIN)
        // al limite delle lettere il riquadro si ferma con loro (niente testo a capo lettera per lettera)
        val piccolo = Grafiche.scala(t.copy(misura = 10f), 0.1f)
        assertEquals(8f, piccolo.misura, eps); assertEquals(320f, piccolo.w, eps)
    }

    // ------------------------------------------------------------ livelli, copie

    @Test fun avantiEDietroSiFermanoAlBordo() {
        val g = foglio(Formato.POST, forma(1, 0f, 0f, 10f, 10f), forma(2, 0f, 0f, 10f, 10f), forma(3, 0f, 0f, 10f, 10f))
        assertEquals(listOf(2L, 1L, 3L), Grafiche.livello(g, 1, +1).elementi.map { it.id })
        assertEquals(g, Grafiche.livello(g, 3, +1))
        assertEquals(g, Grafiche.livello(g, 1, -1))
        assertEquals(g, Grafiche.livello(g, 99, +1))
    }

    @Test fun laCopiaStaSubitoSopraConUnIdNuovo() {
        val g = foglio(Formato.POST, forma(1, 0f, 0f, 10f, 10f), forma(5, 0f, 0f, 10f, 10f))
        val (n, id) = Grafiche.duplica(g, 1)!!
        assertEquals(6L, id)
        assertEquals(listOf(1L, 6L, 5L), n.elementi.map { it.id })
        assertEquals(40f, n.trova(6)!!.x, eps)
        assertNull(Grafiche.duplica(g, 42))
        val nuovo = Grafiche.aggiungi(g, forma(0, 0f, 0f, 100f, 100f))
        assertEquals(6L, nuovo.elementi.last().id)
        assertEquals(490f, nuovo.elementi.last().x, eps)
    }

    // ------------------------------------------------------------ cambio formato

    @Test fun cambiandoFormatoLeCoseRestanoAlLoroPosto() {
        val a4 = Formato.A4.alto
        val fascia = forma(1, 0f, a4 - 300f, 1080f, 300f)                  // tocca il fondo
        val suFascia = Elemento(2, Elemento.TESTO, 80f, a4 - 250f, 600f, 60f, testo = "Date")
        val sopra = forma(3, 100f, 0f, 300f, 100f)                         // tocca la cima
        val mezzo = forma(4, 400f, a4 / 2f - 50f, 200f, 100f)               // nel mezzo
        val sfondo = forma(5, 0f, 0f, 1080f, a4)                           // tocca tutti e due
        val g = Grafiche.cambiaFormato(foglio(Formato.A4, sfondo, fascia, suFascia, sopra, mezzo), Formato.STORIA)
        val st = Formato.STORIA.alto
        assertEquals(Formato.STORIA, g.formato)
        assertEquals(st - 300f, g.trova(1)!!.y, eps)
        assertEquals(st - 250f, g.trova(2)!!.y, eps)
        assertEquals(0f, g.trova(3)!!.y, eps)
        assertEquals(st / 2f, g.trova(4)!!.cy, eps)
        assertEquals(st, g.trova(5)!!.h, eps)
        assertEquals(g, Grafiche.cambiaFormato(g, Formato.STORIA))
    }

    // ------------------------------------------------------------ modelli

    @Test fun leFotoInUsoSonoTutte() {
        val g = Grafica("x", Formato.POST, fotoSfondo = "file:///s", elementi = listOf(
            Elemento(1, Elemento.FOTO, 0f, 0f, 1f, 1f, uri = "file:///a"),
            Elemento(2, Elemento.FOTO, 0f, 0f, 1f, 1f, uri = ""),
            Elemento(3, Elemento.FOTO, 0f, 0f, 1f, 1f, uri = "file:///a"),
        ))
        assertEquals(setOf("file:///s", "file:///a"), Grafiche.foto(g))
        assertNotNull(Grafiche.leggi(Grafiche.scrivi(g)))
    }

    @Test fun ilContrarioSiLegge() {
        assertEquals(Elemento.NERO, Grafiche.contrario(0xFFFFD60A.toInt()))
        assertEquals(Elemento.BIANCO, Grafiche.contrario(0xFF1B2A4A.toInt()))
    }

    @Test fun unTestoConBoxSuMisuraNelDesignSiRileggeUguale() {
        val g = Grafica("x", Formato.STORIA, 0xFF112233.toInt(), "", 0, listOf(
            Elemento(3, Elemento.TESTO, 10f, 20f, 500f, 130f, testo = "Hi", stile = Testo.RIQUADRO, secondo = 0xFFFF3B30.toInt()),
            Elemento(4, Elemento.TESTO, 10f, 220f, 500f, 130f, testo = "Yo", stile = Testo.CONTORNO, spessoreTesto = 0.06f),
            Elemento(5, Elemento.TESTO, 10f, 420f, 500f, 130f, testo = "plain"),
        ))
        val s = Grafiche.scrivi(g)
        assertEquals(g, Grafiche.leggi(s))
        // il testo senza opzioni resta di 24 campi
        assertTrue(s.lines().any { it.contains("\tplain\t") && it.split('\t').size == 24 })
    }

    // ------------------------------------------------------------ pagine vere (A5 + abbondanza)

    @Test fun a5ConAbbondanzaHaLeMisureDellaTipografia() {
        val f = Formato.A5
        // come il quaderno di InDesign: MediaBox = BleedBox 436,535 × 612,283, taglio a 8,504 dal bordo
        assertEquals(436.535f, f.mediaW, 0.001f)
        assertEquals(612.283f, f.mediaH, 0.001f)
        assertEquals(8.504f, f.abbondanzaPt, 0.001f)
        assertEquals(1080f * 3f / 148f, f.abbondanza, 0.01f)
        assertEquals(1748, f.pxW); assertEquals(2480, f.pxH) // 148×210 mm a 300 dpi
        assertEquals(0f, Formato.A4.abbondanza, 0f)
        assertEquals(Formato.A5, Formato.da("a5"))
    }

    @Test fun lePagineSiAggiungonoDuplicanoTolgonoSpostano() {
        val g0 = Grafica("x", Formato.A5, elementi = listOf(forma(1, 0f, 0f, 10f, 10f), forma(2, 5f, 5f, 10f, 10f)))
        val (g1, p1) = Grafiche.aggiungiPagina(g0, 0)
        assertEquals(2, g1.pagine); assertEquals(1, p1)
        val (g2, p2) = Grafiche.duplicaPagina(g1, 0)        // [A, A', vuota]
        assertEquals(3, g2.pagine); assertEquals(1, p2)
        assertEquals(listOf(0, 0, 1, 1), g2.elementi.map { it.pagina })
        assertEquals(4, g2.elementi.map { it.id }.toSet().size) // la copia ha id nuovi
        assertTrue(g2.di(2).isEmpty())
        // la vuota in testa: le altre scalano
        val g3 = Grafiche.spostaPagina(g2, 2, 0)
        assertEquals(listOf(1, 1, 2, 2), g3.elementi.map { it.pagina })
        assertEquals(listOf(1L, 2L), g3.di(1).map { it.id })  // i livelli restano
        assertEquals(listOf(1, 2, 0), (0..2).map { Grafiche.posto(2, 0, it) }) // la pagina aperta segue lo spostamento
        val g4 = Grafiche.eliminaPagina(g3, 1)
        assertEquals(2, g4.pagine)
        assertEquals(listOf(1, 1), g4.elementi.map { it.pagina })
        // l'ultima pagina non si toglie
        val sola = Grafica("x", Formato.A5)
        assertEquals(sola, Grafiche.eliminaPagina(sola, 0))
        // una pagina in testa (-1)
        val (g5, p5) = Grafiche.aggiungiPagina(g0, -1)
        assertEquals(0, p5); assertEquals(listOf(1, 1), g5.elementi.map { it.pagina })
    }

    @Test fun piuPagineSiSalvanoESiRileggonoAncheLaVuota() {
        val g = Grafica("Quaderno", Formato.A5, pagine = 4, elementi = listOf(
            forma(1, -21.9f, -21.9f, 1123.8f, 400f),
            forma(2, 10f, 10f, 50f, 50f).copy(pagina = 1),
            forma(3, 20f, 20f, 50f, 50f).copy(pagina = 3),
            forma(4, 30f, 30f, 50f, 50f).copy(pagina = 1),
        ))
        val letto = Grafiche.leggi(Grafiche.scrivi(g))!!
        assertEquals(4, letto.pagine)                  // la pagina 2 è vuota ma resta
        assertEquals(listOf(2L, 4L), letto.di(1).map { it.id })
        assertEquals(listOf(3L), letto.di(3).map { it.id })
        assertEquals(-21.9f, letto.trova(1)!!.x, eps)  // nell'abbondanza
        assertEquals(g.copy(elementi = g.elementi.sortedBy { it.pagina }), letto)
        // i file di prima (senza righe «P»): una pagina sola, tutto sulla prima
        val vecchio = Grafiche.scrivi(foglio(Formato.POST, forma(1, 0f, 0f, 100f, 100f)))
        assertTrue(!vecchio.contains("\nP\t"))
        assertEquals(1, Grafiche.leggi(vecchio)!!.pagine)
    }

    @Test fun ilDitoEIlivelliVedonoSoloLaPaginaAperta() {
        val g = Grafica("x", Formato.A5, pagine = 2, elementi = listOf(
            forma(1, 0f, 0f, 1080f, 1080f), forma(2, 0f, 0f, 1080f, 1080f).copy(pagina = 1), forma(3, 0f, 0f, 10f, 10f)))
        assertEquals(1L, Grafiche.tocca(g, 500f, 500f, 0f, pagina = 0))
        assertEquals(2L, Grafiche.tocca(g, 500f, 500f, 0f, pagina = 1))
        // 1 avanti salta la 2 (altra pagina) e passa sopra la 3
        assertEquals(listOf(3L, 2L, 1L), Grafiche.livello(g, 1, +1).elementi.map { it.id })
        assertTrue(Grafiche.alBordo(g, 2, +1)); assertTrue(Grafiche.alBordo(g, 2, -1))
        assertEquals(1, Grafiche.aggiungi(g, forma(0, 0f, 0f, 10f, 10f), pagina = 1).elementi.last().pagina)
        // l'aggancio non vede gli oggetti delle altre pagine
        val io = forma(9, 0f, 0f, 100f, 100f).copy(pagina = 1)
        val due = Grafica("x", Formato.POST, pagine = 2, elementi = listOf(forma(8, 300f, 300f, 100f, 100f), io))
        assertNull(Grafiche.aggancia(due, io, 297f, 297f, 8f).guidaX)
    }

    @Test fun conLAbbondanzaCiSiAttaccaAncheAlSuoBordo() {
        val ab = Formato.A5.abbondanza
        val e = forma(1, 0f, 300f, 200f, 100f)
        val g = Grafica("x", Formato.A5, elementi = listOf(e))
        val a = Grafiche.aggancia(g, e, -ab + 3f, 300f, 8f)
        assertEquals(-ab, a.guidaX!!, eps)
        assertEquals(-3f, a.dx, eps)
    }

    @Test fun lAbbondanzaSiSceglieESiSalvaMaSoloPerLaStampa() {
        val a4 = Grafica("x", Formato.A4, sceltaAbbondanza = 5f)
        assertEquals(5f, a4.abbondanzaMm, 0f)
        assertEquals(595f + 2 * mm(5f), a4.mediaW, 0.001f)
        assertEquals(a4, Grafiche.leggi(Grafiche.scrivi(a4)))
        val nessuna = Grafica("x", Formato.A5, sceltaAbbondanza = 0f)
        assertEquals(0f, nessuna.abbondanza, 0f)
        assertEquals(mm(148f), nessuna.mediaW, 0.001f)
        // Post e Story non vanno in tipografia: nessuna fascia, qualunque scelta
        assertEquals(0f, Grafica("x", Formato.POST, sceltaAbbondanza = 5f).abbondanza, 0f)
        // senza scelta vale quella del formato, e il file resta come prima (nessuna riga «B»)
        assertEquals(3f, Grafica("x", Formato.A5).abbondanzaMm, 0f)
        assertTrue(!Grafiche.scrivi(Grafica("x", Formato.A5)).contains("\nB\t"))
    }

    /**: con due pagine affiancate un testo lasciato sulla pagina accanto passa su quella, non sparisce. */
    @Test fun ilTestoPortatoSullaPaginaAccantoCiPassa() {
        val testo = Elemento(1, Elemento.TESTO, 1000f, 100f, 200f, 40f, testo = "jumped", pagina = 1)
        val ferma = Elemento(2, Elemento.TESTO, 100f, 100f, 200f, 40f, testo = "stays", pagina = 1)
        val g = Grafica("x", Formato.A5, elementi = listOf(testo, ferma), pagine = 3)
        // pagina 1 a sinistra, la 2 a destra: il centro (1100) è oltre la piega (1080) → pagina 2, x = 1000 - 1080
        val dopo = Grafiche.dopoLaPiega(g, setOf(1L), aperta = 1, compagna = 2, compagnaASinistra = false)
        assertEquals(2, dopo.trova(1)!!.pagina)
        assertEquals(-80f, dopo.trova(1)!!.x, eps)
        assertEquals(100f, dopo.trova(1)!!.y, eps)
        assertEquals(ferma, dopo.trova(2))
        // pagina 2 aperta (a destra), la 1 a sinistra: un testo con il centro a -50 torna sulla 1, x + 1080
        val sx = Grafica("x", Formato.A5, elementi = listOf(testo.copy(pagina = 2, x = -150f)), pagine = 3)
        val dopo2 = Grafiche.dopoLaPiega(sx, setOf(1L), aperta = 2, compagna = 1, compagnaASinistra = true)
        assertEquals(1, dopo2.trova(1)!!.pagina)
        assertEquals(930f, dopo2.trova(1)!!.x, eps)
        // ancora per metà sulla sua pagina: resta dov'è; senza compagna (la prima pagina da sola): resta
        val meta = g.copy(elementi = listOf(testo.copy(x = 950f)))
        assertEquals(meta, Grafiche.dopoLaPiega(meta, setOf(1L), aperta = 1, compagna = 2, compagnaASinistra = false))
        val sola = g.copy(elementi = listOf(testo.copy(pagina = 0)))
        assertEquals(sola, Grafiche.dopoLaPiega(sola, setOf(1L), aperta = 0, compagna = -1, compagnaASinistra = false))
        // più oggetti insieme: passano tutti, anche quello rimasto di qua, se il centro del gruppo è di là
        val due = g.copy(elementi = listOf(testo.copy(x = 1100f), ferma.copy(x = 1000f)))
        val tutti = Grafiche.dopoLaPiega(due, setOf(1L, 2L), aperta = 1, compagna = 2, compagnaASinistra = false)
        assertEquals(listOf(2, 2), tutti.elementi.map { it.pagina })
        assertEquals(listOf(20f, -80f), tutti.elementi.map { it.x })
    }

}
