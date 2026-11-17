package com.the151universe.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** I conti dell'onda del suono (Onde): picchi per passo e massimo su un pezzo. */
class OndeTest {
    @Test fun ilPiccoFinisceNelSuoPasso() {
        val out = FloatArray(10)
        // 1000 campioni al secondo, mono: il campione 50 sta a 50 ms = passo 2 (20 ms l'uno)
        val pcm = ShortArray(100); pcm[50] = 16384
        Onde.picchi(pcm, pcm.size, 1, 1000, 0, out)
        assertEquals(0.5f, out[2], 1e-3f)
        assertEquals(0f, out[1], 1e-6f)
    }

    @Test fun conDueCanaliVinceIlPiuForteEIlTempoDiPartenzaConta() {
        val out = FloatArray(10)
        val pcm = shortArrayOf(100, -32768) // un fotogramma stereo
        Onde.picchi(pcm, 2, 2, 1000, 60_000, out) // parte a 60 ms = passo 3
        assertEquals(1f, out[3], 1e-3f)
    }

    @Test fun fuoriDallOndaNonSiRompe() {
        val out = FloatArray(2)
        Onde.picchi(ShortArray(4) { 1000 }, 4, 1, 1000, 10_000_000, out)
        assertTrue(out.all { it == 0f })
        assertEquals(0f, Onde.massimo(FloatArray(0), 0, 100), 1e-6f)
    }

    @Test fun ilMassimoGuardaTuttoIlPezzo() {
        val o = floatArrayOf(0f, 0.25f, 0f, 1f, 0f)
        assertEquals(0.5f, Onde.massimo(o, 0, 25), 1e-3f)   // passi 0..1: radice di 0.25
        assertEquals(1f, Onde.massimo(o, 20, 70), 1e-3f)    // passi 1..3
        assertEquals(0f, Onde.massimo(o, 80, 80), 1e-6f)
    }
}
