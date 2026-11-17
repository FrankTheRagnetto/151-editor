package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgettoTest {
    private fun c(n: String, d: Long = 10_000) = Clip("content://x/$n", n, d)

    @Test fun spostaPortaLaClipAlPostoNuovo() {
        val l = listOf(c("a"), c("b"), c("c"))
        assertEquals(listOf("b", "c", "a"), Progetto.sposta(l, 0, 2).map { it.nome })
        assertEquals(listOf("c", "a", "b"), Progetto.sposta(l, 2, 0).map { it.nome })
    }

    @Test fun spostaFuoriElencoNonCambiaNiente() {
        val l = listOf(c("a"), c("b"))
        assertEquals(l, Progetto.sposta(l, -1, 1))
        assertEquals(l, Progetto.sposta(l, 0, 5))
    }

    @Test fun tagliaRestaDentroIlFile() {
        val t = Progetto.taglia(c("a"), -500, 99_000)
        assertEquals(0L, t.inizioMs); assertEquals(10_000L, t.fineMs)
    }

    @Test fun tagliaGiraGliEstremiAlRovescio() {
        val t = Progetto.taglia(c("a"), 8_000, 2_000)
        assertEquals(2_000L, t.inizioMs); assertEquals(8_000L, t.fineMs)
    }

    @Test fun tagliaNonScendeSottoIlMinimo() {
        val t = Progetto.taglia(c("a"), 5_000, 5_050)
        assertEquals(Progetto.MINIMO_MS, t.lunghezzaMs)
        val fondo = Progetto.taglia(c("a"), 9_990, 10_000)
        assertEquals(10_000L, fondo.fineMs)
        assertEquals(Progetto.MINIMO_MS, fondo.lunghezzaMs)
    }

    @Test fun tagliaFileCortissimoTieneTutto() {
        val t = Progetto.taglia(c("a", 200), 50, 60)
        assertEquals(0L, t.inizioMs); assertEquals(200L, t.fineMs)
    }

    @Test fun dividiFaDueMetaCheSiToccano() {
        val l = listOf(c("a"), Progetto.taglia(c("b"), 1_000, 9_000), c("c"))
        val d = Progetto.dividi(l, 1, 4_000)!!
        assertEquals(4, d.size)
        assertEquals(1_000L, d[1].inizioMs); assertEquals(4_000L, d[1].fineMs)
        assertEquals(4_000L, d[2].inizioMs); assertEquals(9_000L, d[2].fineMs)
        assertEquals("c", d[3].nome)
        assertEquals(Progetto.totaleMs(l), Progetto.totaleMs(d)) // niente si perde
    }

    @Test fun dividiTroppoVicinoAlBordoNo() {
        val l = listOf(c("a"))
        assertNull(Progetto.dividi(l, 0, 100))
        assertNull(Progetto.dividi(l, 0, 9_900))
        assertNull(Progetto.dividi(l, 3, 5_000))
    }

    @Test fun doveTrovaLaClipEIlTempoNelFile() {
        val l = listOf(Progetto.taglia(c("a"), 2_000, 5_000), Progetto.taglia(c("b"), 1_000, 3_000))
        assertEquals(0 to 2_000L, Progetto.dove(l, 0))
        assertEquals(0 to 4_999L, Progetto.dove(l, 2_999))
        assertEquals(1 to 1_000L, Progetto.dove(l, 3_000))
        assertEquals(1 to 3_000L, Progetto.dove(l, 99_000))
        assertNull(Progetto.dove(emptyList(), 0))
        assertEquals(3_000L, Progetto.inizioGlobale(l, 1))
    }

    @Test fun totaleSommaIPezziTagliati() {
        val l = listOf(Progetto.taglia(c("a"), 1_000, 3_000), c("b", 4_500))
        assertEquals(6_500L, Progetto.totaleMs(l))
    }

    @Test fun tempoScrittoPerUnaPersona() {
        assertEquals("0:04.85", Progetto.tempo(4_850))
        assertEquals("2:05.00", Progetto.tempo(125_000))
        assertEquals("1:02:03", Progetto.tempo(3_723_000))
        assertEquals("0:00.00", Progetto.tempo(-5))
    }

    @Test fun misuraEQuellaDellaPrimaClip() {
        assertEquals(1080 to 1920, Progetto.misuraUscita(1080, 1920))
        assertEquals(720 to 1280, Progetto.misuraUscita(720, 1280))
        assertEquals(3840 to 2160, Progetto.misuraUscita(3840, 2160))
        assertEquals(1000 to 1000, Progetto.misuraUscita(1000, 1000))
        assertEquals(722 to 406, Progetto.misuraUscita(721, 405)) // lati pari
        assertEquals(3840 to 2160, Progetto.misuraUscita(7680, 4320)) // 8K → 4K
        assertEquals(1080 to 1920, Progetto.misuraUscita(0, 0))
    }

    @Test fun nomeFile() {
        assertEquals("151 Editor 2026-10-11 16.42.07.mp4", Progetto.nomeFile(2026, 10, 11, 16, 42, 7))
    }

    @Test fun salvaERileggeUguale() {
        val l = listOf(Progetto.taglia(c("a"), 1_000, 3_000), c("b\tstrano\nnome", 4_500))
        val back = Progetto.leggi(Progetto.scrivi(Bozza("Camp\tday 1", l)))!!
        assertEquals("Camp day 1", back.nome)
        assertEquals(2, back.clip.size)
        assertEquals(l[0], back.clip[0])
        assertEquals("b strano nome", back.clip[1].nome)
        assertEquals(4_500L, back.clip[1].fineMs)
    }

    @Test fun leggeAncheIlFormatoDellaPrimaVersione() {
        val v1 = "151art 1\ncontent://a\ta\t1000\t0\t1000\n"
        val b = Progetto.leggi(v1)!!
        assertEquals("", b.nome); assertEquals(1, b.clip.size)
    }

    @Test fun fileRottoNonFaCrash() {
        assertNull(Progetto.leggi(""))
        assertNull(Progetto.leggi("roba a caso"))
        val mezzo = "151art 2\tx\ncontent://a\ta\t1000\t0\t1000\nriga\trotta\n\tvuoto\t1\t0\t1\n"
        assertEquals(1, Progetto.leggi(mezzo)!!.clip.size)
    }

    @Test fun duplicaMetteLaCopiaSubitoDopo() {
        val l = listOf(c("a"), c("b"))
        assertEquals(listOf("a", "a", "b"), Progetto.duplica(l, 0).map { it.nome })
        assertEquals(l, Progetto.duplica(l, 7))
    }

    @Test fun annullaERifai() {
        val s = Storia(listOf(c("a")), tetto = 2)
        assertTrue(!s.puoiAnnullare)
        s.fai(listOf(c("a"), c("b")))
        s.fai(listOf(c("b")))
        assertTrue(s.annulla()); assertEquals(listOf("a", "b"), s.adesso.map { it.nome })
        assertTrue(s.rifai()); assertEquals(listOf("b"), s.adesso.map { it.nome })
        assertTrue(s.annulla())
        s.fai(listOf(c("z"))) // un gesto nuovo butta i «rifai»
        assertTrue(!s.puoiRifare)
        s.fai(listOf(c("y")))
        assertTrue(s.annulla()); assertTrue(s.annulla())
        assertTrue(!s.annulla()) // tetto 2
        s.fai(s.adesso) // uguale = niente
        assertTrue(!s.puoiAnnullare)
    }

    @Test fun unTestoConOutlineSuMisuraNelVideoSiRileggeUguale() {
        val t = Testo(4, "ciao", stile = Testo.CONTORNO, secondo = 0xFF34C759.toInt(), spessore = 0.25f)
        val b = Bozza("Camp", listOf(Clip("content://v/1", "a.mp4", 5000)), scritte = listOf(
            Scritta(t, 0, 2000), Scritta(Testo(5, "semplice"), 1000, 3000, riga = 1)))
        val s = Progetto.scrivi(b)
        assertEquals(b.scritte, Progetto.leggi(s)!!.scritte)
        // il testo semplice resta di 11 campi più la riga: le versioni di prima lo leggono ancora
        assertTrue(s.lines().any { it.startsWith("S\t1000") && it.split('\t').size == 13 })
    }

    @Test fun laCalamitaAttaccaIlBordoPiuVicinoSoloDentroLaSoglia() {
        val bersagli = longArrayOf(0, 5000, 8000)
        // un blocco 1000..3000 spostato di +1950: la fine arriva a 4950, a 50 ms dalla clip → si attacca a 5000
        assertEquals(2000L, Progetto.calamita(longArrayOf(1000, 3000), bersagli, 1950, 100))
        // troppo lontano: libero
        assertNull(Progetto.calamita(longArrayOf(1000, 3000), bersagli, 1500, 100))
        // due bordi vicini a due bersagli: vince il più vicino (l'inizio a 30 ms da 5000, la fine a 80 da 8000)
        assertEquals(-40L, Progetto.calamita(longArrayOf(5040, 7920), bersagli, -10, 100))
        assertNull(Progetto.calamita(longArrayOf(), bersagli, 0, 100))
    }
}
