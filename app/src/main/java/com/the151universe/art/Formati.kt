package com.the151universe.art

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * I formati del file audio salvato.
 * M4A esce dritto dal Transformer; gli altri nascono dal suono mescolato preso a metà strada
 * ([Presa], PCM 16 bit), così WAV e FLAC sono davvero senza perdita e l'MP3 non passa dall'AAC.
 */
enum class FormatoSuono(val est: String, val mime: String, val nome: String, val spiega: String) {
    MP3("mp3", "audio/mpeg", "MP3", "Plays everywhere · 256 kbps"),
    M4A("m4a", "audio/mp4", "M4A", "Small · AAC"),
    WAV("wav", "audio/x-wav", "WAV", "Lossless · big"),
    FLAC("flac", "audio/flac", "FLAC", "Lossless · about half of WAV");

    companion object {
        fun da(s: String?): FormatoSuono = entries.firstOrNull { it.name == s } ?: MP3
    }
}

/** Il WAV: 44 byte di intestazione e i campioni così come sono. */
object Wav {
    fun intestazione(hz: Int, canali: Int, byteDati: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val dati = byteDati.coerceAtMost(0xFFFF_FFFFL - 36).toInt()
        b.put("RIFF".toByteArray()); b.putInt(36 + dati); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(canali.toShort())
        b.putInt(hz); b.putInt(hz * canali * 2); b.putShort((canali * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dati)
        return b.array()
    }
}

/** Il FLAC: l'encoder di Android dà l'intestazione e i fotogrammi; qui la si mette in ordine. */
object Flac {
    /**
     * L'intestazione «fLaC» + STREAMINFO col numero vero di campioni (l'encoder lo lascia a 0:
     * i lettori allora non sanno la durata). [h] può arrivare con o senza «fLaC» davanti.
     */
    fun intestazione(h: ByteArray, campioni: Long, md5: ByteArray? = null): ByteArray {
        val conMagia = if (h.size >= 4 && String(h, 0, 4, Charsets.US_ASCII) == "fLaC") h.copyOf() else "fLaC".toByteArray() + h
        // STREAMINFO: 4 byte di «fLaC», 4 di blocco, poi 2+2+3+3 = 10; i 36 bit dei campioni partono a +13, bit 4
        require(conMagia.size >= 8 + 34 && (conMagia[4].toInt() and 0x7F) == 0) { "no STREAMINFO" }
        val k = 8 + 13
        val n = campioni.coerceIn(0, (1L shl 36) - 1)
        conMagia[k] = ((conMagia[k].toInt() and 0xF0) or ((n shr 32).toInt() and 0x0F)).toByte()
        conMagia[k + 1] = (n shr 24).toByte(); conMagia[k + 2] = (n shr 16).toByte()
        conMagia[k + 3] = (n shr 8).toByte(); conMagia[k + 4] = n.toByte()
        // la firma MD5 dei campioni (16 bit little-endian intercalati = il file della Presa): `flac -t` la controlla
        md5?.let { require(it.size == 16); it.copyInto(conMagia, 8 + 18) }
        return conMagia
    }

    /** Un pezzo che comincia con «fLaC» è l'intestazione, non un fotogramma (quelli cominciano con 0xFFF8). */
    fun eIntestazione(b: ByteArray) = b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
        b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    /**
     * I pezzi dell'intestazione man mano che arrivano. L'encoder del telefono di prova la manda DUE volte intera
     * (15/11: file con due «fLaC», `flac -t` → LOST_SYNC): una seconda che ricomincia con «fLaC» si scarta.
     */
    fun unisci(finora: ByteArray?, pezzo: ByteArray): ByteArray =
        if (finora == null) pezzo else if (eIntestazione(pezzo)) finora else finora + pezzo

    /** I campioni scritti nell'intestazione (per le prove). */
    fun campioni(h: ByteArray): Long {
        val k = 8 + 13
        return ((h[k].toLong() and 0x0F) shl 32) or ((h[k + 1].toLong() and 0xFF) shl 24) or
            ((h[k + 2].toLong() and 0xFF) shl 16) or ((h[k + 3].toLong() and 0xFF) shl 8) or (h[k + 4].toLong() and 0xFF)
    }
}

/** Il ponte verso LAME (cpp/mp3.c). */
object Mp3 {
    init { System.loadLibrary("mp3") }
    @JvmStatic external fun apri(hz: Int, canali: Int, kbps: Int): Long
    @JvmStatic external fun codifica(h: Long, pcm: ShortArray, frame: Int, out: ByteArray): Int
    @JvmStatic external fun chiudi(h: Long, out: ByteArray?): Int
}

/**
 * Prende il suono mescolato che passa (dopo tutti gli effetti, prima dell'encoder) e lo scrive
 * in [file] come PCM 16 bit little-endian. Il suono va avanti uguale.
 */
@OptIn(UnstableApi::class)
class Presa(private val file: File) : BaseAudioProcessor() {
    @Volatile var hz = 0; private set
    @Volatile var canali = 0; private set
    @Volatile var frame = 0L; private set
    @Volatile var errore: String? = null; private set
    private var float = false
    private var out: OutputStream? = null

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) throw AudioProcessor.UnhandledAudioFormatException(f)
        if (hz != 0 && (hz != f.sampleRate || canali != f.channelCount)) errore = "format changed"
        hz = f.sampleRate; canali = f.channelCount; float = f.encoding == C.ENCODING_PCM_FLOAT
        if (canali !in 1..2) errore = "$canali channels"
        return f
    }

    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining()
        if (n == 0) return
        val src = input.order(ByteOrder.nativeOrder())
        val o = out ?: BufferedOutputStream(FileOutputStream(file), 1 shl 16).also { out = it }
        val bytes = if (float) 4 else 2
        val campioni = n / bytes
        val buf = ByteArray(campioni * 2)
        val p = src.position()
        for (k in 0 until campioni) {
            val s = if (float) (src.getFloat(p + k * 4).coerceIn(-1f, 1f) * 32767f).toInt() else src.getShort(p + k * 2).toInt()
            buf[2 * k] = s.toByte(); buf[2 * k + 1] = (s shr 8).toByte()
        }
        try { o.write(buf) } catch (e: Exception) { errore = e.message ?: "write failed" }
        frame += campioni / canali.coerceAtLeast(1)
        // il suono passa intatto
        val dest = replaceOutputBuffer(n)
        dest.put(src)
        dest.flip()
    }

    /** Fine dell'export: il file è chiuso e completo. */
    fun chiudi() { try { out?.close() } catch (_: Exception) {}; out = null }
}

/** Dal PCM preso dalla [Presa] al file del formato scelto. [avanti] 0..100. */
object Codifica {
    private const val PASSO = 4096 // frame per giro

    /** readNBytes vuole Android 13: qui la stessa cosa per tutti. */
    private fun java.io.InputStream.leggi(b: ByteArray, quanti: Int): Int {
        var n = 0
        while (n < quanti) { val r = read(b, n, quanti - n); if (r < 0) break; n += r }
        return if (n == 0 && quanti > 0) -1 else n
    }

    fun scrivi(f: FormatoSuono, pcm: File, hz: Int, canali: Int, dest: File, avanti: (Int) -> Unit, fermato: () -> Boolean) {
        when (f) {
            FormatoSuono.WAV -> wav(pcm, hz, canali, dest, avanti, fermato)
            FormatoSuono.MP3 -> mp3(pcm, hz, canali, dest, avanti, fermato)
            FormatoSuono.FLAC -> flac(pcm, hz, canali, dest, avanti, fermato)
            FormatoSuono.M4A -> error("M4A comes from the exporter")
        }
    }

    private fun wav(pcm: File, hz: Int, canali: Int, dest: File, avanti: (Int) -> Unit, fermato: () -> Boolean) {
        val tot = pcm.length()
        BufferedOutputStream(FileOutputStream(dest), 1 shl 16).use { o ->
            o.write(Wav.intestazione(hz, canali, tot))
            pcm.inputStream().use { i ->
                val b = ByteArray(1 shl 16); var fatti = 0L
                while (true) { if (fermato()) return; val n = i.read(b); if (n < 0) break; o.write(b, 0, n); fatti += n; avanti((fatti * 100 / tot.coerceAtLeast(1)).toInt()) }
            }
        }
    }

    private fun mp3(pcm: File, hz: Int, canali: Int, dest: File, avanti: (Int) -> Unit, fermato: () -> Boolean) {
        val h = Mp3.apri(hz, canali, 256)
        check(h != 0L) { "MP3 encoder refused $hz Hz" }
        var chiuso = false
        try {
            val tot = pcm.length()
            val b = ByteArray(PASSO * canali * 2)
            val s = ShortArray(PASSO * canali)
            val out = ByteArray(PASSO * 5 / 4 + 7200)
            BufferedOutputStream(FileOutputStream(dest), 1 shl 16).use { o ->
                pcm.inputStream().buffered(1 shl 16).use { i ->
                    var fatti = 0L
                    while (true) {
                        if (fermato()) return
                        val n = i.leggi(b, b.size)
                        if (n <= 0) break
                        ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s, 0, n / 2)
                        val r = Mp3.codifica(h, s, n / 2 / canali, out)
                        check(r >= 0) { "MP3 encoder error $r" }
                        o.write(out, 0, r); fatti += n
                        avanti((fatti * 100 / tot.coerceAtLeast(1)).toInt())
                    }
                }
                val r = Mp3.chiudi(h, out); chiuso = true
                check(r >= 0) { "MP3 encoder error $r" }
                o.write(out, 0, r)
            }
        } finally { if (!chiuso) Mp3.chiudi(h, null) }
    }

    private fun md5(f: File): ByteArray {
        val d = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest()
    }

    private fun flac(pcm: File, hz: Int, canali: Int, dest: File, avanti: (Int) -> Unit, fermato: () -> Boolean) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, hz, canali).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PASSO * canali * 2)
        }
        val cod = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        try {
            cod.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            cod.start()
            val tot = pcm.length()
            val frameTot = tot / (2 * canali)
            var testa: ByteArray? = null
            RandomAccessFile(dest, "rw").use { o ->
                o.setLength(0)
                pcm.inputStream().buffered(1 shl 16).use { i ->
                    val info = MediaCodec.BufferInfo()
                    var finitoIn = false; var finitoOut = false; var fatti = 0L; var tempoUs = 0L
                    while (!finitoOut) {
                        if (fermato()) return
                        if (!finitoIn) {
                            val ix = cod.dequeueInputBuffer(10_000)
                            if (ix >= 0) {
                                val buf = cod.getInputBuffer(ix)!!
                                val b = ByteArray(minOf(buf.capacity(), PASSO * canali * 2) / (2 * canali) * (2 * canali))
                                val n = i.leggi(b, b.size)
                                if (n <= 0) { cod.queueInputBuffer(ix, 0, 0, tempoUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finitoIn = true }
                                else {
                                    buf.clear(); buf.put(b, 0, n)
                                    cod.queueInputBuffer(ix, 0, n, tempoUs, 0)
                                    fatti += n; tempoUs = fatti / (2 * canali) * 1_000_000L / hz
                                    avanti((fatti * 100 / tot.coerceAtLeast(1)).toInt())
                                }
                            }
                        }
                        val ox = cod.dequeueOutputBuffer(info, 10_000)
                        if (ox >= 0) {
                            val buf = cod.getOutputBuffer(ox)!!
                            val b = ByteArray(info.size); buf.position(info.offset); buf.get(b)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || Flac.eIntestazione(b)) {
                                testa = Flac.unisci(testa, b)
                            } else if (b.isNotEmpty()) {
                                if (o.length() == 0L) {
                                    val t = testa ?: error("FLAC encoder gave no header")
                                    o.write(Flac.intestazione(t, 0)) // il numero vero si scrive alla fine
                                }
                                o.write(b)
                            }
                            cod.releaseOutputBuffer(ox, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finitoOut = true
                        } else if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && testa == null) {
                            // alcuni encoder danno l'intestazione solo nel formato («csd-0»)
                            cod.outputFormat.getByteBuffer("csd-0")?.let { c -> testa = ByteArray(c.remaining()).also { c.get(it) } }
                        }
                    }
                }
                // la durata vera e la firma nell'intestazione (stessa lunghezza: si riscrive sopra)
                val t = testa ?: error("FLAC encoder gave no header")
                o.seek(0); o.write(Flac.intestazione(t, frameTot, md5(pcm)))
            }
        } finally {
            try { cod.stop() } catch (_: Exception) {}
            cod.release()
        }
    }
}
