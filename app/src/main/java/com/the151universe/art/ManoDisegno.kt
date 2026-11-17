package com.the151universe.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Come si disegnano i tratti a mano libera. La STESSA funzione serve lo schermo, il file della
 * foto, il video esportato, il PNG e il PDF del design: quello che si vede è quello che esce.
 * Il [Canvas] è già nel sistema del riquadro: (0,0)-([w],[h]).
 */
object ManoDisegno {
    // pennello e percorso nuovi a ogni chiamata: schermo, salvataggio della foto ed export del
    // video disegnano su fili diversi, nello stesso momento
    private fun pennello() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun disegna(c: Canvas, tratti: List<Tratto>, w: Float, h: Float) {
        if (tratti.isEmpty()) return
        val pe = pennello(); val pa = Path()
        for (t in tratti) uno(c, t, w, h, pe, pa)
    }

    /** Un tratto: una curva morbida che passa a metà fra un punto e l'altro, un solo colpo (l'evidenziatore non si scurisce dove si incrocia da solo). */
    fun uno(c: Canvas, t: Tratto, w: Float, h: Float, pennello: Paint = pennello(), percorso: Path = Path()) {
        val n = t.quanti
        if (n == 0) return
        pennello.strokeWidth = (Mano.larghezza(t) * minOf(w, h)).coerceAtLeast(0.5f)
        pennello.color = if (t.tipo == Mano.EVIDENZIATORE) (t.colore and 0x00FFFFFF) or (Mano.ALFA_EVIDENZIATORE shl 24) else t.colore
        val p = t.punti
        percorso.rewind()
        percorso.moveTo(p[0] * w, p[1] * h)
        if (n == 1) {
            // un tocco solo = un punto tondo
            percorso.lineTo(p[0] * w + 0.01f, p[1] * h)
        } else {
            for (i in 1 until n - 1) {
                val x = p[2 * i] * w; val y = p[2 * i + 1] * h
                val nx = p[2 * i + 2] * w; val ny = p[2 * i + 3] * h
                percorso.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
            }
            percorso.lineTo(p[2 * n - 2] * w, p[2 * n - 1] * h)
        }
        c.drawPath(percorso, pennello)
    }
}
