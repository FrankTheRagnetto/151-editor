package com.the151universe.art

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**: i formati del file audio salvato. Intestazioni scritte a mano, controllate byte per byte. */
class FormatiTest {
    @Test fun laPrimaVoltaEMp3EUnNomeSconosciutoAnche() {
        assertEquals(FormatoSuono.MP3, FormatoSuono.da(null))
        assertEquals(FormatoSuono.MP3, FormatoSuono.da("OGG"))
        assertEquals(FormatoSuono.FLAC, FormatoSuono.da("FLAC"))
        assertEquals(listOf("MP3", "M4A", "WAV", "FLAC"), FormatoSuono.entries.map { it.nome })
    }

    @Test fun wavStereo48k() {
        val h = Wav.intestazione(48_000, 2, 192_000)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4)); assertEquals(36 + 192_000, b.getInt(4)); assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4)); assertEquals(16, b.getInt(16)); assertEquals(1, b.getShort(20).toInt())
        assertEquals(2, b.getShort(22).toInt()); assertEquals(48_000, b.getInt(24)); assertEquals(192_000, b.getInt(28))
        assertEquals(4, b.getShort(32).toInt()); assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(h, 36, 4)); assertEquals(192_000, b.getInt(40))
    }

    /** Una STREAMINFO come la scrive libFLAC: 44,1 kHz, 2 canali, 16 bit, 0 campioni. */
    private fun streaminfo(conMagia: Boolean): ByteArray {
        val si = ByteArray(34)
        si[0] = 0x10; si[1] = 0x00; si[2] = 0x10; si[3] = 0x00 // blocchi 4096
        // 20 bit hz (44100 = 0x0AC44), 3 bit canali-1 (1), 5 bit bps-1 (15), 36 bit campioni (0)
        si[10] = 0x0A; si[11] = 0xC4.toByte(); si[12] = (0x40 or (1 shl 1) or 0).toByte(); si[13] = (15 shl 4).toByte()
        val blocco = byteArrayOf(0x80.toByte(), 0, 0, 34) + si // ultimo blocco, tipo 0, lungo 34
        return if (conMagia) "fLaC".toByteArray() + blocco else blocco
    }

    @Test fun flacScriveIlNumeroDiCampioniSenzaToccareIlResto() {
        val n = 0x9_1234_5678L // usa anche i 4 bit alti
        for (conMagia in listOf(true, false)) {
            val h = Flac.intestazione(streaminfo(conMagia), n)
            assertEquals("fLaC", String(h, 0, 4))
            assertEquals(n, Flac.campioni(h))
            // hz, canali e bit restano
            assertEquals(0x0A, h[8 + 10].toInt()); assertEquals(0xC4.toByte(), h[8 + 11]); assertEquals(0x42, h[8 + 12].toInt())
            assertEquals(0xF0, h[8 + 13].toInt() and 0xF0)
        }
    }

    @Test fun flacAZeroResta() {
        val h0 = Flac.intestazione(streaminfo(true), 0)
        assertArrayEquals(streaminfo(true), h0)
    }

    @Test fun lIntestazioneRipetutaNonEUnFotogramma() {
        assertEquals(true, Flac.eIntestazione(streaminfo(true)))
        assertEquals(false, Flac.eIntestazione(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x08)))
        assertEquals(false, Flac.eIntestazione(byteArrayOf(0x66)))
    }

    @Test fun lIntestazioneMandataDueVolteEntraUnaVolta() {
        val h = streaminfo(true)
        val due = Flac.unisci(Flac.unisci(null, h), h)
        assertArrayEquals(h, due)
        // a pezzi (magia, poi STREAMINFO) si attacca
        val pezzi = Flac.unisci(Flac.unisci(null, h.copyOfRange(0, 4)), h.copyOfRange(4, h.size))
        assertArrayEquals(h, pezzi)
    }

    @Test fun laFirmaMd5VaAlSuoPosto() {
        val m = ByteArray(16) { (it + 1).toByte() }
        val h = Flac.intestazione(streaminfo(true), 1000, m)
        assertArrayEquals(m, h.copyOfRange(8 + 18, 8 + 34))
        assertEquals(1000L, Flac.campioni(h))
        assertEquals(streaminfo(true).size, h.size)
    }
}
