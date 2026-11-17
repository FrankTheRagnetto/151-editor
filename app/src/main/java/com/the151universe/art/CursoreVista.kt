package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Il cursore di 151 Editor, lo stesso ovunque (volume, velocità, durata delle transizioni…):
 * una pillola lunga con «−» e «+» DENTRO le estremità (tocco = un passo, tenuto = passi di fila)
 * e in mezzo la corsa, colorata da sinistra fino al pallino: più è lunga, meno è nervosa. La tacca segna il [normale] (100%, 1×);
 * vicino alle [calamite] il pallino si attacca, con un colpetto nella mano.
 * Non decide niente: dice all'[ascoltatore] il valore, e [fine] quando il dito si alza (anche da − e +).
 */
class CursoreVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    fun interface Ascoltatore { fun cambia(v: Float, fine: Boolean) }

    var ascoltatore: Ascoltatore? = null
    var da = 0f
    var a = 1f
    var normale: Float? = null
    var calamite = FloatArray(0)
    var soglia = 0f
    /** Il passo di − e +: dal valore di adesso e dal verso (-1, +1) al valore nuovo. */
    var passo: (Float, Int) -> Float = { v, verso -> v + verso * (a - da) / 40f }

    var valore = 0f
        set(v) { if (!toccando) { field = v; invalidate() } }
    /** Il dito è sopra: chi ridisegna da fuori non deve spostare il pallino. */
    var toccando = false
        private set

    private val dp = resources.displayMetrics.density
    private val zona = 34 * dp
    private fun col(id: Int) = ContextCompat.getColor(context, id)
    private val pFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.riga) }
    private val pCorsa = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val pPieno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.accento) }
    private val pPallino = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo); setShadowLayer(3 * dp, 0f, 1 * dp, 0x99000000.toInt()) }
    private val pSegno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo); strokeWidth = 2 * dp; strokeCap = Paint.Cap.ROUND }
    private val pTacca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt() }
    private val r = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // l'ombra del pallino
        isClickable = true
    }

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(resolveSize((160 * dp).toInt(), w), resolveSize((40 * dp).toInt(), h))

    private val x0 get() = zona + 8 * dp
    private val x1 get() = width - zona - 8 * dp
    private fun xDi(v: Float) = x0 + ((v - da) / (a - da)).coerceIn(0f, 1f) * (x1 - x0)
    private fun vDi(x: Float) = da + ((x - x0) / (x1 - x0)).coerceIn(0f, 1f) * (a - da)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val h = minOf(height.toFloat(), 36 * dp)
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        c.drawRoundRect(r, h / 2, h / 2, pFondo)
        // − e +
        val m = 6 * dp
        c.drawLine(zona / 2 - m, cy, zona / 2 + m, cy, pSegno)
        val xp = width - zona / 2
        c.drawLine(xp - m, cy, xp + m, cy, pSegno); c.drawLine(xp, cy - m, xp, cy + m, pSegno)
        // la corsa: colorata da sinistra fino al pallino, grigia dopo; la tacca del normale
        r.set(x0, cy - 2.5f * dp, x1, cy + 2.5f * dp)
        c.drawRoundRect(r, 2.5f * dp, 2.5f * dp, pCorsa)
        val xv = xDi(valore)
        r.set(x0, cy - 2.5f * dp, xv, cy + 2.5f * dp)
        c.drawRoundRect(r, 2.5f * dp, 2.5f * dp, pPieno)
        normale?.let { val xn = xDi(it); c.drawRect(xn - 1 * dp, cy - 7 * dp, xn + 1 * dp, cy + 7 * dp, pTacca) }
        c.drawCircle(xv, cy, 9 * dp, pPallino)
    }

    // ---------------------------------------------------------------- dita

    private val main = Handler(Looper.getMainLooper())
    private var verso = 0
    private val giro = object : Runnable { override fun run() { fai(verso); main.postDelayed(this, 80) } }
    private var attaccato: Float? = null

    private fun fai(v: Int) {
        val n = passo(valore, v).coerceIn(da, a)
        if (n == valore) return
        // la calamita si sente anche coi passi: un colpetto quando si arriva su un punto tondo
        if (calamite.any { abs(it - n) < 1e-4f }) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val prima = toccando; toccando = false; valore = n; toccando = prima
        // tenuto premuto: si vede subito, entra in Annulla/Rifai una volta sola quando si lascia
        ascoltatore?.cambia(n, false)
    }

    private fun trascina(x: Float, fine: Boolean) {
        var v = vDi(x)
        val c = if (calamite.isEmpty()) v else calamite.minByOrNull { abs(it - v) }!!.let { if (abs(it - v) <= soglia) it else v }
        if (c != v) {
            if (attaccato != c) { attaccato = c; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            v = c
        } else attaccato = null
        val prima = toccando; toccando = false; valore = v; toccando = prima
        ascoltatore?.cambia(v, fine)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                verso = when { e.x < zona + 4 * dp -> -1; e.x > width - zona - 4 * dp -> 1; else -> 0 }
                if (verso != 0) { fai(verso); main.postDelayed(giro, 380) }
                else { toccando = true; trascina(e.x, false) }
            }
            MotionEvent.ACTION_MOVE -> if (verso == 0 && toccando) trascina(e.x, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(giro)
                // il dito è già via: se chi ascolta corregge il valore (Speed fermata dal suono dopo), il pallino va lì
                if (verso == 0 && toccando) { toccando = false; trascina(e.x, true) }
                else if (verso != 0) ascoltatore?.cambia(valore, true)
                verso = 0
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() { main.removeCallbacks(giro); super.onDetachedFromWindow() }
}
