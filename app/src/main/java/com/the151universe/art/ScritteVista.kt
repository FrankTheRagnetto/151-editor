package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * I testi e i disegni sopra il video nell'anteprima: si vedono quelli del momento, disegnati con la
 * stessa ricetta dell'export ([ManoDisegno], [Disegno.testi]) dentro il riquadro del video. Tocco = scegli,
 * trascina = sposta, pizzica = misura, doppio tocco = riscrivi. I tocchi fuori dai testi passano.
 */
class ScritteVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    interface Ascoltatore {
        fun scritta(i: Int)
        fun scrittaSposta(i: Int, x: Float, y: Float, fine: Boolean)
        fun scrittaMisura(i: Int, misura: Float, fine: Boolean)
        fun scrittaRiscrivi(i: Int)
    }

    var ascoltatore: Ascoltatore? = null
    private val dp = resources.displayMetrics.density
    private var scritte: List<Scritta> = emptyList()
    private var t = 0L
    /** la misura del video finito: i testi stanno in proporzione a lei */
    private var vw = 1080; private var vh = 1920
    var scelta = -1
        set(v) { field = v; invalidate() }

    private var s = 1f; private var ox = 0f; private var oy = 0f

    fun mostra(nuove: List<Scritta>, tempo: Long) {
        if (nuove === scritte && tempo == t) return
        val cambiate = nuove !== scritte
        val prima = visibili()
        scritte = nuove; t = tempo
        // ridisegno solo se cambia qualcosa che si vede (l'orologio chiama ogni 33 ms)
        if (cambiate || visibili() != prima) invalidate()
    }

    fun misura(w: Int, h: Int) { if (w > 0 && h > 0) { vw = w; vh = h; invalidate() } }

    private fun visibili(): List<Int> = scritte.indices.filter { t >= scritte[it].inizioMs && t < scritte[it].fineMs }

    private fun misure() {
        s = min(width.toFloat() / vw, height.toFloat() / vh)
        ox = (width - vw * s) / 2f; oy = (height - vh * s) / 2f
    }

    private val tratteggio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
        pathEffect = DashPathEffect(floatArrayOf(8 * dp, 6 * dp), 0f)
    }

    override fun onDraw(c: Canvas) {
        if (scritte.isEmpty()) return
        misure()
        val vis = visibili()
        c.save()
        c.translate(ox, oy); c.scale(s, s)
        c.clipRect(0f, 0f, vw.toFloat(), vh.toFloat())
        // nell'ordine dei livelli (la riga 0 della corsia davanti a tutto), come nel file
        for (sc in Progetto.scritteDaDisegnare(scritte, t)) {
            ManoDisegno.disegna(c, sc.tratti, vw.toFloat(), vh.toFloat())
            Disegno.testi(context, c, listOf(sc.testo), vw.toFloat(), vh.toFloat())
        }
        c.restore()
        // il testo scelto: tratteggio anche se non e' il suo momento, per vedere dove sta
        scritte.getOrNull(scelta)?.let { sc ->
            if (scelta !in vis) {
                c.save(); c.translate(ox, oy); c.scale(s, s)
                c.saveLayerAlpha(0f, 0f, vw.toFloat(), vh.toFloat(), 110)
                ManoDisegno.disegna(c, sc.tratti, vw.toFloat(), vh.toFloat())
                Disegno.testi(context, c, listOf(sc.testo), vw.toFloat(), vh.toFloat())
                c.restore(); c.restore()
            }
            val q = schermo(sc)
            q.inset(-6 * dp, -6 * dp)
            c.drawRoundRect(q, 6 * dp, 6 * dp, tratteggio)
        }
    }

    /** Il fotogramma sullo schermo (per la penna: i tratti stanno in frazioni di lui). */
    fun cornice(): RectF? {
        if (width == 0 || height == 0) return null
        misure()
        return RectF(ox, oy, ox + vw * s, oy + vh * s)
    }

    private fun schermo(sc: Scritta): RectF {
        if (sc.disegno) {
            val b = Mano.ingombro(sc.tratti, vw.toFloat(), vh.toFloat()) ?: return RectF()
            return RectF(ox + b[0] * vw * s, oy + b[1] * vh * s, ox + b[2] * vw * s, oy + b[3] * vh * s)
        }
        val z = Disegno.spazio(context, sc.testo, vw.toFloat(), vh.toFloat())
        return RectF(ox + z.left * s, oy + z.top * s, ox + z.right * s, oy + z.bottom * s)
    }

    /** Il testo sotto il dito: quelli del momento, e il scelto anche fuori dal suo momento. */
    private fun sotto(x: Float, y: Float): Int {
        misure()
        // davanti per primi: la riga 0 della corsia vince il tocco
        val righe = Progetto.righeScritte(scritte)
        val candidati = visibili().sortedBy { righe[it] }.toMutableList()
        if (scelta in scritte.indices && scelta !in candidati) candidati.add(0, scelta)
        for (i in candidati) {
            // i disegni non si trascinano col dito (si scelgono dalla timeline): il tocco passa ai testi
            if (scritte[i].disegno) continue
            val q = schermo(scritte[i]); q.inset(-14 * dp, -14 * dp)
            if (q.contains(x, y)) return i
        }
        return -1
    }

    private var preso = -1
    private var x0 = 0f; private var y0 = 0f; private var tx0 = 0f; private var ty0 = 0f
    private var mosso = false
    private var pizzico = false
    private var misura0 = 0f

    private val doppio = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val i = sotto(e.x, e.y)
            if (i >= 0) { ascoltatore?.scrittaRiscrivi(i); return true }
            return false
        }
    })

    private val pizza = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            val sc = scritte.getOrNull(scelta)?.takeIf { !it.disegno } ?: return false
            pizzico = true; misura0 = sc.testo.misura; preso = -1
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            misura0 = (misura0 * d.scaleFactor).coerceIn(Testo.MISURA_MIN, Testo.MISURA_MAX)
            ascoltatore?.scrittaMisura(scelta, misura0, false)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { ascoltatore?.scrittaMisura(scelta, misura0, true) }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            pizzico = false; mosso = false
            preso = sotto(e.x, e.y)
            // niente testo sotto e nessuno scelto: il tocco non e' nostro
            if (preso < 0 && scelta < 0) return false
            x0 = e.x; y0 = e.y
            if (preso >= 0) {
                tx0 = scritte[preso].testo.x; ty0 = scritte[preso].testo.y
                if (preso != scelta) ascoltatore?.scritta(preso)
            }
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        doppio.onTouchEvent(e)
        pizza.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (preso >= 0 && !pizzico && e.pointerCount == 1) {
                if (!mosso && hypot(e.x - x0, e.y - y0) < 6 * dp) return true
                mosso = true
                ascoltatore?.scrittaSposta(preso, (tx0 + (e.x - x0) / (vw * s)).coerceIn(0f, 1f), (ty0 + (e.y - y0) / (vh * s)).coerceIn(0f, 1f), false)
            }
            MotionEvent.ACTION_UP -> {
                // tocco sul video fuori dai testi = nessun testo scelto
                if (preso < 0 && !pizzico && hypot(e.x - x0, e.y - y0) < 10 * dp) ascoltatore?.scritta(-1)
                if (preso >= 0 && mosso && !pizzico) {
                    ascoltatore?.scrittaSposta(preso, (tx0 + (e.x - x0) / (vw * s)).coerceIn(0f, 1f), (ty0 + (e.y - y0) / (vh * s)).coerceIn(0f, 1f), true)
                }
                preso = -1
            }
            MotionEvent.ACTION_CANCEL -> preso = -1
        }
        return true
    }
}
