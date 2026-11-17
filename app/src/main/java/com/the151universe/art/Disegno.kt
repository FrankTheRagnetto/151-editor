package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/**
 * Come si disegna la foto modificata. La STESSA funzione serve l'anteprima sullo schermo
 * e il file salvato: quello che si vede e' quello che esce.
 */
object Disegno {
    /**
     * La matrice che applica specchio (prima) e [giri] quarti di giro orari a una foto [w]×[h],
     * gia' spostata perche' il risultato parta da (0,0).
     */
    fun matrice(w: Int, h: Int, giri: Int, specchio: Boolean): Matrix {
        val m = Matrix()
        if (specchio) { m.postScale(-1f, 1f); m.postTranslate(w.toFloat(), 0f) }
        m.postRotate(90f * (giri.mod(4)))
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        return m
    }

    /** Una nuova foto girata e specchiata (quella data resta com'e'). Identita' = la stessa. */
    fun orienta(b: Bitmap, giri: Int, specchio: Boolean): Bitmap {
        if (giri.mod(4) == 0 && !specchio) return b
        val (w, h) = Foto.quadro(b.width, b.height, giri)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, matrice(b.width, b.height, giri, specchio), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private val pennello = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * La foto [b] (gia' girata e colorata, misura = il quadro) raddrizzata di [gradi] e
     * ingrandita quanto basta, nel sistema del quadro (0,0)-(W,H) del [c].
     */
    fun foto(c: Canvas, b: Bitmap, gradi: Float) {
        val w = b.width.toFloat(); val h = b.height.toFloat()
        c.save()
        if (gradi != 0f) {
            val s = Foto.zoomRaddrizza(w, h, gradi)
            c.translate(w / 2f, h / 2f); c.rotate(gradi); c.scale(s, s); c.translate(-w / 2f, -h / 2f)
        }
        c.drawBitmap(b, 0f, 0f, pennello)
        c.restore()
    }

    private fun pittore(ctx: Context, t: Testo, w: Float, h: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Caratteri.di(ctx, t.font)
        textSize = (t.misura * minOf(w, h)).coerceAtLeast(1f)
        textAlign = Paint.Align.CENTER
    }

    /** Dove sta il testo [t] in un quadro [w]×[h] (riquadro compreso), per toccarlo e per disegnarlo. */
    fun spazio(ctx: Context, t: Testo, w: Float, h: Float): RectF {
        val p = pittore(ctx, t, w, h)
        val righe = t.testo.split('\n')
        val fm = p.fontMetrics
        val riga = fm.descent - fm.ascent
        val largo = righe.maxOf { p.measureText(it) }
        val alto = riga * righe.size
        val cx = t.x * w; val cy = t.y * h
        val pad = if (t.stile == Testo.RIQUADRO) p.textSize * 0.35f
            else p.textSize * maxOf(0.12f, if (t.stile == Testo.CONTORNO) t.spessore / 2f + 0.02f else 0f)
        return RectF(cx - largo / 2f - pad, cy - alto / 2f - pad, cx + largo / 2f + pad, cy + alto / 2f + pad)
    }

    /** Tutti i testi, nel sistema del quadro [w]×[h]. */
    fun testi(ctx: Context, c: Canvas, testi: List<Testo>, w: Float, h: Float) {
        for (t in testi) testo(ctx, c, t, w, h)
    }

    private fun testo(ctx: Context, c: Canvas, t: Testo, w: Float, h: Float) {
        if (t.testo.isBlank()) return
        val p = pittore(ctx, t, w, h)
        val righe = t.testo.split('\n')
        val fm = p.fontMetrics
        val riga = fm.descent - fm.ascent
        val cx = t.x * w
        val primaBase = t.y * h - riga * righe.size / 2f - fm.ascent
        var colore = t.colore
        if (t.stile == Testo.RIQUADRO) {
            val box = spazio(ctx, t, w, h)
            val r = p.textSize * 0.25f
            val (fondo, lettere) = Testo.riquadro(t.colore, t.secondo)
            c.drawRoundRect(box, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fondo })
            colore = lettere
        }
        if (t.stile == Testo.CONTORNO) {
            val bordo = Paint(p).apply {
                style = Paint.Style.STROKE
                strokeWidth = p.textSize * t.spessore
                strokeJoin = Paint.Join.ROUND
                color = Testo.coloreBordo(t.colore, t.secondo)
            }
            righe.forEachIndexed { i, s -> c.drawText(s, cx, primaBase + i * riga, bordo) }
        }
        p.color = colore
        if (t.stile == Testo.OMBRA) p.setShadowLayer(p.textSize * 0.08f, p.textSize * 0.03f, p.textSize * 0.04f, 0xAA000000.toInt())
        righe.forEachIndexed { i, s -> c.drawText(s, cx, primaBase + i * riga, p) }
    }
}
