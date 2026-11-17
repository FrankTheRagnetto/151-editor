package com.the151universe.art

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * L'onda del suono dei blocchi della timeline (clip e musica): un picco 0..1 ogni [PASSO_MS] ms.
 * Un lavoratore in sottofondo decodifica il suono UNA volta e lo tiene su disco
 * (`cache/onde`, scritto atomico): la seconda volta l'onda c'è subito. [pronto] arriva sul
 * thread principale. Un file senza suono = onda vuota (non si disegna niente).
 * I conti puri ([picchi], [massimo]) si provano sul PC (OndeTest).
 */
class Onde(private val ctx: Context, private val pronto: () -> Unit) {
    companion object {
        const val PASSO_MS = 20L
        private const val VERSIONE = 1

        /**
         * Mette in [out] il picco di ogni passo dei campioni [pcm] (16 bit, [canali] intrecciati,
         * [hz] al secondo) che cominciano a [inizioUs]. Il picco resta il più alto già visto.
         */
        fun picchi(pcm: ShortArray, n: Int, canali: Int, hz: Int, inizioUs: Long, out: FloatArray) {
            if (canali <= 0 || hz <= 0) return
            val fotogrammi = n / canali
            for (f in 0 until fotogrammi) {
                val ms = inizioUs / 1000 + f * 1000L / hz
                val k = (ms / PASSO_MS).toInt()
                if (k < 0 || k >= out.size) continue
                var p = 0
                for (c in 0 until canali) p = max(p, abs(pcm[f * canali + c].toInt()))
                val v = p / 32768f
                if (v > out[k]) out[k] = v
            }
        }

        /** Il picco più alto fra [daMs] e [aMs] del file; la radice lo rende visibile anche quando è basso. */
        fun massimo(onda: FloatArray, daMs: Long, aMs: Long): Float {
            if (onda.isEmpty()) return 0f
            val a = (daMs / PASSO_MS).toInt().coerceIn(0, onda.size - 1)
            val b = (aMs / PASSO_MS).toInt().coerceIn(a, onda.size - 1)
            var m = 0f
            for (k in a..b) if (onda[k] > m) m = onda[k]
            return sqrt(m.coerceIn(0f, 1f))
        }
    }

    private val pronte = HashMap<String, FloatArray>()
    private val chieste = HashSet<String>()
    private val coda = ArrayDeque<String>()
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var vivo = true

    private val lavoratore = Thread {
        while (true) {
            val uri = synchronized(lock) {
                while (vivo && coda.isEmpty()) lock.wait()
                if (vivo) coda.removeFirst() else null
            } ?: break
            val onda = try { daDisco(uri) ?: decodifica(uri)?.also { suDisco(uri, it) } } catch (_: Exception) { null }
                ?: FloatArray(0)
            main.post { if (vivo) { pronte[uri] = onda; pronto() } }
        }
    }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }

    /** L'onda se c'è; se no la chiede e torna null (arriva con [pronto]). Thread principale. */
    fun prendi(uri: String): FloatArray? {
        pronte[uri]?.let { return it }
        if (chieste.add(uri)) synchronized(lock) { coda.addLast(uri); lock.notify() }
        return null
    }

    fun chiudi() {
        vivo = false
        synchronized(lock) { lock.notify() }
    }

    // ---------------------------------------------------------------- disco

    private fun file(uri: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.cacheDir, "onde"), "$h.v$VERSIONE")
    }

    /** Un byte per passo (0..255): pochi KB anche per un video lungo. */
    private fun daDisco(uri: String): FloatArray? {
        val f = file(uri)
        if (!f.isFile) return null
        val b = f.readBytes()
        return FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f }
    }

    private fun suDisco(uri: String, onda: FloatArray) {
        val f = file(uri)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".${android.os.Process.myPid()}.tmp")
        tmp.writeBytes(ByteArray(onda.size) { (onda[it].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte() })
        if (!tmp.renameTo(f)) tmp.delete()
    }

    // ---------------------------------------------------------------- decodifica

    private fun decodifica(uri: String): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(uri), null)
            val traccia = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            ex.selectTrack(traccia)
            val fmt = ex.getTrackFormat(traccia)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else return null
            val out = FloatArray((durUs / 1000 / PASSO_MS + 1).toInt().coerceIn(1, 2_000_000))
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var canali = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var hz = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var float = false
            val info = MediaCodec.BufferInfo()
            var finitoIn = false
            var pcm = ShortArray(0)
            while (vivo) {
                if (!finitoIn) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finitoIn = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    canali = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    hz = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val b = buf.slice().order(ByteOrder.nativeOrder())
                    val n = if (float) info.size / 4 else info.size / 2
                    if (pcm.size < n) pcm = ShortArray(n)
                    if (float) { val fb = b.asFloatBuffer(); for (k in 0 until n) pcm[k] = (fb.get(k).coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                    else b.asShortBuffer().get(pcm, 0, n)
                    picchi(pcm, n, canali, hz, info.presentationTimeUs, out)
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return if (vivo) out else null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
