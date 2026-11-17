package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La penna comune (Mano) e il suo posto in ognuno dei tre editor. Dati finti, fatti al volo. */
class ManoTest {
    private val eps = 1e-3f
    private fun tratto(vararg p: Float, colore: Int = 0xFFFF0000.toInt(), spessore: Float = 0.012f, tipo: Int = Mano.PENNA) =
        Tratto(p, colore, spessore, tipo)

    // ------------------------------------------------------------ il tratto

    @Test fun dueTrattiUgualiSonoUguali() {
        assertEquals(tratto(0.1f, 0.2f, 0.3f, 0.4f), tratto(0.1f, 0.2f, 0.3f, 0.4f))
        assertFalse(tratto(0.1f, 0.2f) == tratto(0.1f, 0.3f))
        assertFalse(tratto(0.1f, 0.2f) == tratto(0.1f, 0.2f, tipo = Mano.EVIDENZIATORE))
    }

    @Test fun lEvidenziatoreELargo() {
        assertEquals(0.012f, Mano.larghezza(tratto(0f, 0f)), eps)
        assertEquals(0.012f * Mano.LARGO_EVIDENZIATORE, Mano.larghezza(tratto(0f, 0f, tipo = Mano.EVIDENZIATORE)), eps)
    }

    @Test fun unPuntoTroppoVicinoNonServe() {
        assertFalse(Mano.lontano(0.5f, 0.5f, 0.5005f, 0.5f, 2f, 1000f, 1000f))
        assertTrue(Mano.lontano(0.5f, 0.5f, 0.505f, 0.5f, 2f, 1000f, 1000f))
    }

    // ------------------------------------------------------------ salvataggio

    @Test fun scrivereELeggereDannoLoStesso() {
        val t = listOf(tratto(0.1234f, 0.5f, 0.9f, 0.25f), tratto(0.5f, 0.5f, tipo = Mano.EVIDENZIATORE, colore = 0xFF00FF00.toInt(), spessore = 0.045f))
        val s = Mano.scriviTutti(t)
        assertFalse(s.contains('\t')); assertFalse(s.contains('\n'))
        assertEquals(t, Mano.leggiTutti(s))
    }

    @Test fun laLinguaDelTelefonoNonCambiaIlFile() {
        val prima = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ITALY)
            val t = tratto(0.25f, 0.75f, spessore = 0.024f)
            assertEquals(t, Mano.leggi(Mano.scrivi(t)))
        } finally { java.util.Locale.setDefault(prima) }
    }

    @Test fun unTrattoRottoSiSaltaGliAltriRestano() {
        val buono = tratto(0.1f, 0.1f, 0.2f, 0.2f)
        val s = "rotto|" + Mano.scrivi(buono) + "|0;1;0.01;1,2,3|9;1;0.01;1,2|0;1;-1;1,2|"
        assertEquals(listOf(buono), Mano.leggiTutti(s))
        assertEquals(emptyList<Tratto>(), Mano.leggiTutti(""))
    }

    // ------------------------------------------------------------ la gomma

    @Test fun laGommaToglieSoloQuelloCheTocca() {
        val a = tratto(0.1f, 0.1f, 0.9f, 0.1f)   // in alto
        val b = tratto(0.1f, 0.9f, 0.9f, 0.9f)   // in basso
        // la gomma (10 px) sfiora il tratto in alto: 10 px dalla linea, punta compresa ci arriva
        val dopo = Mano.cancella(listOf(a, b), 0.5f, 0.11f, 0.5f, 0.11f, 10f, 1000f, 1000f)
        assertEquals(listOf(b), dopo)
    }

    @Test fun laGommaVeloceNonSaltaUnTrattoSottile() {
        val sottile = tratto(0.5f, 0f, 0.5f, 1f, spessore = 0.001f)
        // il dito va da sinistra a destra in un colpo solo, scavalcando il tratto
        val dopo = Mano.cancella(listOf(sottile), 0.1f, 0.5f, 0.9f, 0.5f, 10f, 1000f, 1000f)
        assertTrue(dopo.isEmpty())
    }

    @Test fun laGommaLontanaNonCambiaNiente() {
        val t = listOf(tratto(0.1f, 0.1f, 0.2f, 0.1f))
        val dopo = Mano.cancella(t, 0.8f, 0.8f, 0.9f, 0.9f, 10f, 1000f, 1000f)
        assertTrue(dopo === t)
    }

    @Test fun unPuntoSoloSiPrende() {
        assertTrue(Mano.tocca(tratto(0.5f, 0.5f), 0.505f, 0.5f, 10f, 1000f, 1000f))
        assertFalse(Mano.tocca(tratto(0.5f, 0.5f), 0.6f, 0.5f, 10f, 1000f, 1000f))
    }

    // ------------------------------------------------------------ girare, specchiare, riquadri

    @Test fun quattroGiriTornanoComePrima() {
        val t = listOf(tratto(0.1f, 0.2f, 0.7f, 0.4f))
        var g = t
        repeat(4) { g = Mano.gira(g) }
        assertEquals(t[0].punti.toList().map { Math.round(it * 1000) }, g[0].punti.toList().map { Math.round(it * 1000) })
        val uno = Mano.gira(t)[0]
        assertEquals(0.8f, uno.punti[0], eps); assertEquals(0.1f, uno.punti[1], eps)
    }

    @Test fun specchiareDueVolteTornaComePrima() {
        val t = listOf(tratto(0.1f, 0.2f, 0.7f, 0.4f))
        assertEquals(0.9f, Mano.specchia(t)[0].punti[0], eps)
        val due = Mano.specchia(Mano.specchia(t))[0]
        for (k in 0 until 4) assertEquals(t[0].punti[k], due.punti[k], 1e-6f)
    }

    @Test fun dentroEFuoriSonoIlContrario() {
        val t = listOf(tratto(0.3f, 0.4f, 0.5f, 0.6f, spessore = 0.02f))
        val d = Mano.dentro(t, 0.2f, 0.3f, 0.6f, 0.7f, 1080f, 1920f)
        assertEquals(0.25f, d[0].punti[0], eps); assertEquals(0.25f, d[0].punti[1], eps)
        val f = Mano.fuori(d, 0.2f, 0.3f, 0.6f, 0.7f, 1080f, 1920f)
        for (k in 0 until 4) assertEquals(t[0].punti[k], f[0].punti[k], eps)
        assertEquals(0.02f, f[0].spessore, 1e-5f)
    }

    @Test fun lIngombroComprendeLaPunta() {
        val b = Mano.ingombro(listOf(tratto(0.5f, 0.5f, spessore = 0.1f)), 1000f, 1000f)!!
        assertEquals(0.45f, b[0], eps); assertEquals(0.55f, b[2], eps)
        assertNull(Mano.ingombro(emptyList(), 1f, 1f))
    }

    // ------------------------------------------------------------ nella FOTO

    @Test fun fotoIlDisegnoSiSalvaESiRilegge() {
        val m = Modifiche(tratti = listOf(tratto(0.1f, 0.2f, 0.3f, 0.4f)), testi = listOf(Testo(1, "ciao")))
        assertEquals(m, Foto.leggi(Foto.scrivi(m)))
    }

    @Test fun fotoUnaBozzaDiPrimaSiLeggeSenzaDisegno() {
        val vecchia = "151foto 1\n0\t0\t0.0\t0.0\t0.0\t1.0\t1.0\tfree\n0\t0\t0\t0\t0\t0\t\t100\n"
        assertEquals(Modifiche(), Foto.leggi(vecchia))
    }

    @Test fun fotoIlDisegnoGiraESiSpecchiaConLaFoto() {
        val m = Modifiche(tratti = listOf(tratto(0.1f, 0.2f)), testi = listOf(Testo(1, "x", x = 0.1f, y = 0.2f)))
        val g = Foto.gira(m, 400, 300)
        // il disegno va dove va il testo che stava nello stesso punto
        assertEquals(g.testi[0].x, g.tratti[0].punti[0], eps); assertEquals(g.testi[0].y, g.tratti[0].punti[1], eps)
        val s = Foto.specchia(m)
        assertEquals(s.testi[0].x, s.tratti[0].punti[0], eps)
    }

    // ------------------------------------------------------------ nel DESIGN

    @Test fun designIlDisegnoSiStringeAttornoAllInchiostro() {
        val g = Grafica("x", Formato.POST)
        val t = listOf(tratto(0.25f, 0.25f, 0.75f, 0.5f, spessore = 0.01f))
        val (n, id) = Grafiche.disegno(g, null, t)
        val e = n.trova(id)!!
        assertEquals(Elemento.DISEGNO, e.tipo)
        assertEquals(270f - 5.4f, e.x, 0.1f); assertEquals(810f + 5.4f, e.x + e.w, 0.1f)
        // e tornando al foglio i tratti sono quelli dati
        val indietro = Grafiche.trattiNelFoglio(n, e)
        for (k in 0 until 4) assertEquals(t[0].punti[k], indietro[0].punti[k], eps)
        assertEquals(0.01f, indietro[0].spessore, 1e-5f)
    }

    @Test fun designCancellareTuttoTogliIlDisegno() {
        val (n, id) = Grafiche.disegno(Grafica("x", Formato.A4), null, listOf(tratto(0.5f, 0.5f)))
        val (vuoto, nid) = Grafiche.disegno(n, id, emptyList())
        assertNull(nid); assertTrue(vuoto.elementi.isEmpty())
    }

    @Test fun designUnPuntinoHaLaScatolaMinima() {
        val (n, id) = Grafiche.disegno(Grafica("x", Formato.A4), null, listOf(tratto(0.5f, 0.5f, spessore = 0.001f)))
        val e = n.trova(id)!!
        assertTrue(e.w >= Elemento.MIN - eps && e.h >= Elemento.MIN - eps)
    }

    @Test fun designIlDisegnoSiPrendeSullInchiostroNonSullaScatola() {
        // una «L»: l'angolo in basso a destra della scatola è vuoto
        val (n, id) = Grafiche.disegno(Grafica("x", Formato.POST), null, listOf(tratto(0.2f, 0.2f, 0.2f, 0.8f, 0.8f, 0.8f)))
        assertEquals(id, Grafiche.tocca(n, 0.2f * 1080f, 0.5f * 1080f, 10f))
        assertNull(Grafiche.tocca(n, 0.7f * 1080f, 0.3f * 1080f, 10f))
    }

    @Test fun designIlDisegnoSiSalvaESiRilegge() {
        val (n, _) = Grafiche.disegno(Grafica("x", Formato.STORIA), null, listOf(tratto(0.1f, 0.1f, 0.4f, 0.3f)))
        val letto = Grafiche.leggi(Grafiche.scrivi(n))
        assertNotNull(letto)
        assertEquals(n.elementi.size, letto!!.elementi.size)
        val a = n.elementi[0]; val b = letto.elementi[0]
        assertEquals(Elemento.DISEGNO, b.tipo)
        assertEquals(a.tratti.size, b.tratti.size)
        for (k in a.tratti[0].punti.indices) assertEquals(a.tratti[0].punti[k], b.tratti[0].punti[k], 2e-4f)
    }

    @Test fun designLeRigheDegliAltriOggettiRestanoA24Campi() {
        val g = Grafiche.aggiungi(Grafica("x", Formato.A4), Elemento(0, Elemento.FORMA, 0f, 0f, 100f, 100f))
        val riga = Grafiche.scrivi(g).split('\n')[1]
        assertEquals(24, riga.split('\t').size)
    }

    // ------------------------------------------------------------ nel VIDEO

    @Test fun videoIlDisegnoSiSalvaESiRilegge() {
        val disegno = Scritta(Testo(5, ""), 1000, 4000, listOf(tratto(0.1f, 0.2f, 0.3f, 0.4f)))
        val testo = Scritta(Testo(6, "ciao"), 5000, 8000)
        val b = Bozza("p", listOf(Clip("content://x", "x.mp4", 10_000)), scritte = listOf(disegno, testo))
        val letta = Progetto.leggi(Progetto.scrivi(b))!!
        assertEquals(b.scritte, letta.scritte)
        assertTrue(letta.scritte[0].disegno); assertFalse(letta.scritte[1].disegno)
    }

    @Test fun videoIRigheDeiTestiRestanoA11Campi() {
        val b = Bozza("p", emptyList(), scritte = listOf(Scritta(Testo(6, "ciao"), 0, 3000)))
        val riga = Progetto.scrivi(b).split('\n').first { it.startsWith("S\t") }
        assertEquals(11, riga.split('\t').size)
    }
}
