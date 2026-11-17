package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FotoTest {
    private val eps = 1e-4f
    private fun px(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    // ------------------------------------------------------------ ritaglio

    @Test fun formaDeiRapporti() {
        assertNull(Foto.forma("free", 400, 300))
        assertEquals(4f / 3f, Foto.forma("orig", 400, 300)!!, eps)
        assertEquals(0.8f, Foto.forma("4:5", 400, 300)!!, eps)
        assertEquals(16f / 9f, Foto.forma("16:9", 1, 1)!!, eps)
        assertNull(Foto.forma("rotto", 400, 300))
    }

    @Test fun ritaglioCentratoTieneLaFormaEStaDentro() {
        for ((w, h) in listOf(4000 to 3000, 3000 to 4000, 1000 to 1000)) {
            for ((id, _) in Foto.RAPPORTI) {
                val f = Foto.forma(id, w, h) ?: continue
                val c = Foto.ritaglioCentrato(w, h, f)
                assertEquals("$id su ${w}x$h", f, c.largo * w / (c.alto * h), 1e-3f)
                assertTrue(c.l >= -eps && c.t >= -eps && c.r <= 1 + eps && c.b <= 1 + eps)
                // il piu' grande possibile: tocca due lati opposti
                assertTrue(c.largo > 1 - 1e-3f || c.alto > 1 - 1e-3f)
            }
        }
    }

    @Test fun trascinaAngoloLiberoNonEsceENonSiRovescia() {
        val r = Foto.trascina(Ritaglio(), Maniglia.SE, -5f, -5f, 1000, 800, null)
        assertEquals(0f, r.l, eps); assertEquals(0f, r.t, eps)
        assertEquals(Foto.RITAGLIO_MIN * 800 / 1000, r.r, eps) // largo il minimo, non negativo
        assertEquals(Foto.RITAGLIO_MIN, r.b, eps)
        val fuori = Foto.trascina(Ritaglio(0.2f, 0.2f, 0.8f, 0.8f), Maniglia.NO, -3f, -3f, 1000, 800, null)
        assertEquals(Ritaglio(0f, 0f, 0.8f, 0.8f), fuori)
    }

    @Test fun trascinaAngoloConFormaLaTiene() {
        val w = 4000; val h = 3000
        val f = Foto.forma("1:1", w, h)!!
        val partenza = Foto.ritaglioCentrato(w, h, f)
        for ((dx, dy) in listOf(-0.1f to 0f, 0f to -0.2f, -0.3f to -0.05f, 0.5f to 0.5f, -2f to -2f)) {
            val r = Foto.trascina(partenza, Maniglia.SE, dx, dy, w, h, f)
            assertEquals("dx=$dx dy=$dy", f, r.largo * w / (r.alto * h), 1e-3f)
            assertTrue(r.r <= 1 + eps && r.b <= 1 + eps && r.l >= -eps && r.t >= -eps)
            // l'angolo opposto non si muove
            assertEquals(partenza.l, r.l, eps); assertEquals(partenza.t, r.t, eps)
        }
    }

    @Test fun conFormaILatiNonSiMuovono() {
        val p = Ritaglio(0.1f, 0.1f, 0.9f, 0.9f)
        assertEquals(p, Foto.trascina(p, Maniglia.N, 0f, 0.3f, 100, 100, 1f))
        assertNotEquals(p, Foto.trascina(p, Maniglia.N, 0f, 0.3f, 100, 100, null))
    }

    @Test fun spostaRestaDentroEConservaLaMisura() {
        val p = Ritaglio(0.2f, 0.3f, 0.6f, 0.7f)
        val r = Foto.trascina(p, Maniglia.SPOSTA, 5f, -5f, 1000, 1000, null)
        assertEquals(0.4f, r.largo, eps); assertEquals(0.4f, r.alto, eps)
        assertEquals(1f, r.r, eps); assertEquals(0f, r.t, eps)
    }

    // ------------------------------------------------------------ giri, specchio, raddrizza

    @Test fun quattroGiriTornanoAlPuntoDiPartenza() {
        val m0 = Modifiche(ritaglio = Ritaglio(0.1f, 0.2f, 0.5f, 0.9f), testi = listOf(Testo(1, "ciao", 0.2f, 0.7f)))
        var m = m0
        repeat(4) { m = Foto.gira(m, 4000, 3000) }
        assertEquals(0, m.giri)
        assertEquals(m0.ritaglio.l, m.ritaglio.l, eps); assertEquals(m0.ritaglio.b, m.ritaglio.b, eps)
        assertEquals(0.2f, m.testi[0].x, eps); assertEquals(0.7f, m.testi[0].y, eps)
    }

    @Test fun unGiroPortaIlRitaglioAlPostoGiusto() {
        // in alto a sinistra → dopo un giro orario: in alto a destra
        val m = Foto.gira(Modifiche(ritaglio = Ritaglio(0f, 0f, 0.5f, 0.25f)), 400, 400)
        assertEquals(Ritaglio(0.75f, 0f, 1f, 0.5f), m.ritaglio)
    }

    @Test fun conFormaFissaIlGiroRifaIlRitaglioAlCentro() {
        val w = 4000; val h = 3000
        val m = Foto.conRapporto(Modifiche(), "16:9", w, h)
        val g = Foto.gira(m, w, h)
        val (qw, qh) = Foto.quadro(w, h, g.giri)
        assertEquals(16f / 9f, g.ritaglio.largo * qw / (g.ritaglio.alto * qh), 1e-3f)
    }

    @Test fun specchiareDueVolteNonCambiaNiente() {
        val m = Modifiche(giri = 1, raddrizza = 7f, ritaglio = Ritaglio(0.1f, 0.2f, 0.4f, 0.9f), testi = listOf(Testo(1, "a", 0.3f, 0.4f)))
        val s = Foto.specchia(m)
        assertTrue(s.specchio); assertEquals(3, s.giri); assertEquals(-7f, s.raddrizza, eps)
        assertEquals(0.7f, s.testi[0].x, eps)
        val ss = Foto.specchia(s)
        assertEquals(m.giri, ss.giri); assertFalse(ss.specchio); assertEquals(m.raddrizza, ss.raddrizza, eps)
        assertEquals(m.ritaglio.l, ss.ritaglio.l, eps); assertEquals(m.ritaglio.r, ss.ritaglio.r, eps)
    }

    @Test fun raddrizzaIngrandisceQuantoBasta() {
        assertEquals(1f, Foto.zoomRaddrizza(400f, 300f, 0f), eps)
        val s = Foto.zoomRaddrizza(400f, 300f, 10f)
        assertTrue(s > 1f)
        // gli angoli del quadro, riportati nella foto girata e ingrandita, stanno dentro la foto
        val a = Math.toRadians(10.0)
        for ((x, y) in listOf(-200.0 to -150.0, 200.0 to -150.0, 200.0 to 150.0, -200.0 to 150.0)) {
            val xr = (x * Math.cos(a) + y * Math.sin(a)) / s
            val yr = (-x * Math.sin(a) + y * Math.cos(a)) / s
            assertTrue("$x,$y", Math.abs(xr) <= 200.001 && Math.abs(yr) <= 150.001)
        }
    }

    @Test fun exifDiventaGiriESpecchio() {
        assertEquals(0 to false, Foto.exif(1))
        assertEquals(1 to false, Foto.exif(6))
        assertEquals(3 to false, Foto.exif(8))
        assertEquals(0 to true, Foto.exif(2))
        assertEquals(0 to false, Foto.exif(0))
    }

    @Test fun campioneTieneSottoIlTetto() {
        assertEquals(1, Foto.campione(4000, 3000, Foto.PIXEL_MAX))
        assertEquals(2, Foto.campione(8160, 6120, Foto.PIXEL_MAX))   // 50 MP
        assertEquals(4, Foto.campione(16320, 12240, Foto.PIXEL_MAX)) // 200 MP
        assertEquals(2, Foto.campioneAnteprima(4000, 3000, 1600))
        assertEquals(1, Foto.campioneAnteprima(1200, 900, 1600))
    }

    // ------------------------------------------------------------ colore

    @Test fun senzaModificheIPixelNonCambiano() {
        val p = intArrayOf(px(10, 120, 250), px(0, 0, 0), px(255, 255, 255), px(1, 2, 3, 7))
        val c = p.copyOf()
        Foto.colora(c, Modifiche())
        assertTrue(p.contentEquals(c))
        Foto.colora(c, Modifiche(filtro = "bw", forza = 0))
        assertTrue(p.contentEquals(c))
    }

    @Test fun biancoENeroFaCanaliUguali() {
        val c = intArrayOf(px(200, 40, 90), px(30, 220, 10))
        Foto.colora(c, Modifiche(filtro = "bw"))
        for (v in c) { assertEquals(r(v), g(v)); assertEquals(g(v), b(v)) }
    }

    @Test fun laForzaStaInMezzo() {
        val o = px(200, 40, 90)
        val pieno = intArrayOf(o).also { Foto.colora(it, Modifiche(filtro = "bw", forza = 100)) }[0]
        val meta = intArrayOf(o).also { Foto.colora(it, Modifiche(filtro = "bw", forza = 50)) }[0]
        assertEquals((200 + r(pieno)) / 2.0, r(meta).toDouble(), 1.0)
        assertEquals((40 + g(pieno)) / 2.0, g(meta).toDouble(), 1.0)
    }

    @Test fun laLuceVaNelVersoGiusto() {
        val grigio = px(128, 128, 128)
        fun dopo(l: Luce) = intArrayOf(grigio).also { Foto.colora(it, Modifiche(luce = l)) }[0]
        assertTrue(r(dopo(Luce(luminosita = 50))) > 128)
        assertTrue(r(dopo(Luce(luminosita = -50))) < 128)
        val caldo = dopo(Luce(calore = 80)); assertTrue(r(caldo) > b(caldo))
        val freddo = dopo(Luce(calore = -80)); assertTrue(b(freddo) > r(freddo))
        val scuro = px(30, 30, 30)
        assertTrue(r(intArrayOf(scuro).also { Foto.colora(it, Modifiche(luce = Luce(ombre = 80))) }[0]) > 30)
        val chiaro = px(230, 230, 230)
        assertTrue(r(intArrayOf(chiaro).also { Foto.colora(it, Modifiche(luce = Luce(luci = -80))) }[0]) < 230)
    }

    @Test fun ilColoreNonToccaLAlfaERestaNeiLimiti() {
        val c = intArrayOf(px(250, 250, 250, 40), px(5, 5, 5, 200))
        Foto.colora(c, Modifiche(luce = Luce(luminosita = 100, contrasto = 100, saturazione = 100), filtro = "pop"))
        assertEquals(40, c[0] ushr 24); assertEquals(200, c[1] ushr 24)
    }

    @Test fun ogniFiltroHaUnIdSolo() {
        assertEquals(Foto.FILTRI.size, Foto.FILTRI.map { it.id }.toSet().size)
        assertEquals("", Foto.FILTRI.first().id) // il primo e' «Original»
    }

    // ------------------------------------------------------------ stato e nomi

    @Test fun statoSalvatoERiletto() {
        val m = Modifiche(
            giri = 3, specchio = true, raddrizza = -12.5f, ritaglio = Ritaglio(0.1f, 0.2f, 0.7f, 0.95f), rapporto = "4:5",
            luce = Luce(10, -20, 30, -40, 50, -60), filtro = "vintage", forza = 42,
            testi = listOf(
                Testo(7, "ciao\tmondo\ndue righe \\ fine", 0.3f, 0.6f, 0.12f, 0xFFFF5C8A.toInt(), "pacifico", Testo.RIQUADRO),
                Testo(8, "secondo"),
            ),
        )
        assertEquals(m, Foto.leggi(Foto.scrivi(m)))
        assertNull(Foto.leggi("niente"))
        assertNull(Foto.leggi("151foto 1\nrotto"))
    }

    @Test fun nomeDelFile() {
        assertEquals("151 Editor 2026-10-11 09.05.03.jpg", Foto.nomeFile(2026, 10, 11, 9, 5, 3, "jpg"))
    }

    @Test fun storiaValeAncheperLaFoto() {
        val s = Storia(Modifiche())
        s.fai(Modifiche(filtro = "bw"))
        s.fai(Modifiche(filtro = "bw")) // uguale: non conta
        s.fai(Modifiche(filtro = "sepia"))
        assertTrue(s.annulla()); assertEquals("bw", s.adesso.filtro)
        assertTrue(s.rifai()); assertEquals("sepia", s.adesso.filtro)
        assertTrue(s.annulla()); assertTrue(s.annulla()); assertFalse(s.annulla())
        assertEquals(Modifiche(), s.adesso)
    }

    @Test fun ogniFontHaUnIdSoloEIlPrimoEsiste() {
        assertEquals(Caratteri.TUTTI.size, Caratteri.TUTTI.map { it.first }.toSet().size)
        assertTrue(Caratteri.TUTTI.any { it.first == Caratteri.PRIMO })
    }

    @Test fun ogniFontStaNellAppConLaSuaLicenza() {
        val dir = java.io.File("src/main/assets/font")
        for ((id, _, file) in Caratteri.TUTTI) {
            if (file == null) continue
            val f = java.io.File(dir, file)
            assertTrue("$id: manca $file", f.length() > 10_000)
            assertTrue("$id: manca la licenza", java.io.File(dir, "licenze/" + file.removeSuffix(".ttf") + ".license.txt").length() > 1_000)
        }
    }

    @Test fun bozzaFotoSalvataERiletta() {
        val b = BozzaFoto("Photo\t3\nbis", "content://media/picker/0/x/12", Modifiche(filtro = "noir", testi = listOf(Testo(1, "a"))), png = true)
        assertEquals(b, Foto.leggiBozza(Foto.scriviBozza(b)))
        val vuota = BozzaFoto("Photo 1", "file:///data/x/b1.orig", Modifiche())
        assertEquals(vuota, Foto.leggiBozza(Foto.scriviBozza(vuota)))
        assertNull(Foto.leggiBozza("151art 2\tvideo\n"))
        assertNull(Foto.leggiBozza("151fotobozza 1\tnome\t\t0\n"))
    }

    // ------------------------------------------------------------ Outline e Box su misura (14/10)

    @Test fun outlineEBoxSuMisuraSiSalvanoEIVecchiTestiRestanoUguali() {
        val m = Modifiche(testi = listOf(
            Testo(1, "bordo", stile = Testo.CONTORNO, secondo = 0xFFFF5C8A.toInt(), spessore = 0.2f),
            Testo(2, "fondo", stile = Testo.RIQUADRO, secondo = 0xFF0A84FF.toInt()),
            Testo(3, "come prima"),
        ))
        assertEquals(m, Foto.leggi(Foto.scrivi(m)))
        // una bozza scritta prima della 0.13 (9 campi) si rilegge con Auto e lo spessore di sempre
        val vecchia = Foto.leggi("151foto 1\n0\t0\t0.0\t0.0\t0.0\t1.0\t1.0\tfree\n0\t0\t0\t0\t0\t0\t\t100\nT\t5\t0.5\t0.5\t0.09\t-1\tanton\t2\tciao\n")!!
        assertEquals(0, vecchia.testi[0].secondo)
        assertEquals(Testo.SPESSORE_STD, vecchia.testi[0].spessore)
        // uno spessore assurdo scritto a mano rientra nei limiti
        val matto = Foto.leggi(Foto.scrivi(Modifiche(testi = listOf(Testo(1, "x", spessore = 9f)))))!!
        assertEquals(Testo.SPESSORE_MAX, matto.testi[0].spessore)
    }

    @Test fun autoEColoreSceltoDiOutlineEBox() {
        val giallo = 0xFFFFD60A.toInt(); val blu = 0xFF0A84FF.toInt()
        // Auto = come prima: il bordo è il contrario del testo, il Box prende il colore e le lettere il contrario
        assertEquals(0xFF000000.toInt(), Testo.coloreBordo(giallo, 0))
        assertEquals(giallo to 0xFF000000.toInt(), Testo.riquadro(giallo, 0))
        // scelto: il bordo è quello, il fondo è quello e le lettere restano del colore del testo
        assertEquals(blu, Testo.coloreBordo(giallo, blu))
        assertEquals(blu to giallo, Testo.riquadro(giallo, blu))
    }
}
