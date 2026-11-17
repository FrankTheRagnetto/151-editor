package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiccoTabellaTest {
    private val eps = 1e-3f

    // ------------------------------------------------------------ testo misto

    @Test fun ilPulsanteMetteEToglieSoloSullaScelta() {
        val t = "verbing"                       // «verb» normale, «ing» sottolineato
        var s = Ricco.cambia(t.length, emptyList(), 4, 7, Ricco.SOTTOLINEATO)
        assertEquals(listOf(Segno(4, 7, Ricco.SOTTOLINEATO)), s)
        s = Ricco.cambia(t.length, s, 0, 7, Ricco.GRASSETTO)  // tutto grassetto
        assertEquals(listOf(Segno(0, 4, 1), Segno(4, 7, 5)), s)
        s = Ricco.cambia(t.length, s, 0, 7, Ricco.GRASSETTO)  // già tutto grassetto: si toglie
        assertEquals(listOf(Segno(4, 7, Ricco.SOTTOLINEATO)), s)
        assertTrue(Ricco.tutto(t.length, s, 5, 7, Ricco.SOTTOLINEATO))
        assertFalse(Ricco.tutto(t.length, s, 3, 7, Ricco.SOTTOLINEATO))
    }

    @Test fun segniVicinieUgualiSiUnisconoERottiSiSaltano() {
        assertEquals(listOf(Segno(0, 6, 2)), Ricco.normalizza(10, listOf(Segno(0, 3, 2), Segno(3, 6, 2), Segno(8, 8, 1))))
        assertEquals(listOf(Segno(2, 5, 3)), Ricco.leggi("2,5,3;rotto;1,x,2;9,99,1", 5)) // fuori dal testo: tagliato
        assertEquals(emptyList<Segno>(), Ricco.leggi("", 10))
    }

    @Test fun unTestoMistoSiSalvaESiRilegge() {
        val e = Elemento(1, Elemento.TESTO, 0f, 0f, 500f, 40f, testo = "article + noun:", font = "liberation", misura = 23f,
            segni = listOf(Segno(0, 15, Ricco.GRASSETTO or Ricco.CORSIVO)))
        val g = Grafica("x", Formato.A5, elementi = listOf(e))
        assertEquals(g, Grafiche.leggi(Grafiche.scrivi(g)))
    }

    @Test fun unPezzoHaColoreFontEMisuraSuoi() {
        val t = "I jumped. = Io ho saltato."
        val rosso = 0xFFE23B3B.toInt()
        var s = Ricco.colore(t.length, emptyList(), 2, 8, rosso)
        s = Ricco.cambia(t.length, s, 2, 8, Ricco.GRASSETTO)
        s = Ricco.misura(t.length, s, 2, 8, 18f)
        assertEquals(listOf(Segno(2, 8, Ricco.GRASSETTO, rosso, "", 18f)), s)
        // si salva e si rilegge, anche dentro un oggetto
        assertEquals(s, Ricco.leggi(Ricco.scrivi(s), t.length))
        val e = Elemento(1, Elemento.TESTO, 0f, 0f, 500f, 40f, testo = t, font = "liberation", misura = 15f, segni = s)
        val g = Grafica("x", Formato.A5, elementi = listOf(e))
        assertEquals(g, Grafiche.leggi(Grafiche.scrivi(g)))
        // i vecchi segni a 3 campi si leggono ancora
        assertEquals(listOf(Segno(0, 2, 1)), Ricco.leggi("0,2,1", 5))
        // B sopra un pezzo rosso non perde il rosso; «Clear style» toglie tutto
        assertEquals(rosso, Ricco.aspetti(t.length, Ricco.cambia(t.length, s, 2, 8, Ricco.GRASSETTO))[3].colore)
        assertEquals(emptyList<Segno>(), Ricco.pulisci(t.length, s, 0, t.length))
    }

    @Test fun scrivendoDentroUnPezzoLeLettereNuoveSonoComeLui() {
        val rosso = 0xFFE23B3B.toInt()
        val s = listOf(Segno(2, 8, Ricco.GRASSETTO, rosso))
        // «I jumped.» → «I jumpedd.»: la d nuova è rossa
        assertEquals(listOf(Segno(2, 9, Ricco.GRASSETTO, rosso)), Ricco.dopoScrittura("I jumped.", "I jumpedd.", s))
        // cancellare «ump» accorcia il pezzo
        assertEquals(listOf(Segno(2, 5, Ricco.GRASSETTO, rosso)), Ricco.dopoScrittura("I jumped.", "I jed.", s))
        // scrivere prima del pezzo lo sposta e non lo colora
        assertEquals(listOf(Segno(3, 9, Ricco.GRASSETTO, rosso)), Ricco.dopoScrittura("I jumped.", "XI jumped.", s))
        // la tastiera sostituisce tutta la parola (correzione): resta rossa
        assertEquals(listOf(Segno(2, 7, Ricco.GRASSETTO, rosso)), Ricco.dopoScrittura("I jumped.", "I leapt.", s))
    }

    @Test fun laParolaSottoIlDito() {
        val t = "I jumped. = Io"
        assertEquals(2 until 8, Ricco.parola(t, 4))
        assertEquals(2 until 8, Ricco.parola(t, 8))   // sul punto subito dopo: la parola prima
        assertEquals(10 until 11, Ricco.parola(t, 10)) // un segno da solo: solo lui
    }

    // ------------------------------------------------------------ tabelle

    private fun t3() = Tabelle.nuova(1, 3, 3, 900f, 300f, 23f, Elemento.NERO, Elemento.NERO, 1.3f)

    @Test fun unireUnaRigaInteraEDividerla() {
        var t = t3()
        t = Tabelle.unisci(t, 0, 0); t = Tabelle.unisci(t, 0, 0)    // «Important Grammar Notes»: tutta la riga
        assertEquals(3, Tabelle.larga(t, 0, 0))
        assertTrue(Tabelle.coperta(t, 0, 2))
        assertEquals(0 to 0, Tabelle.dove(t, 850f, 50f))            // il tocco a destra cade sulla cella unita
        assertEquals(1 to 2, Tabelle.dove(t, 850f, 150f))
        assertEquals(t, Tabelle.unisci(t, 0, 0))                     // al bordo non si va oltre
        assertEquals(1, Tabelle.larga(Tabelle.dividi(t, 0, 0), 0, 0))
    }

    @Test fun righeEColonneSiAggiungonoETolgono() {
        var t = Tabelle.unisci(t3(), 0, 0)                           // riga 0: celle 0-1 unite
        t = Tabelle.aggiungiRiga(t, 0)
        assertEquals(4, Tabelle.nr(t)); assertEquals(400f, t.h, eps)
        assertEquals(1f, t.righe.sum(), eps)
        assertEquals(1, Tabelle.larga(t, 1, 0))                      // la riga nuova non è unita
        t = Tabelle.aggiungiColonna(t, 0)                            // dentro la cella unita: si allarga
        assertEquals(4, Tabelle.nc(t)); assertEquals(3, Tabelle.larga(t, 0, 0))
        assertEquals(900f, t.w, eps)
        t = Tabelle.togliColonna(t, 1)
        assertEquals(2, Tabelle.larga(t, 0, 0))
        t = Tabelle.togliRiga(t, 1)
        assertEquals(3, Tabelle.nr(t)); assertEquals(300f, t.h, eps)
        assertEquals(t.righe.size * t.colonne.size, t.celle.size)
    }

    @Test fun iBordiSiTrascinanoMaMaiSottoIlMinimo() {
        val t = t3()
        val a = Tabelle.spostaColonna(t, 0, 200f)
        assertEquals(200f, Tabelle.xs(a)[1], eps); assertEquals(600f, Tabelle.xs(a)[2], eps) // la terza non si muove
        assertEquals(Tabelle.MINIMO, Tabelle.xs(Tabelle.spostaColonna(t, 0, -50f))[1], eps)
        val b = Tabelle.spostaRiga(t, 0, 150f)                       // la prima riga da 100 a 150: la tabella cresce
        assertEquals(350f, b.h, eps); assertEquals(150f, Tabelle.ys(b)[1], eps); assertEquals(250f, Tabelle.ys(b)[2], eps)
    }

    @Test fun unaTabellaSiSalvaESiRilegge() {
        var t = Tabelle.unisci(t3(), 0, 0)
        t = Tabelle.conCella(t, 1, 1, Cella("I jumped.\n= Io ho saltato.\tok", listOf(Segno(2, 8, Ricco.SOTTOLINEATO)), 0xFFD8D8D8.toInt(), 1, Elemento.SINISTRA))
        val g = Grafica("x", Formato.A5, elementi = listOf(t.copy(pagina = 0)))
        assertEquals(g, Grafiche.leggi(Grafiche.scrivi(g)))
        // rotta: la riga si salta, il resto resta
        assertNull(Tabelle.leggi("0.5,0.5\u001E1\u001Esolo una cella"))
    }
}
