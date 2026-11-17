package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'editor video nuovo: velocita', volume, dissolvenze, musica, testi, salvataggio. */
class VideoTest {
    private fun c(n: String, d: Long = 10_000) = Clip("content://x/$n", n, d)
    private fun m(pos: Long, lun: Long, dur: Long = 60_000) = Musica("content://m", "song", dur, 0, lun, pos)
    private val eps = 1e-3f

    @Test fun laVelocitaCambiaIlTempoNelVideo() {
        val l = listOf(c("a").copy(velocita = 2f), c("b").copy(velocita = 0.5f))
        assertEquals(5_000L, l[0].vistaMs)
        assertEquals(25_000L, Progetto.totaleMs(l))
        assertEquals(5_000L, Progetto.inizioGlobale(l, 1))
        // 1 s nel video della clip a 2x = 2 s nel file
        assertEquals(0 to 2_000L, Progetto.dove(l, 1_000))
        assertEquals(1 to 1_000L, Progetto.dove(l, 7_000))
    }

    @Test fun dividereTieneLeDissolvenzeAiBordiGiusti() {
        val l = listOf(c("a").copy(entra = true, esce = true, volume = 50))
        val d = Progetto.dividi(l, 0, 5_000)!!
        assertTrue(d[0].entra); assertTrue(!d[0].esce)
        assertTrue(!d[1].entra); assertTrue(d[1].esce)
        assertEquals(50, d[1].volume)
    }

    @Test fun dissolvenzaSaleEScende() {
        assertEquals(0f, Progetto.dissolvenza(0, 10_000, true, false), eps)
        assertEquals(0.5f, Progetto.dissolvenza(250, 10_000, true, false), eps)
        assertEquals(1f, Progetto.dissolvenza(5_000, 10_000, true, true), eps)
        assertEquals(0.5f, Progetto.dissolvenza(9_750, 10_000, false, true), eps)
        assertEquals(1f, Progetto.dissolvenza(0, 10_000, false, false), eps)
        // pezzo cortissimo: la dissolvenza non supera meta'
        assertEquals(1f, Progetto.dissolvenza(200, 400, true, true), eps)
    }

    @Test fun laMusicaNonAbbassaLeClip() {
        // la 0.5.0 abbassava le voci sotto la musica da sola: tolto nella 0.5.1
        val bz = Bozza("x", listOf(c("a").copy(volume = 200)), listOf(m(2_000, 3_000)))
        assertEquals(2f, Progetto.guadagnoClip(bz, 0, 3_000), eps)
    }

    @Test fun unaBozzaDella050SiLeggeAncora() {
        val vecchia = "151art 3\tProva\nM\tcontent://m\tsong\t60000\t0\t60000\t1000\t80\t1\t0\t1\t30\n"
        val bz = Progetto.leggi(vecchia)!!
        assertEquals(1, bz.musica.size)
        assertEquals(80, bz.musica[0].volume)
        assertEquals(1_000L, bz.musica[0].posMs)
        assertEquals(bz, Progetto.leggi(Progetto.scrivi(bz)))
    }

    @Test fun laMusicaNuovaNonSiSovrapponeEStaNelPosto() {
        val una = Progetto.mettiMusica(emptyList(), m(0, 60_000), 1_000)!!
        assertEquals(1_000L, una[0].posMs)
        assertNull(Progetto.mettiMusica(una, m(0, 60_000), 5_000)) // ci cade sopra
        val prima = Progetto.mettiMusica(listOf(m(10_000, 5_000)), m(0, 60_000), 2_000)!!
        assertEquals(2_000L, prima[0].posMs)
        assertEquals(10_000L, prima[0].fineGlobaleMs) // accorciata fino alla seguente
    }

    @Test fun spostareEBordiRestanoNellaCorsia() {
        val l = listOf(m(0, 4_000), m(10_000, 4_000))
        assertEquals(6_000L, Progetto.spostaMusica(l, 0, 50_000)[0].posMs) // si ferma contro la seconda
        assertEquals(4_000L, Progetto.spostaMusica(l, 1, 0)[1].posMs)
        val bordo = Progetto.bordoMusica(l, 0, false, 99_000)
        assertEquals(10_000L, bordo[0].fineGlobaleMs)
        val sx = Progetto.bordoMusica(listOf(m(5_000, 4_000).copy(inizioMs = 1_000, fineMs = 5_000)), 0, true, -3_000)
        assertEquals(0L, sx[0].inizioMs); assertEquals(4_000L, sx[0].posMs) // non prima dell'inizio del file
    }

    @Test fun dividereLaMusica() {
        val d = Progetto.dividiMusica(listOf(m(1_000, 6_000).copy(entra = true, esce = true)), 4_000)!!
        assertEquals(2, d.size)
        assertEquals(3_000L, d[0].lunghezzaMs); assertEquals(4_000L, d[1].posMs); assertEquals(3_000L, d[1].inizioMs)
        assertTrue(d[0].entra && !d[0].esce && !d[1].entra && d[1].esce)
        assertNull(Progetto.dividiMusica(d, 50_000))
    }

    @Test fun testiSovrappostiSuRigheDiverseEVisibiliAlMomentoGiusto() {
        val s = Progetto.mettiScritta(emptyList(), Testo(1, "ciao"), 1_000, 3_000)
        // un secondo testo nello stesso momento: si può e va sulla riga sotto
        val due = Progetto.mettiScritta(s, Testo(2, "anche"), 2_000, 3_000)
        assertEquals(listOf(1L, 2L), Progetto.scritteA(due, 2_500).map { it.testo.id })
        assertEquals(listOf(0, 1), Progetto.righeScritte(due).toList())
        // uno dopo che non tocca nessuno torna sulla prima riga
        val tre = Progetto.mettiScritta(due, Testo(3, "dopo"), 6_000, 1_000)
        assertEquals(listOf(0, 1, 0), Progetto.righeScritte(tre).toList())
        assertEquals(0L, Progetto.spostaScritta(due, 1, -5_000).first { it.testo.id == 2L }.inizioMs) // non prima dello zero
        val b = Progetto.bordoScritta(due, 0, false, 5_000)
        assertEquals(9_000L, b[0].fineMs) // il bordo passa sopra l'altro testo
        assertEquals(Progetto.MINIMO_MS, Progetto.bordoScritta(due, 0, false, -99_000)[0].let { it.fineMs - it.inizioMs })
    }

    @Test fun bozzaCompletaSalvataERiletta() {
        val bz = Bozza(
            "Camp", listOf(c("a").copy(volume = 150, velocita = 2f, entra = true), Progetto.taglia(c("b"), 1_000, 4_000)),
            listOf(m(500, 3_000).copy(nome = "Song\tone", volume = 80, esce = true)),
            listOf(Scritta(Testo(9, "Hi\nthere", 0.3f, 0.2f, 0.1f, 0xFFFF0000.toInt(), "pacifico", Testo.RIQUADRO), 0, 2_000)),
        )
        val back = Progetto.leggi(Progetto.scrivi(bz))!!
        assertEquals(bz.clip, back.clip)
        assertEquals(bz.scritte, back.scritte)
        assertEquals("Song one", back.musica[0].nome)
        assertEquals(bz.musica[0].copy(nome = "Song one"), back.musica[0])
    }

    @Test fun velocitaStraneDiventanoQuellaVicina() {
        assertEquals(30f, Progetto.vicina(99f), eps)
        assertEquals(0.1f, Progetto.vicina(0.01f), eps)
        assertEquals(1.35f, Progetto.vicina(1.33f), eps)
        assertEquals(12.5f, Progetto.vicina(12.4f), eps)
        assertEquals(1f, Progetto.vicina(Float.NaN), eps)
        assertEquals("1.5×", Progetto.velocitaScritta(1.5f))
        assertEquals("30×", Progetto.velocitaScritta(30f))
        assertEquals("0.25×", Progetto.velocitaScritta(0.25f))
        assertEquals("1.35×", Progetto.velocitaScritta(Progetto.vicina(1.35f)))
    }

    @Test fun ilCursoreDellaVelocitaHaUnoAlCentro() {
        assertEquals(0f, Progetto.posizione(1f), eps)
        assertEquals(-1f, Progetto.posizione(0.25f), eps)
        assertEquals(1f, Progetto.posizione(4f), eps)
        assertEquals(1f, Progetto.posizione(30f), eps) // oltre il cursore: in fondo
        assertEquals(2f, Progetto.daPosizione(Progetto.posizione(2f)), eps)
        assertEquals(1.05f, Progetto.passoVelocita(1f, +1), eps)
        assertEquals(2.5f, Progetto.passoVelocita(2f, +1), eps)
        assertEquals(1.95f, Progetto.passoVelocita(2f, -1), eps)
    }

    @Test fun iCursoriSiAttaccanoAlleCalamite() {
        // vicino a un punto: si attacca; lontano: resta dov'è
        assertEquals(1f, Progetto.calamita(1.03f, Progetto.CALAMITE_VELOCITA, Progetto.CALAMITA_VELOCITA), eps)
        assertEquals(1.1f, Progetto.calamita(1.1f, Progetto.CALAMITE_VELOCITA, Progetto.CALAMITA_VELOCITA), eps)
        assertEquals(100f, Progetto.calamita(97f, Progetto.CALAMITE_VOLUME, Progetto.CALAMITA_VOLUME), eps)
        assertEquals(90f, Progetto.calamita(90f, Progetto.CALAMITE_VOLUME, Progetto.CALAMITA_VOLUME), eps)
        assertEquals(200f, Progetto.CALAMITE_VOLUME.last(), eps)
    }

    @Test fun unaVelocitaLiberaSiSalvaESiRilegge() {
        val bz = Bozza("x", listOf(Clip("content://v", "v.mp4", 10_000, velocita = 1.35f)))
        assertEquals(1.35f, Progetto.leggi(Progetto.scrivi(bz))!!.clip[0].velocita, eps)
    }

    @Test fun leTransizioniStannoFraDueClipEScuriscono() {
        val due = listOf(Clip("a", "a", 4_000), Clip("b", "b", 4_000))
        val t = Progetto.conTransizione(due, 1, true)
        assertTrue(t[0].esce && t[1].entra && !t[0].entra && !t[1].esce)
        assertTrue(Progetto.transizione(t, 1)); assertFalse(Progetto.transizione(t, 0)); assertFalse(Progetto.transizione(t, 2))
        // a metà della prima clip niente nero; sul taglio tutto nero; 250 ms dopo, metà
        assertEquals(0f, Progetto.nero(t, 2_000), eps)
        assertEquals(1f, Progetto.nero(t, 4_000), eps)
        assertEquals(0.5f, Progetto.nero(t, 4_250), eps)
        assertEquals(0.5f, Progetto.nero(t, 3_750), eps)
        assertEquals(due, Progetto.conTransizione(t, 1, false))
        assertTrue(Progetto.transizione(Progetto.conTransizione(due, 0, true), 0))
    }

    @Test fun iTempiHannoICentesimi() {
        assertEquals("0:01.40", Progetto.tempo(1_400))
        assertEquals("0:01.47", Progetto.tempo(1_479))
        assertEquals("1.40 s", Progetto.secondi(1_400))
    }

    @Test fun laRigaSceltaEIlLivello() {
        val a = Scritta(Testo(1, "a"), 0, 5_000)
        val b = Scritta(Testo(2, "b"), 1_000, 4_000, riga = 2)
        assertEquals(listOf(0, 2), Progetto.righeScritte(listOf(a, b)).toList())
        // davanti (disegnata per ultima) la riga 0
        assertEquals(listOf(2L, 1L), Progetto.scritteDaDisegnare(listOf(a, b), 2_000).map { it.testo.id })
        // due che vogliono la stessa riga nello stesso tempo: il secondo scende
        val c = Scritta(Testo(3, "c"), 2_000, 3_000, riga = 2)
        assertEquals(listOf(0, 2, 1), Progetto.righeScritte(listOf(a, b, c)).toList())
        // la riga si salva; un testo in riga 0 resta a 11 campi
        val bz = Bozza("x", emptyList(), scritte = listOf(a, b))
        assertEquals(listOf(a, b), Progetto.leggi(Progetto.scrivi(bz))!!.scritte)
    }

    @Test fun laTransizioneHaLaSuaDurata() {
        val due = Progetto.conTransizione(listOf(Clip("a", "a", 4_000), Clip("b", "b", 4_000)), 1, true, 1_000)
        assertEquals(1_000L, Progetto.durataTransizione(due, 1))
        assertEquals(0.5f, Progetto.nero(due, 3_500), eps)
        val letta = Progetto.leggi(Progetto.scrivi(Bozza("x", due)))!!.clip
        assertEquals(due, letta)
        // le bozze di prima (righe C a 10 campi) prendono la durata normale
        val vecchia = "151art 3\tx\nC\tcontent://v\tv.mp4\t4000\t0\t4000\t100\t1.0\t1\t0\n"
        assertEquals(DISSOLVENZA_STD, Progetto.leggi(vecchia)!!.clip[0].durEntra)
    }

    @Test fun trascinareSuEGiuScambiaILivelli() {
        val a = Scritta(Testo(1, "a"), 0, 5_000)
        val b = Scritta(Testo(2, "b"), 1_000, 4_000)
        val due = listOf(a, b)
        assertEquals(listOf(0, 1), Progetto.righeScritte(due).toList())
        val su = Progetto.cambiaRiga(due, 1, 0) // b in cima: a scende
        assertEquals(listOf(1, 0), Progetto.righeScritte(su).toList())
        assertEquals(listOf(1L, 2L), Progetto.scritteDaDisegnare(su, 2_000).map { it.testo.id }) // b davanti
        assertEquals(due, Progetto.cambiaRiga(due, 0, 0))
    }
}
