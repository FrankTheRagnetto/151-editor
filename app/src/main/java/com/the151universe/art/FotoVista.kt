package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * La foto sullo schermo. Fuori dal ritaglio si vede il risultato (ritagliato, raddrizzato,
 * con i testi); nel ritaglio si vede tutto il quadro col riquadro da trascinare.
 * I testi si toccano (sceglie), si trascinano, si pizzicano (misura), doppio tocco = riscrivi.
 * La vista non decide niente: dice all'[ascoltatore] cosa fa il dito, e mostra quello che riceve.
 */
class FotoVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    interface Ascoltatore {
        fun ritaglio(r: Ritaglio, fine: Boolean)
        fun testoScelto(id: Long?)
        fun testoSposta(id: Long, x: Float, y: Float, fine: Boolean)
        fun testoMisura(id: Long, misura: Float, fine: Boolean)
        fun testoRiscrivi(id: Long)
    }

    var ascoltatore: Ascoltatore? = null
    private var foto: Bitmap? = null
    private var m = Modifiche()
    var ritagliando = false
        set(v) { field = v; invalidate() }
    var scelto: Long? = null
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    // il quadro → lo schermo: x_schermo = ox + x_quadro * s
    private var s = 1f; private var ox = 0f; private var oy = 0f

    fun mostra(b: Bitmap?, mod: Modifiche) { foto = b; m = mod; invalidate() }

    /** Il quadro intero sullo schermo (per la penna): i tratti stanno in frazioni di lui. */
    fun cornice(): RectF? {
        val b = foto ?: return null
        if (!misure()) return null
        return RectF(ox, oy, ox + b.width * s, oy + b.height * s)
    }

    /** La parte del quadro che si vede (il ritaglio), sullo schermo. */
    fun taglio(): RectF? {
        val b = foto ?: return null
        if (!misure()) return null
        val r = m.ritaglio
        return RectF(ox + r.l * b.width * s, oy + r.t * b.height * s, ox + r.r * b.width * s, oy + r.b * b.height * s)
    }

    private fun misure(): Boolean {
        val b = foto ?: return false
        val w = b.width.toFloat(); val h = b.height.toFloat()
        if (width == 0 || height == 0) return false
        if (ritagliando) {
            val p = 28 * dp
            s = min((width - 2 * p) / w, (height - 2 * p) / h)
            ox = (width - w * s) / 2f; oy = (height - h * s) / 2f
        } else {
            val r = m.ritaglio
            val cw = r.largo * w; val ch = r.alto * h
            val p = 10 * dp
            s = min((width - 2 * p) / cw, (height - 2 * p) / ch)
            ox = width / 2f - (r.l * w + cw / 2f) * s
            oy = height / 2f - (r.t * h + ch / 2f) * s
        }
        return s > 0f
    }

    private val buio = Paint().apply { color = 0xB0000000.toInt() }
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE }
    private val terzi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt(); style = Paint.Style.STROKE }
    private val maniglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tratteggio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val b = foto ?: return
        if (!misure()) return
        val w = b.width.toFloat(); val h = b.height.toFloat()
        val r = m.ritaglio
        c.save()
        c.translate(ox, oy); c.scale(s, s)
        if (!ritagliando) c.clipRect(r.l * w, r.t * h, r.r * w, r.b * h)
        Disegno.foto(c, b, m.raddrizza)
        ManoDisegno.disegna(c, m.tratti, w, h)
        Disegno.testi(context, c, m.testi, w, h)
        c.restore()

        if (ritagliando) {
            val q = schermo(r)
            val tutto = RectF(ox, oy, ox + w * s, oy + h * s)
            c.drawRect(tutto.left, tutto.top, tutto.right, q.top, buio)
            c.drawRect(tutto.left, q.bottom, tutto.right, tutto.bottom, buio)
            c.drawRect(tutto.left, q.top, q.left, q.bottom, buio)
            c.drawRect(q.right, q.top, tutto.right, q.bottom, buio)
            terzi.strokeWidth = 1 * dp
            for (i in 1..2) {
                val x = q.left + q.width() * i / 3f; val y = q.top + q.height() * i / 3f
                c.drawLine(x, q.top, x, q.bottom, terzi); c.drawLine(q.left, y, q.right, y, terzi)
            }
            linea.strokeWidth = 1.5f * dp
            c.drawRect(q, linea)
            maniglia.strokeWidth = 4 * dp
            val l = min(22 * dp, min(q.width(), q.height()) / 3f)
            for ((x, y, sx, sy) in listOf(
                arrayOf(q.left, q.top, 1f, 1f), arrayOf(q.right, q.top, -1f, 1f),
                arrayOf(q.left, q.bottom, 1f, -1f), arrayOf(q.right, q.bottom, -1f, -1f),
            )) {
                c.drawLine(x, y, x + l * sx, y, maniglia); c.drawLine(x, y, x, y + l * sy, maniglia)
            }
            if (Foto.forma(m.rapporto, b.width, b.height) == null) {
                val cx = q.centerX(); val cy = q.centerY(); val mezzo = min(14 * dp, l / 2f)
                c.drawLine(cx - mezzo, q.top, cx + mezzo, q.top, maniglia)
                c.drawLine(cx - mezzo, q.bottom, cx + mezzo, q.bottom, maniglia)
                c.drawLine(q.left, cy - mezzo, q.left, cy + mezzo, maniglia)
                c.drawLine(q.right, cy - mezzo, q.right, cy + mezzo, maniglia)
            }
        } else {
            val id = scelto
            val t = m.testi.firstOrNull { it.id == id }
            if (t != null) {
                val z = Disegno.spazio(context, t, w, h)
                val q = RectF(ox + z.left * s, oy + z.top * s, ox + z.right * s, oy + z.bottom * s)
                q.inset(-6 * dp, -6 * dp)
                tratteggio.strokeWidth = 1.5f * dp
                tratteggio.pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
                c.drawRoundRect(q, 6 * dp, 6 * dp, tratteggio)
            }
        }
    }

    private fun schermo(r: Ritaglio): RectF {
        val b = foto!!
        return RectF(ox + r.l * b.width * s, oy + r.t * b.height * s, ox + r.r * b.width * s, oy + r.b * b.height * s)
    }

    // ------------------------------------------------------------------ il dito

    private var presa: Maniglia? = null
    private var ritaglioInizio = Ritaglio()
    private var x0 = 0f; private var y0 = 0f
    private var trascinato: Long? = null
    private var tx0 = 0f; private var ty0 = 0f
    private var pizzico = false
    private var misura0 = 0f
    private var mosso = false

    private fun testoSotto(x: Float, y: Float): Testo? {
        val b = foto ?: return null
        if (!misure()) return null
        for (t in m.testi.asReversed()) {
            val z = Disegno.spazio(context, t, b.width.toFloat(), b.height.toFloat())
            val q = RectF(ox + z.left * s, oy + z.top * s, ox + z.right * s, oy + z.bottom * s)
            q.inset(-14 * dp, -14 * dp)
            if (q.contains(x, y)) return t
        }
        return null
    }

    private val doppio = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (ritagliando) return false
            testoSotto(e.x, e.y)?.let { ascoltatore?.testoRiscrivi(it.id); return true }
            return false
        }
    })

    private val pizza = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (ritagliando) return false
            val t = m.testi.firstOrNull { it.id == scelto } ?: return false
            pizzico = true; misura0 = t.misura; trascinato = null
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val id = scelto ?: return false
            misura0 = (misura0 * d.scaleFactor).coerceIn(Testo.MISURA_MIN, Testo.MISURA_MAX)
            ascoltatore?.testoMisura(id, misura0, false)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            scelto?.let { ascoltatore?.testoMisura(it, misura0, true) }
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = foto ?: return false
        if (!misure()) return false
        doppio.onTouchEvent(e)
        pizza.onTouchEvent(e)
        if (ritagliando) return ditoRitaglio(e, b)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pizzico = false; mosso = false
                x0 = e.x; y0 = e.y
                val t = testoSotto(e.x, e.y)
                trascinato = t?.id
                if (t != null) {
                    tx0 = t.x; ty0 = t.y
                    if (scelto != t.id) ascoltatore?.testoScelto(t.id)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val id = trascinato
                if (id != null && !pizzico && e.pointerCount == 1) {
                    if (!mosso && hypot(e.x - x0, e.y - y0) < 6 * dp) return true
                    mosso = true
                    val x = (tx0 + (e.x - x0) / (b.width * s)).coerceIn(0f, 1f)
                    val y = (ty0 + (e.y - y0) / (b.height * s)).coerceIn(0f, 1f)
                    ascoltatore?.testoSposta(id, x, y, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = trascinato
                if (id != null && mosso && !pizzico) {
                    val x = (tx0 + (e.x - x0) / (b.width * s)).coerceIn(0f, 1f)
                    val y = (ty0 + (e.y - y0) / (b.height * s)).coerceIn(0f, 1f)
                    ascoltatore?.testoSposta(id, x, y, true)
                } else if (id == null && !pizzico && hypot(e.x - x0, e.y - y0) < 10 * dp && scelto != null) {
                    ascoltatore?.testoScelto(null) // tocco sul vuoto = nessun testo scelto
                }
                trascinato = null
            }
            MotionEvent.ACTION_CANCEL -> trascinato = null
        }
        return true
    }

    private fun ditoRitaglio(e: MotionEvent, b: Bitmap): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y
                ritaglioInizio = m.ritaglio
                presa = quale(e.x, e.y, b)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val p = presa ?: return true
                if (e.pointerCount > 1) return true
                val dx = (e.x - x0) / (b.width * s); val dy = (e.y - y0) / (b.height * s)
                val f = Foto.forma(m.rapporto, b.width, b.height)
                val r = Foto.trascina(ritaglioInizio, p, dx, dy, b.width, b.height, f)
                val fine = e.actionMasked == MotionEvent.ACTION_UP
                ascoltatore?.ritaglio(r, fine)
                if (fine) presa = null
            }
            MotionEvent.ACTION_CANCEL -> { if (presa != null) ascoltatore?.ritaglio(m.ritaglio, true); presa = null }
        }
        return true
    }

    /** Quale pezzo del riquadro c'e' sotto il dito: angoli prima, poi lati, poi dentro. */
    private fun quale(x: Float, y: Float, b: Bitmap): Maniglia? {
        val q = schermo(m.ritaglio)
        val raggio = 32 * dp
        val angoli = listOf(
            Maniglia.NO to (q.left to q.top), Maniglia.NE to (q.right to q.top),
            Maniglia.SO to (q.left to q.bottom), Maniglia.SE to (q.right to q.bottom),
        )
        val vicino = angoli.minByOrNull { (_, p) -> hypot(x - p.first, y - p.second) }!!
        if (hypot(x - vicino.second.first, y - vicino.second.second) <= raggio) return vicino.first
        if (Foto.forma(m.rapporto, b.width, b.height) == null) {
            val bordo = 24 * dp
            val dentroX = x > q.left && x < q.right; val dentroY = y > q.top && y < q.bottom
            if (dentroX && abs(y - q.top) < bordo) return Maniglia.N
            if (dentroX && abs(y - q.bottom) < bordo) return Maniglia.S
            if (dentroY && abs(x - q.left) < bordo) return Maniglia.O
            if (dentroY && abs(x - q.right) < bordo) return Maniglia.E
        }
        return if (q.contains(x, y)) Maniglia.SPOSTA else null
    }
}
