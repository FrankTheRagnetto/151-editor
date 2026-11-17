package com.the151universe.art

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**: la pulizia del rumore, su suoni finti. */
class RumoreTest {
    private val hz = 44100
    private fun db(a: Double, b: Double) = 10 * log10(a / b)
    private fun energia(x: FloatArray, da: Int, a: Int): Double { var e = 0.0; for (i in da until a) e += x[i].toDouble() * x[i]; return e }

    /** 3 s: rumore bianco 0,05 sempre; un tono di 1 kHz a 0,5 fra 1 s e 2 s. */
    private fun prova(): FloatArray {
        val r = java.util.Random(151)
        return FloatArray(3 * hz) { i ->
            val rumore = (r.nextGaussian() * 0.05).toFloat()
            val tono = if (i in hz until 2 * hz) (0.5 * sin(2 * PI * 1000 * i / hz)).toFloat() else 0f
            rumore + tono
        }
    }

    @Test fun laFftAndataERitornoRidaIlSegnale() {
        val x = FloatArray(Rumore.N) { sin(it * 0.37f) + 0.2f * it / Rumore.N }
        val re = x.copyOf(); val im = FloatArray(Rumore.N)
        Rumore.fft(re, im); Rumore.fft(re, im, inversa = true)
        for (k in x.indices) assertEquals(x[k], re[k] / Rumore.N, 1e-4f)
    }

    @Test fun senzaRumoreIlSuonoEsceUgualeEAllineato() {
        val x = prova()
        val y = Rumore.pulisci(x, FloatArray(Rumore.BIN))
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals("campione $i", x[i], y[i], 1e-4f)
    }

    @Test fun lePauseImparanoIlRumoreNonIlTono() {
        val x = prova()
        val q = Rumore.quieti(Rumore.energie(x))
        // nessun blocco quieto dentro il tono (1-2 s)
        assertTrue(q.none { b -> b * Rumore.N + Rumore.N > hz && b * Rumore.N < 2 * hz })
    }

    @Test fun ilRumoreScendeDiAlmeno15dBEIlTonoResta() {
        val x = prova()
        val y = Rumore.pulisci(x)
        val pausa = db(energia(y, hz / 4, 3 * hz / 4), energia(x, hz / 4, 3 * hz / 4))
        val tono = db(energia(y, hz + hz / 4, 2 * hz - hz / 4), energia(x, hz + hz / 4, 2 * hz - hz / 4))
        println("pausa %.1f dB, tono %.2f dB".format(pausa, tono))
        assertTrue("pausa $pausa dB", pausa < -15)
        assertTrue("tono $tono dB", tono > -1.0 && tono < 0.5)
    }

    @Test fun aPezziEUgualeCheTuttoInsieme() {
        val x = prova()
        val p = Rumore.profilo(x)
        val intero = Cancello(p).tutto(x)
        val c = Cancello(p)
        val pezzi = ArrayList<Float>()
        var i = 0
        val misure = intArrayOf(1, 777, 4096, 13, 30000)
        var m = 0
        while (i < x.size) { val n = minOf(misure[m++ % misure.size], x.size - i); pezzi += c.spingi(x.copyOfRange(i, i + n)).toList(); i += n }
        pezzi += c.spingi(FloatArray(Rumore.N)).toList()
        val a = pezzi.toFloatArray().copyOfRange(Rumore.N - Rumore.H, Rumore.N - Rumore.H + x.size)
        assertArrayEquals(intero, a, 1e-6f)
    }

    /** Rumore rosa (filtro di Paul Kellet, come anoisesrc pink): i bassi forti vanno e vengono. Le pause si scelgono sopra i bassi, se no li si sottostima. */
    @Test fun iBassiCheOscillanoNonIngannanoLaScelta() {
        val r = java.util.Random(7)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val x = FloatArray(3 * hz) { i ->
            val w = r.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
            val rosa = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11 * 0.03; b6 = w * 0.115926
            val tono = if (i in hz until 2 * hz) (0.3 * sin(2 * PI * 1000 * i / hz)).toFloat() else 0f
            rosa.toFloat() + tono
        }
        val y = Rumore.pulisci(x)
        val pausa = db(energia(y, hz / 4, 3 * hz / 4), energia(x, hz / 4, 3 * hz / 4))
        println("rosa: pausa %.1f dB".format(pausa))
        assertTrue("pausa $pausa dB", pausa < -15)
    }

    @Test fun senzaPauseNonSiTocca() {
        val r = java.util.Random(3)
        // 3 s di rumore e tono fisso, come la musica continua
        val musica = FloatArray(3 * hz) { i -> (r.nextGaussian() * 0.05 + 0.2 * sin(2 * PI * 220 * i / hz)).toFloat() }
        val e = Rumore.energie(musica)
        assertEquals(false, Rumore.pauseChiare(e, Rumore.quieti(e)))
        val parlato = prova()
        val e2 = Rumore.energie(parlato)
        assertEquals(true, Rumore.pauseChiare(e2, Rumore.quieti(e2)))
    }
}
