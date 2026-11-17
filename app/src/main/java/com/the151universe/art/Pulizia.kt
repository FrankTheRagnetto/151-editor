package com.the151universe.art

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * «Clean noise» sul telefono: il suono scelto → PCM in cache → [Rumore] canale per canale
 * → una copia FLAC in files/puliti. L'originale non si tocca: il progetto passa alla copia, Undo torna.
 * Gira fuori dal filo principale; [avanti] 0..100; [fermato] interrompe e pulisce.
 */
class Pulizia(private val ctx: Context, private val uri: String, private val avanti: (Int) -> Unit, private val fermato: () -> Boolean) {
    class Esito(val file: File, val dB: Double)
    /** Nessuna pausa da cui imparare il rumore: niente si cambia. */
    class SenzaPause : Exception("no quiet moments")

    private val pcm = File(ctx.cacheDir, "pulizia.part.pcm")
    private val pulito = File(ctx.cacheDir, "pulizia-pulito.part.pcm")
    private val flac = File(ctx.cacheDir, "pulizia.part.flac")

    fun fai(): Esito? {
        try {
            val (hz, ch) = decodifica() ?: return null
            avanti(25)
            val frame = pcm.length() / (2 * ch)
            check(frame >= Rumore.N) { "too short" }
            // il rumore: i blocchi più quieti (sul suono mescolato dei canali), poi la loro potenza per canale
            val profili: Array<FloatArray>
            val quieti: List<Int>
            RandomAccessFile(pcm, "r").use { f ->
                val blocchi = (frame / Rumore.N).toInt()
                val e = FloatArray(blocchi)
                val b = ByteArray(Rumore.N * ch * 2)
                for (k in 0 until blocchi) {
                    if (fermato()) return null
                    f.readFully(b)
                    val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    // sopra i bassi, come Rumore.energie: i canali si sommano
                    var t = 0f
                    for (c in 0 until ch) t += Rumore.energiaSopra(Rumore.potenza(FloatArray(Rumore.N) { s.get(it * ch + c) / 32768f }))
                    e[k] = t
                }
                quieti = Rumore.quieti(e)
                if (!Rumore.pauseChiare(e, quieti)) throw SenzaPause()
                profili = Array(ch) { FloatArray(Rumore.BIN) }
                for (q in quieti) {
                    f.seek(q.toLong() * b.size); f.readFully(b)
                    val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (c in 0 until ch) {
                        val x = FloatArray(Rumore.N) { s.get(it * ch + c) / 32768f }
                        val p = Rumore.potenza(x)
                        for (k in 0 until Rumore.BIN) profili[c][k] += p[k] / quieti.size
                    }
                }
            }
            if (fermato()) return null
            // la pulizia a pezzi; il ritardo (N − H) si salta all'inizio, la coda si spinge alla fine
            val cancelli = Array(ch) { Cancello(profili[it]) }
            val salta = (Rumore.N - Rumore.H).toLong()
            var scritti = 0L; var saltati = 0L
            BufferedOutputStream(FileOutputStream(pulito), 1 shl 16).use { o ->
                fun scrivi(usciti: Array<FloatArray>) {
                    val n = usciti[0].size
                    val buf = ByteBuffer.allocate(n * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var dati = 0
                    for (i in 0 until n) {
                        if (saltati < salta) { saltati++; continue }
                        if (scritti >= frame) break
                        for (c in 0 until ch) buf.putShort((usciti[c][i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        scritti++; dati++
                    }
                    o.write(buf.array(), 0, dati * ch * 2)
                }
                pcm.inputStream().buffered(1 shl 16).use { i ->
                    val passo = 8192
                    val b = ByteArray(passo * ch * 2)
                    var letti = 0L
                    while (true) {
                        if (fermato()) return null
                        var n = 0
                        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
                        if (n <= 0) break
                        val f = n / (2 * ch)
                        val s = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        scrivi(Array(ch) { c -> cancelli[c].spingi(FloatArray(f) { s.get(it * ch + c) / 32768f }) })
                        letti += f
                        avanti(25 + (letti * 55 / frame).toInt())
                    }
                }
                scrivi(Array(ch) { c -> cancelli[c].spingi(FloatArray(Rumore.N)) })
            }
            check(scritti == frame) { "cleaned $scritti of $frame" }
            // quanto è sceso il rumore: gli stessi blocchi quieti, prima e dopo
            val dB = RandomAccessFile(pcm, "r").use { a -> RandomAccessFile(pulito, "r").use { d ->
                var ea = 0.0; var ed = 0.0
                val b = ByteArray(Rumore.N * ch * 2)
                for (q in quieti) for ((f, somma) in listOf(a to 0, d to 1)) {
                    f.seek(q.toLong() * b.size); f.readFully(b)
                    val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var t = 0.0; for (i in 0 until Rumore.N * ch) { val v = s.get(i).toDouble(); t += v * v }
                    if (somma == 0) ea += t else ed += t
                }
                if (ea <= 0.0) 0.0 else 10 * kotlin.math.log10(maxOf(ed, 1e-9) / ea)
            } }
            if (fermato()) return null
            Codifica.scrivi(FormatoSuono.FLAC, pulito, hz, ch, flac, avanti = { avanti(80 + it / 5) }, fermato = fermato)
            if (fermato()) return null
            check(flac.length() > 0) { "empty file" }
            val dir = File(ctx.filesDir, "puliti").apply { mkdirs() }
            val dest = File(dir, "pulito-${System.currentTimeMillis()}.flac")
            check(flac.renameTo(dest)) { "can't keep the file" }
            return Esito(dest, dB)
        } finally {
            pcm.delete(); pulito.delete(); flac.delete()
        }
    }

    /** Il suono intero in PCM 16 bit intercalato; ritorna hz e canali (null se fermato). */
    private fun decodifica(): Pair<Int, Int>? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(uri), null)
            val traccia = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no sound in the file")
            ex.selectTrack(traccia)
            val fmt = ex.getTrackFormat(traccia)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var canali = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var hz = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            val info = MediaCodec.BufferInfo()
            var finitoIn = false
            // il «priming» del codificatore (AAC 1024 campioni in testa): il lettore lo salta, qui pure,
            // se no la copia pulita arriva 23 ms in ritardo (telefono di prova 15/11: 1024 campioni misurati)
            var saltaTesta = if (fmt.containsKey("encoder-delay")) fmt.getInteger("encoder-delay") else 0
            val coda = if (fmt.containsKey("encoder-padding")) fmt.getInteger("encoder-padding") else 0
            BufferedOutputStream(FileOutputStream(pcm), 1 shl 16).use { o ->
                while (true) {
                    if (fermato()) return null
                    if (!finitoIn) {
                        val i = c.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                            if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finitoIn = true }
                            else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val k = c.dequeueOutputBuffer(info, 10_000)
                    if (k == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        canali = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        hz = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (k >= 0) {
                        val buf = c.getOutputBuffer(k)!!
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val b = buf.slice().order(ByteOrder.nativeOrder())
                        val campioni = if (float) info.size / 4 else info.size / 2
                        // Android applica la lista di montaggio dell'MP4 dando tempi negativi al priming
                        if (info.presentationTimeUs < 0 && saltaTesta == 0)
                            saltaTesta = Math.round(-info.presentationTimeUs * hz / 1_000_000.0).toInt().coerceAtMost(campioni / canali)
                        val via = minOf(saltaTesta * canali, campioni)
                        saltaTesta -= via / canali
                        val out = ByteBuffer.allocate((campioni - via) * 2).order(ByteOrder.LITTLE_ENDIAN)
                        if (float) { val fb = b.asFloatBuffer(); for (j in via until campioni) out.putShort((fb.get(j).coerceIn(-1f, 1f) * 32767f).toInt().toShort()) }
                        else { val sb = b.asShortBuffer(); for (j in via until campioni) out.putShort(sb.get(j)) }
                        o.write(out.array())
                        c.releaseOutputBuffer(k, false)
                        if (durUs > 0) avanti((info.presentationTimeUs * 25 / durUs).toInt().coerceIn(0, 25))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(canali in 1..2) { "$canali channels" }
            // e la coda di riempimento, se il file la dichiara
            if (coda > 0) RandomAccessFile(pcm, "rw").use { it.setLength(maxOf(0L, it.length() - coda.toLong() * canali * 2)) }
            return hz to canali
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
