package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il progetto Audio: corsie, posti liberi, spostamenti, export e bozza su disco. Dati finti. */
class SuonoTest {
    private fun suono(n: String, dur: Long) = Musica("content://prova/$n", n, dur)

    @Test fun ilSecondoSuonoNelloStessoPuntoVaNellaCorsiaSotto() {
        val (a, ia) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        assertEquals(0, a[ia].corsia)
        val (b, ib) = Suono.metti(a, suono("voce", 4_000), 2_000)!!
        assertEquals(1, b[ib].corsia)
        assertEquals(2_000, b[ib].posMs)
        assertEquals(10_000, Suono.totaleMs(b))
        assertEquals(3, Suono.corsieVisibili(b)) // due usate + una vuota
    }

    @Test fun dopoLaFineVaNellaPrimaCorsiaLibera() {
        val (a, _) = Suono.metti(emptyList(), suono("uno", 3_000), 0)!!
        val (b, ib) = Suono.metti(a, suono("due", 3_000), 3_000)!!
        assertEquals(0, b[ib].corsia)
    }

    @Test fun seTutteLeCorsieSonoPieneNonSiMette() {
        var p = emptyList<Musica>()
        repeat(Suono.CORSIE_MAX) { p = Suono.metti(p, suono("s$it", 5_000), 0)!!.first }
        assertNull(Suono.metti(p, suono("troppo", 1_000), 1_000))
    }

    @Test fun spostareInUnaCorsiaLiberaECambiaCorsia() {
        val (a, _) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        val (b, ib) = Suono.metti(a, suono("voce", 2_000), 12_000)!!
        val (c, ic) = Suono.sposta(b, ib, 1_000, 1)
        assertEquals(1, c[ic].corsia); assertEquals(1_000, c[ic].posMs)
        assertEquals("voce", c[ic].nome)
    }

    @Test fun spostareSopraUnAltroNonSovrapponeResta() {
        val (a, _) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        val (b, ib) = Suono.metti(a, suono("voce", 2_000), 1_000)!! // corsia 1
        // corsia 0 occupata dalla musica: resta nella corsia 1
        val (c, ic) = Suono.sposta(b, ib, 3_000, 0)
        assertEquals(1, c[ic].corsia); assertEquals(3_000, c[ic].posMs)
        for (k in 0 until Suono.CORSIE_MAX) {
            val l = Suono.corsia(c, k).sortedBy { it.posMs }
            for (j in 1 until l.size) assertTrue(l[j].posMs >= l[j - 1].fineGlobaleMs)
        }
    }

    @Test fun dividereTieneLaSecondaMetaScelta() {
        val (a, ia) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        val (b, ib) = Suono.dividi(a, ia, 4_000)!!
        assertEquals(2, b.size)
        assertEquals(4_000, b[ib].posMs); assertEquals(4_000, b[ib].inizioMs)
        assertNull(Suono.dividi(a, ia, 100)) // troppo vicino al bordo
        assertNull(Suono.dividi(a, ia, 12_000)) // fuori
    }

    @Test fun duplicareMetteLaCopiaSubitoDopo() {
        val (a, ia) = Suono.metti(emptyList(), suono("colpo", 1_000), 500)!!
        val (b, ib) = Suono.duplica(a, ia)!!
        assertEquals(2, b.size); assertEquals(1_500, b[ib].posMs); assertEquals(0, b[ib].corsia)
    }

    @Test fun ilBordoNonEntraNelVicino() {
        val (a, _) = Suono.metti(emptyList(), suono("uno", 3_000), 0)!!
        // «due» usa il file da 5 s: il bordo sinistro può tornare indietro fino a 5 s, ma «uno» finisce a 3 s
        val due = Musica("content://prova/due", "due", 10_000, 5_000, 8_000, 4_000)
        val b = a + due
        val (c, ic) = Suono.bordo(b, b.indexOf(due), true, -3_000)
        assertEquals(3_000, c[ic].posMs); assertEquals(4_000, c[ic].inizioMs)
    }

    @Test fun leFileDellExportSonoLungheUguali() {
        val (a, _) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        val (b, _) = Suono.metti(a, suono("voce", 2_000), 3_000)!!
        val file = Suono.file(b)
        assertEquals(2, file.size)
        for (f in file) assertEquals(10_000L, f.sumOf { when (it) { is Suono.Passo.Silenzio -> it.ms; is Suono.Passo.Pezzo -> it.m.lunghezzaMs } })
        val voce = file[1]
        assertEquals(Suono.Passo.Silenzio(3_000), voce[0])
        assertTrue(voce[1] is Suono.Passo.Pezzo)
        assertEquals(Suono.Passo.Silenzio(5_000), voce[2])
    }

    @Test fun laPrimaFilaEQuellaCheCominciaPrima() {
        val (a, _) = Suono.metti(emptyList(), suono("tardi", 2_000), 5_000)!!
        val (b, _) = Suono.metti(a, suono("presto", 2_000), 0)!!
        assertTrue(Suono.file(b)[0][0] is Suono.Passo.Pezzo)
    }

    @Test fun laBozzaSiSalvaESiRilegge() {
        val (a, _) = Suono.metti(emptyList(), suono("musica\tcon tab", 10_000).copy(volume = 80, entra = true), 0)!!
        val (b, _) = Suono.metti(a, suono("voce", 2_000).copy(esce = true), 1_000)!!
        val s = ProgettoSuono("Audio 1", b)
        val letto = Suono.leggi(Suono.scrivi(s))
        assertNotNull(letto)
        assertEquals("Audio 1", letto!!.nome)
        assertEquals(b.map { it.copy(nome = it.nome.replace('\t', ' ')) }, letto.pezzi)
        // un video non è un progetto audio, e viceversa
        assertNull(Suono.leggi(Progetto.scrivi(Bozza("v", emptyList()))))
        assertNull(Progetto.leggi(Suono.scrivi(s)))
    }

    @Test fun righeRotteSiSaltano() {
        val t = "151suono 1\tProva\nM\tcontent://x\tx\tnon-un-numero\t0\t1\t0\t100\t0\t0\t0\nM\tcontent://y\ty\t1000\t0\t1000\t0\t100\t0\t0\t9\n"
        val p = Suono.leggi(t)!!
        assertEquals(1, p.pezzi.size)
        assertEquals(Suono.CORSIE_MAX - 1, p.pezzi[0].corsia)
    }

    @Test fun mutaESoloDecidonoQualiCorsieSiSentono() {
        val (a, _) = Suono.metti(emptyList(), suono("musica", 10_000), 0)!!
        val (b, _) = Suono.metti(a, suono("voce", 2_000), 0)!!
        var s = ProgettoSuono("p", b)
        assertEquals(1f, Suono.guadagnoCorsia(s, 0), 0f)
        s = Suono.conCorsia(s, 0) { it.copy(volume = 40) }
        assertEquals(0.4f, Suono.guadagno(s, s.pezzi[0], 5_000), 0.0001f)
        s = Suono.conCorsia(s, 1) { it.copy(solo = true) }
        assertEquals(0f, Suono.guadagnoCorsia(s, 0), 0f) // un'altra è in solo
        assertEquals(1f, Suono.guadagnoCorsia(s, 1), 0f)
        s = Suono.conCorsia(s, 1) { it.copy(solo = false, muto = true) }
        assertEquals(0f, Suono.guadagnoCorsia(s, 1), 0f)
        assertEquals(0.4f, Suono.guadagnoCorsia(s, 0), 0f)
    }

    @Test fun laDissolvenzaHaLaDurataScelta() {
        val m = Suono.conEntra(suono("musica", 10_000), 2_000)
        assertTrue(m.entra); assertEquals(2_000, m.durEntra)
        assertEquals(0.5f, Progetto.guadagnoMusica(m, 1_000), 0.001f) // a metà della dissolvenza di 2 s
        assertEquals(1f, Progetto.guadagnoMusica(m, 2_500), 0.001f)
        assertTrue(!Suono.conEntra(m, 50).entra) // 0 = niente dissolvenza
        assertEquals(Suono.FADE_MAX, Suono.conEsce(m, 9_000).durEsce)
    }

    @Test fun laMusicaDelVideoTieneLaDissolvenzaDiPrima() {
        val m = Musica("content://m", "song", 10_000, entra = true)
        assertEquals(0.5f, Progetto.guadagnoMusica(m, DISSOLVENZA_STD / 2), 0.001f)
    }

    @Test fun evenOutPortaIlSuonoAlLivelloComune() {
        // una voce bassa: picchi 0.2 quasi sempre, un colpo isolato a 0.9
        val onda = FloatArray(500) { 0.2f }.also { it[10] = 0.9f }
        val l = Suono.livello(onda, 0, 10_000)!!
        assertEquals(0.2f, l, 0.0001f) // il colpo isolato non conta
        assertEquals(250, Suono.volumePari(l))
        assertEquals(Progetto.VOLUME_MAX, Suono.volumePari(0.01f)) // mai sopra il massimo
        assertEquals(10, Suono.volumePari(10f)) // mai sotto il 10%
        assertNull(Suono.livello(FloatArray(100), 0, 2_000)) // muto: non si tocca
    }

    @Test fun ilFileSalvatoPrendeIlNomeDelProgetto() {
        assertEquals("Lesson 3 2026-11-04 09.14.07.m4a", Suono.nomeFile("Lesson 3", "151 Editor 2026-11-04 09.14.07.m4a"))
        assertEquals("a b 2026-11-04 09.14.07.m4a", Suono.nomeFile("a/b", "151 Editor 2026-11-04 09.14.07.m4a"))
        assertEquals("151 Editor 2026-11-04 09.14.07.m4a", Suono.nomeFile("  ", "151 Editor 2026-11-04 09.14.07.m4a"))
    }

    @Test fun corsieEDurateSiSalvanoESiRileggono() {
        val (a, _) = Suono.metti(emptyList(), Suono.conEsce(suono("musica", 10_000), 3_000), 0)!!
        val s = Suono.conCorsia(ProgettoSuono("p", a), 2) { it.copy(volume = 70, solo = true) }
        val letto = Suono.leggi(Suono.scrivi(s))!!
        assertEquals(s.pezzi, letto.pezzi)
        assertEquals(Corsia(70, false, true), Suono.corsia(letto, 2))
        assertEquals(Corsia(), Suono.corsia(letto, 0))
        // il formato 1 (0.16.0) si legge ancora
        val vecchio = "151suono 1\tVecchio\nM\tcontent://y\ty\t1000\t0\t1000\t0\t100\t1\t0\t0\n"
        val v = Suono.leggi(vecchio)!!
        assertEquals(DISSOLVENZA_STD, v.pezzi[0].durEntra); assertTrue(v.pezzi[0].entra)
    }

    @Test fun seiCorsieNonScendonoSotto48dpEScorrono() {
        // telefono di prova in orizzontale: ~257 dp di corsie; sei corsie divise = 42,8 dp, troppo poche per un pollice
        val spazio = 257f
        assertEquals(48f, Suono.altezzaCorsia(spazio, 6, 48f), 0.01f)
        assertEquals(6 * 48f - spazio, Suono.scorrimentoMax(spazio, 6, 48f), 0.01f)
        // tre corsie: ci stanno, si dividono lo spazio e non scorrono
        assertEquals(spazio / 3, Suono.altezzaCorsia(spazio, 3, 48f), 0.01f)
        assertEquals(0f, Suono.scorrimentoMax(spazio, 3, 48f), 0.01f)
    }

    @Test fun leScritteDelRighelloNonFinisconoSotteLeManiglieDelLoop() {
        val maniglie = floatArrayOf(100f, 300f)
        assertTrue(!Suono.scrittaLibera(95f, 130f, maniglie, 9f)) // «0:06» sopra la maniglia
        assertTrue(!Suono.scrittaLibera(60f, 95f, maniglie, 9f))  // finisce dentro la maniglia
        assertTrue(Suono.scrittaLibera(110f, 140f, maniglie, 9f)) // subito dopo: si legge
        assertTrue(Suono.scrittaLibera(0f, 30f, floatArrayOf(), 9f)) // Loop spento
    }

    @Test fun laVelocitaAccorciaEAllungaIlSuonoNelProgetto() {
        val (a, ia) = Suono.metti(emptyList(), suono("voce", 8_000), 1_000)!!
        val veloce = Suono.conVelocita(a, ia, 2f)
        assertEquals(4_000, veloce[ia].suonaMs)
        assertEquals(5_000, veloce[ia].fineGlobaleMs)
        assertEquals(8_000, veloce[ia].lunghezzaMs) // il file usato resta intero
        assertEquals(6_000, veloce[ia].nelFile(4_000)) // 3 s dopo il suo inizio nel progetto = 6 s del file
        val lento = Suono.conVelocita(a, ia, 0.5f)
        assertEquals(16_000, lento[ia].suonaMs)
    }

    @Test fun rallentatoSiFermaDoveCominciaIlProssimo() {
        var p = Suono.metti(emptyList(), suono("uno", 4_000), 0)!!.first
        p = Suono.metti(p, suono("due", 4_000), 6_000, 0)!!.first // stessa corsia, 2 s di spazio dopo «uno»
        assertEquals(0, p[1].corsia)
        val r = Suono.conVelocita(p, 0, 0.25f)
        assertTrue(r[0].fineGlobaleMs <= 6_000)
        assertTrue(r[0].velocita < 1f) // rallentato quanto c'è posto: 4 s → al massimo 6 s
        assertEquals(0.7f, r[0].velocita, 0.001f)
    }

    @Test fun dividereUnSuonoVeloceTagliaNelPuntoGiustoDelFile() {
        val (a, ia) = Suono.metti(emptyList(), suono("voce", 8_000), 0)!!
        val v = Suono.conVelocita(a, ia, 2f)
        val (d, _) = Suono.dividi(v, ia, 1_000)!!
        assertEquals(2_000, d[0].fineMs)  // 1 s del progetto a 2× = 2 s del file
        assertEquals(2_000, d[1].inizioMs)
        assertEquals(1_000, d[1].posMs)
        assertEquals(3_000, d[1].suonaMs)
    }

    @Test fun ilBordoDestroDiUnSuonoLentoTagliaIlFileAlRitmoGiusto() {
        val (a, ia) = Suono.metti(emptyList(), suono("voce", 8_000), 0)!!
        val lento = Suono.conVelocita(a, ia, 0.5f) // 16 s nel progetto
        val (b, ib) = Suono.bordo(lento, ia, false, -4_000) // 4 s del progetto in meno = 2 s del file
        assertEquals(6_000, b[ib].fineMs)
        assertEquals(12_000, b[ib].fineGlobaleMs)
    }

    @Test fun ladissolvenzaFinisceDoveFinisceIlSuonoVeloce() {
        val (a, ia) = Suono.metti(emptyList(), suono("voce", 8_000), 0)!!
        val m = Suono.conEsce(Suono.conVelocita(a, ia, 2f)[ia], 1_000)
        val s = ProgettoSuono("x", listOf(m))
        assertEquals(0f, Suono.guadagno(s, m, 4_000), 0.01f) // a 2× finisce a 4 s, muto lì
        assertEquals(1f, Suono.guadagno(s, m, 2_000), 0.01f)
    }

    @Test fun laVelocitaSiSalvaNellaBozzaELeVecchieRestanoA1x() {
        val (a, ia) = Suono.metti(emptyList(), suono("voce", 8_000), 0)!!
        val s = ProgettoSuono("Prova", Suono.conVelocita(a, ia, 1.5f))
        val letto = Suono.leggi(Suono.scrivi(s))!!
        assertEquals(1.5f, letto.pezzi[0].velocita, 0.001f)
        val vecchia = "151suono 2\tVecchia\nM\tcontent://prova/x\tx\t5000\t0\t5000\t0\t100\t0\t0\t0\t500\t500\n"
        assertEquals(1f, Suono.leggi(vecchia)!!.pezzi[0].velocita, 0f)
    }

    // --- «Remove silences»: un'onda finta a passi da 20 ms
    private fun onda(vararg tratti: Pair<Long, Float>): FloatArray {
        val l = mutableListOf<Float>()
        for ((ms, v) in tratti) repeat((ms / Onde.PASSO_MS).toInt()) { l += v }
        return l.toFloatArray()
    }

    @Test fun iSilenziLunghiSiTolgonoEIBuchiSiChiudono() {
        // 2 s di voce, 2 s di silenzio, 3 s di voce: 7 s
        val o = onda(2_000L to 0.5f, 2_000L to 0.001f, 3_000L to 0.5f)
        val voce = suono("voce", 7_000).copy(posMs = 1_000, entra = true, esce = true)
        val dopo = suono("dopo", 2_000).copy(posMs = 9_000)
        val altra = suono("musica", 10_000).copy(corsia = 1)
        val (l, k) = Suono.togliSilenzi(listOf(voce, dopo, altra), 0, o)!!
        val parti = l.filter { it.uri == voce.uri }
        assertEquals(2, parti.size)
        // restano 0,15 s di bordo per lato: 2,15 s + 3,15 s
        assertEquals(0L, parti[0].inizioMs); assertEquals(2_150L, parti[0].fineMs); assertEquals(1_000L, parti[0].posMs)
        assertEquals(3_850L, parti[1].inizioMs); assertEquals(7_000L, parti[1].fineMs); assertEquals(3_150L, parti[1].posMs)
        assertTrue(parti[0].entra && !parti[0].esce && !parti[1].entra && parti[1].esce)
        // il suono dopo nella stessa corsia si sposta indietro di quanto tolto (1,7 s); l'altra corsia no
        assertEquals(9_000L - 1_700L, l.first { it.uri == dopo.uri }.posMs)
        assertEquals(0L, l.first { it.uri == altra.uri }.posMs)
        assertEquals(parti[1], l[k])
    }

    @Test fun unaPausaCortaResta() {
        val o = onda(2_000L to 0.5f, 400L to 0.001f, 2_000L to 0.5f)
        assertNull(Suono.togliSilenzi(listOf(suono("voce", 4_400)), 0, o))
    }

    @Test fun ilSilenzioInTestaEInCodaVaViaTuttoEUnSuonoMutoNo() {
        val o = onda(1_500L to 0.001f, 3_000L to 0.5f, 1_000L to 0.001f)
        val (l, _) = Suono.togliSilenzi(listOf(suono("voce", 5_500)), 0, o)!!
        assertEquals(1, l.size)
        assertEquals(1_350L, l[0].inizioMs); assertEquals(4_650L, l[0].fineMs); assertEquals(0L, l[0].posMs) // 0,15 s di respiro per lato
        assertNull(Suono.togliSilenzi(listOf(suono("muto", 3_000)), 0, onda(3_000L to 0.0f)))
    }

    @Test fun conLaVelocitaIPezziStannoInFilaGiusti() {
        val o = onda(2_000L to 0.5f, 2_000L to 0.001f, 2_000L to 0.5f)
        val (l, _) = Suono.togliSilenzi(listOf(suono("voce", 6_000).copy(velocita = 2f)), 0, o)!!
        assertEquals(l[0].fineGlobaleMs, l[1].posMs)
    }

    // --- un colore per ogni suono
    @Test fun ilColoreSiSalvaERileggeEIVecchiProgettiRestanoSenza() {
        val s = ProgettoSuono("Col", listOf(suono("voce", 3_000).copy(colore = 4), suono("musica", 3_000).copy(posMs = 3_000)))
        val letto = Suono.leggi(Suono.scrivi(s))!!
        assertEquals(4, letto.pezzi.first { it.nome == "voce" }.colore)
        assertEquals(0, letto.pezzi.first { it.nome == "musica" }.colore)
        val vecchia = "151suono 2\tV\nM\tcontent://prova/x\tx\t5000\t0\t5000\t0\t100\t0\t0\t0\t500\t500\t1.0\n"
        assertEquals(0, Suono.leggi(vecchia)!!.pezzi[0].colore)
        val strana = "151suono 2\tV\nM\tcontent://prova/x\tx\t5000\t0\t5000\t0\t100\t0\t0\t0\t500\t500\t1.0\t99\n"
        assertEquals(0, Suono.leggi(strana)!!.pezzi[0].colore)
    }

    @Test fun ogniColoreReggeIlTestoBianco() {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
        }
        for (c in Suono.COLORI) assertTrue("colore %08x".format(c), 1.05 / (lum(c) + 0.05) >= 4.5)
        assertEquals(Suono.COLORI.size, Suono.NOMI_COLORI.size)
    }

    @Test fun togliereISilenziTieneIlColore() {
        val o = onda(2_000L to 0.5f, 2_000L to 0.001f, 2_000L to 0.5f)
        val (l, _) = Suono.togliSilenzi(listOf(suono("voce", 6_000).copy(colore = 2)), 0, o)!!
        assertTrue(l.all { it.colore == 2 })
    }

    // --- segnalini sul tempo
    @Test fun unSegnalinoSiMetteESiTogliePremendoVicino() {
        var k = Suono.segna(emptyList(), 4_000)
        k = Suono.segna(k, 1_000)
        assertEquals(listOf(1_000L, 4_000L), k)
        assertEquals(listOf(1_000L), Suono.segna(k, 4_200)) // a 0,2 s: si toglie
        assertEquals(3, Suono.segna(k, 4_400).size)          // a 0,4 s: uno nuovo
    }

    @Test fun iSegnaliniSiSalvanoEISuoniCiSiAttaccano() {
        val s = ProgettoSuono("Segni", listOf(suono("voce", 3_000)), segni = listOf(2_500L, 7_000L))
        val letto = Suono.leggi(Suono.scrivi(s))!!
        assertEquals(listOf(2_500L, 7_000L), letto.segni)
        assertEquals(emptyList<Long>(), Suono.leggi("151suono 2\tV\nK\tnon-numero\nK\t-5\n")!!.segni)
        assertTrue(Suono.bordi(s.pezzi, -1, s.segni).contains(7_000L))
    }

    // --- registrare ascoltando: solo in cuffia
    @Test fun ilProgettoSuonaSoloInCuffia() {
        assertTrue(Suono.sonoCuffie(intArrayOf(2, 4)))   // altoparlante + cuffie a filo
        assertTrue(Suono.sonoCuffie(intArrayOf(2, 8)))   // Bluetooth
        assertTrue(!Suono.sonoCuffie(intArrayOf(1, 2)))  // auricolare del telefono e altoparlante
        assertTrue(!Suono.sonoCuffie(intArrayOf()))
    }

    // --- passaggio morbido ---

    @Test fun ilPassaggioSovrapponeIDueSuoniESfuma() {
        val (a, _) = Suono.metti(emptyList(), suono("uno", 10_000), 0)!!
        val (b, _) = Suono.metti(a, suono("due", 8_000), 10_000)!!
        val i = b.indexOfFirst { it.nome == "uno" }
        val (c, k) = Suono.passaggio(b, i, 2_000)!!
        val uno = c.first { it.nome == "uno" }; val due = c[k]
        assertEquals("due", due.nome)
        assertEquals(8_000, due.posMs)                 // parte 2 s prima della fine del primo
        assertEquals(1, due.corsia)                    // nella corsia sotto
        assertTrue(uno.esce && uno.durEsce == 2_000L)
        assertTrue(due.entra && due.durEntra == 2_000L)
        assertEquals(16_000, Suono.totaleMs(c))        // il progetto si accorcia della sovrapposizione
        assertEquals(10_000, uno.fineGlobaleMs)        // il primo non si muove
    }

    @Test fun ilPassaggioSiAccorciaAMetaDelPiuCorto() {
        val (a, _) = Suono.metti(emptyList(), suono("uno", 10_000), 0)!!
        val (b, _) = Suono.metti(a, suono("corto", 1_000), 10_200)!!
        val (c, k) = Suono.passaggio(b, b.indexOfFirst { it.nome == "uno" }, 5_000)!!
        assertEquals(500L, c[k].durEntra)
        assertEquals(9_500, c[k].posMs)
    }

    @Test fun senzaUnSuonoSubitoDopoNienteDaFare() {
        val (a, _) = Suono.metti(emptyList(), suono("uno", 10_000), 0)!!
        assertNull(Suono.passaggio(a, 0, 2_000))
        val (b, _) = Suono.metti(a, suono("lontano", 5_000), 12_000)!! // buco di 2 s
        assertNull(Suono.passaggio(b, b.indexOfFirst { it.nome == "uno" }, 2_000))
        // e il suono prima non conta: il passaggio va avanti, non indietro
        assertNull(Suono.passaggio(b, b.indexOfFirst { it.nome == "lontano" }, 2_000))
    }

    @Test fun laCorsiaSottoOccupataUsaQuellaSopraOLibera() {
        var p = Suono.metti(emptyList(), suono("sopra", 20_000), 0)!!.first            // corsia 0
        p = Suono.metti(p, suono("uno", 10_000), 0, 1)!!.first                         // corsia 1
        p = Suono.metti(p, suono("due", 6_000), 10_000, 1)!!.first                     // corsia 1, dopo
        p = Suono.metti(p, suono("sotto", 20_000), 0, 2)!!.first                       // corsia 2 piena
        val (c, k) = Suono.passaggio(p, p.indexOfFirst { it.nome == "uno" }, 2_000)!!
        assertEquals("due", c[k].nome)
        assertEquals(3, c[k].corsia)                   // 2 e 0 occupate in quel tempo → la prima libera
        assertEquals(4, c.size)
    }
}
