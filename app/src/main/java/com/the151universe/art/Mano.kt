package com.the151universe.art

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Il disegno a mano libera, senza Android: UNO strumento che ogni editor usa (foto, video, design).
 *
 * Un tratto vive in un «riquadro» qualunque — il quadro della foto, il fotogramma del video, la
 * scatola di un oggetto del design — in frazioni 0..1. Lo spessore è una frazione del lato CORTO
 * del riquadro: lo schermo piccolo e il file grande coincidono, e un riquadro girato di un quarto
 * tiene lo stesso spessore. Chi usa la penna decide solo dove sta il riquadro.
 *
 * Tutto puro, così si prova sul PC (app/src/test/ManoTest.kt). Il disegno vero: [ManoDisegno];
 * il dito: [LavagnaVista]; i bottoni: [PannelloMano].
 */
class Tratto(
    /** x0,y0,x1,y1… in frazioni del riquadro */
    val punti: FloatArray,
    val colore: Int,
    /** frazione del lato corto del riquadro */
    val spessore: Float,
    val tipo: Int = Mano.PENNA,
) {
    val quanti get() = punti.size / 2

    fun copia(punti: FloatArray = this.punti, colore: Int = this.colore, spessore: Float = this.spessore) =
        Tratto(punti, colore, spessore, tipo)

    override fun equals(other: Any?): Boolean = other is Tratto && other.colore == colore &&
        other.spessore == spessore && other.tipo == tipo && other.punti.contentEquals(punti)

    override fun hashCode(): Int = ((punti.contentHashCode() * 31 + colore) * 31 + spessore.hashCode()) * 31 + tipo

    override fun toString() = "Tratto(${quanti} punti, tipo $tipo)"
}

object Mano {
    /** I tipi di tratto. La gomma non è un tratto: toglie quelli che tocca. */
    const val PENNA = 0
    const val EVIDENZIATORE = 1
    const val GOMMA = 2

    /** Le quattro misure della punta, in frazioni del lato corto. */
    val SPESSORI = floatArrayOf(0.006f, 0.012f, 0.024f, 0.045f)
    /** L'evidenziatore è più largo della penna della stessa misura, e trasparente. */
    const val LARGO_EVIDENZIATORE = 2.5f
    const val ALFA_EVIDENZIATORE = 0x70
    /** Un tratto lunghissimo si ferma qui (un dito che non si alza mai non riempie la memoria). */
    const val PUNTI_MAX = 4000

    /** La larghezza vera, in frazioni del lato corto. */
    fun larghezza(t: Tratto) = if (t.tipo == EVIDENZIATORE) t.spessore * LARGO_EVIDENZIATORE else t.spessore

    /** Il punto nuovo vale la pena? Solo se dista almeno [minPx] pixel dall'ultimo (riquadro [w]×[h] pixel). */
    fun lontano(ax: Float, ay: Float, bx: Float, by: Float, minPx: Float, w: Float, h: Float) =
        hypot((bx - ax) * w, (by - ay) * h) >= minPx

    // ---------------------------------------------------------------- la gomma

    private fun distanzaSegmento(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val k = if (l2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + k * dx), py - (ay + k * dy))
    }

    /** Il punto (x,y) tocca il tratto? Tutto in pixel di un riquadro [w]×[h]; [raggio] = la gomma. */
    fun tocca(t: Tratto, x: Float, y: Float, raggio: Float, w: Float, h: Float): Boolean {
        val px = x * w; val py = y * h
        val soglia = raggio + larghezza(t) * min(w, h) / 2f
        val p = t.punti
        if (t.quanti == 1) return hypot(p[0] * w - px, p[1] * h - py) <= soglia
        for (i in 0 until t.quanti - 1) {
            val d = distanzaSegmento(px, py, p[2 * i] * w, p[2 * i + 1] * h, p[2 * i + 2] * w, p[2 * i + 3] * h)
            if (d <= soglia) return true
        }
        return false
    }

    /**
     * La gomma passa da (ax,ay) a (bx,by) (frazioni): toglie ogni tratto che tocca lungo la strada.
     * Il percorso si guarda a passi più corti del raggio: un dito veloce non salta un tratto sottile.
     */
    fun cancella(tratti: List<Tratto>, ax: Float, ay: Float, bx: Float, by: Float, raggio: Float, w: Float, h: Float): List<Tratto> {
        if (tratti.isEmpty()) return tratti
        val lungo = hypot((bx - ax) * w, (by - ay) * h)
        val passi = max(1, (lungo / max(1f, raggio / 2f)).toInt() + 1)
        val restano = tratti.filter { t ->
            (0..passi).none { k ->
                val f = k.toFloat() / passi
                tocca(t, ax + (bx - ax) * f, ay + (by - ay) * f, raggio, w, h)
            }
        }
        return if (restano.size == tratti.size) tratti else restano
    }

    // ---------------------------------------------------------------- girare e specchiare
    // Gli stessi conti dei testi della foto (Foto.gira, Foto.specchia): il disegno segue la foto.

    /** Un quarto di giro in senso orario. */
    fun gira(tratti: List<Tratto>): List<Tratto> = tratti.map { t ->
        val p = t.punti; val n = FloatArray(p.size)
        for (i in 0 until t.quanti) { n[2 * i] = 1f - p[2 * i + 1]; n[2 * i + 1] = p[2 * i] }
        t.copia(punti = n)
    }

    /** Specchio orizzontale. */
    fun specchia(tratti: List<Tratto>): List<Tratto> = tratti.map { t ->
        val p = t.punti; val n = p.copyOf()
        for (i in 0 until t.quanti) n[2 * i] = 1f - p[2 * i]
        t.copia(punti = n)
    }

    // ---------------------------------------------------------------- da un riquadro a un altro

    /**
     * Dove sta l'inchiostro, punta compresa: (l,t,r,b) in frazioni di un riquadro [w]×[h].
     * Niente tratti = null.
     */
    fun ingombro(tratti: List<Tratto>, w: Float, h: Float): FloatArray? {
        if (tratti.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val corto = min(w, h)
        for (tr in tratti) {
            val mx = larghezza(tr) * corto / 2f / w; val my = larghezza(tr) * corto / 2f / h
            for (i in 0 until tr.quanti) {
                val x = tr.punti[2 * i]; val y = tr.punti[2 * i + 1]
                l = min(l, x - mx); r = max(r, x + mx); t = min(t, y - my); b = max(b, y + my)
            }
        }
        return floatArrayOf(l, t, r, b)
    }

    /**
     * I tratti di un riquadro grande [w]×[h] riportati dentro un pezzo suo (l,t,r,b, frazioni):
     * le coordinate diventano frazioni del pezzo, lo spessore resta lo stesso a occhio.
     */
    fun dentro(tratti: List<Tratto>, l: Float, t: Float, r: Float, b: Float, w: Float, h: Float): List<Tratto> {
        val pw = (r - l) * w; val ph = (b - t) * h
        if (pw <= 0f || ph <= 0f) return tratti
        val k = min(w, h) / min(pw, ph)
        return tratti.map { tr ->
            val p = tr.punti; val n = FloatArray(p.size)
            for (i in 0 until tr.quanti) { n[2 * i] = (p[2 * i] - l) / (r - l); n[2 * i + 1] = (p[2 * i + 1] - t) / (b - t) }
            tr.copia(punti = n, spessore = tr.spessore * k)
        }
    }

    /** Il contrario di [dentro]: dal pezzo (l,t,r,b) di nuovo al riquadro grande [w]×[h]. */
    fun fuori(tratti: List<Tratto>, l: Float, t: Float, r: Float, b: Float, w: Float, h: Float): List<Tratto> {
        val pw = (r - l) * w; val ph = (b - t) * h
        if (pw <= 0f || ph <= 0f) return tratti
        val k = min(pw, ph) / min(w, h)
        return tratti.map { tr ->
            val p = tr.punti; val n = FloatArray(p.size)
            for (i in 0 until tr.quanti) { n[2 * i] = l + p[2 * i] * (r - l); n[2 * i + 1] = t + p[2 * i + 1] * (b - t) }
            tr.copia(punti = n, spessore = tr.spessore * k)
        }
    }

    // ---------------------------------------------------------------- salvataggio
    // Un tratto = «tipo;colore;spessore;x,y,x,y…» con le coordinate in decimillesimi interi
    // (niente virgole dei decimali che cambiano con la lingua del telefono). Più tratti: separati da «|».
    // Nessun TAB né a capo: entra in un campo delle righe a TAB di ogni editor.

    private const val SCALA = 10000f

    fun scrivi(t: Tratto): String = buildString {
        append(t.tipo).append(';').append(t.colore).append(';').append(t.spessore).append(';')
        for (i in t.punti.indices) {
            if (i > 0) append(',')
            append(Math.round(t.punti[i] * SCALA))
        }
    }

    /** Un tratto rotto = null, mai un crash. */
    fun leggi(s: String): Tratto? = try {
        val p = s.split(';')
        if (p.size != 4) null else {
            val tipo = p[0].toInt(); val colore = p[1].toInt(); val sp = p[2].toFloat()
            val n = p[3].split(',').map { it.toInt() / SCALA }
            if (tipo !in PENNA..EVIDENZIATORE || !sp.isFinite() || sp <= 0f || n.isEmpty() || n.size % 2 != 0) null
            else Tratto(n.take(PUNTI_MAX * 2).toFloatArray(), colore, sp.coerceAtMost(1f), tipo)
        }
    } catch (_: Exception) { null }

    fun scriviTutti(tratti: List<Tratto>): String = tratti.joinToString("|") { scrivi(it) }

    /** Tratti rotti si saltano: gli altri restano. */
    fun leggiTutti(s: String): List<Tratto> = if (s.isBlank()) emptyList() else s.split('|').mapNotNull { leggi(it) }
}
