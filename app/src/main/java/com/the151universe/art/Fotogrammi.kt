package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LongSparseArray
import android.util.LruCache

/**
 * I fotogrammi piccoli dei blocchi della timeline. Un solo lavoratore in sottofondo,
 * che prende per prima l'ULTIMA richiesta (quello che hai sotto gli occhi adesso);
 * un lettore aperto per video, riusato. [pronto] si chiama sul thread principale.
 *
 * Ogni fotogramma esce gia' QUADRATO col lato corto pari a [lato]: il quadratino della
 * timeline non lo ingrandisce (niente sfocato) e il disegno non deve ritagliare.
 * [prendi] e [segnaposto] si chiamano a ogni disegno: un fotogramma gia' pronto si trova
 * senza creare niente (niente stringhe, niente oggetti) — la cache vive sul thread principale.
 */
class Fotogrammi(private val ctx: Context, private val lato: Int, private val pronto: () -> Unit) {
    // --- solo thread principale ---
    private val perVideo = HashMap<String, LongSparseArray<Bitmap>>()
    private val ultimi = HashMap<String, Bitmap>()
    private val numeri = HashMap<String, Int>()
    private val video = ArrayList<String>()
    /** Il conto dei byte e chi esce per primo: un ottavo della memoria dell'app, al massimo 64 MB. */
    private val conto = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(8L shl 20, 64L shl 20).toInt()
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) perVideo[video[(key ushr 32).toInt()]]?.remove(key and 0xFFFFFFFFL)
        }
    }

    // --- condivisi col lavoratore, sotto [lock] ---
    private val coda = ArrayDeque<Pair<String, Long>>()
    private val inCoda = HashSet<String>()
    private val falliti = HashSet<String>()
    private val lock = Object()

    // --- solo lavoratore ---
    private val lettori = HashMap<String, MediaMetadataRetriever>()
    private val scatole = HashMap<String, Int>()

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var vivo = true

    private val lavoratore = Thread {
        while (true) {
            val prossimo = synchronized(lock) {
                while (vivo && coda.isEmpty()) lock.wait()
                if (vivo) coda.removeLast() else null
            } ?: break
            val (uri, ms) = prossimo
            val k = chiave(uri, ms)
            val b = try { fotogramma(uri, ms) } catch (_: Exception) { null }
            synchronized(lock) {
                inCoda.remove(k)
                if (b == null) falliti.add(k)
            }
            if (b != null) main.post { if (vivo) { metti(uri, ms, b); pronto() } }
        }
        // i lettori li usa solo questo thread: li chiude lui, uscendo
        lettori.values.forEach { try { it.release() } catch (_: Exception) {} }
        lettori.clear()
    }.apply { isDaemon = true; start() }

    private fun chiave(uri: String, ms: Long) = "$uri|$ms"

    private fun lettore(uri: String): MediaMetadataRetriever? = lettori[uri] ?: try {
        MediaMetadataRetriever().also { it.setDataSource(ctx, Uri.parse(uri)); lettori[uri] = it }
    } catch (_: Exception) { null }

    /** Solo sul lavoratore: il fotogramma col lato corto = [lato], ritagliato quadrato al centro. */
    private fun fotogramma(uri: String, ms: Long): Bitmap? {
        val r = lettore(uri) ?: return null
        // la scatola in cui farlo stare perche' il lato CORTO sia [lato] (la rotazione non cambia il lato corto)
        val scatola = scatole.getOrPut(uri) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) (lato.toLong() * maxOf(w, h) / minOf(w, h)).toInt() else lato * 2
        }
        val b = Archivio.piccolo(r, ms, scatola) ?: return null
        if (b.width == b.height) return b
        val s = minOf(b.width, b.height)
        val q = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        if (q !== b) b.recycle()
        return q
    }

    /** Thread principale: il fotogramma arrivato entra nella cache. */
    private fun metti(uri: String, ms: Long, b: Bitmap) {
        val n = numeri.getOrPut(uri) { video.add(uri); video.size - 1 }
        perVideo.getOrPut(uri) { LongSparseArray() }.put(ms, b)
        ultimi[uri] = b
        conto.put((n.toLong() shl 32) or (ms and 0xFFFFFFFFL), b)
    }

    /** Il fotogramma se c'e' gia'; se no lo chiede e torna null (arriva con [pronto]). Thread principale. */
    fun prendi(uri: String, ms: Long): Bitmap? {
        perVideo[uri]?.get(ms)?.let { return it }
        val k = chiave(uri, ms)
        synchronized(lock) {
            if (k in inCoda || k in falliti) return null
            inCoda.add(k)
            coda.addLast(uri to ms)
            while (coda.size > 60) inCoda.remove(coda.removeFirst().let { chiave(it.first, it.second) })
            lock.notify()
        }
        return null
    }

    /** L'ultimo fotogramma arrivato dello stesso video: si mostra al posto del vuoto finche' arriva quello giusto. */
    fun segnaposto(uri: String): Bitmap? = ultimi[uri]

    fun chiudi() {
        vivo = false
        synchronized(lock) { lock.notify() }
    }
}
