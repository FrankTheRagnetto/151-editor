package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Il vetro su cui si disegna, lo stesso per ogni editor: si mette SOPRA l'anteprima.
 * Spenta, lascia passare ogni tocco. Accesa, un dito disegna (o cancella, con la gomma);
 * un secondo dito annulla il tratto a metà. Mostra solo il tratto mentre nasce: quelli già fatti
 * li disegna l'editor con [ManoDisegno], nella sua anteprima, come nel file.
 *
 * L'editor dice soltanto DOVE sta il riquadro sullo schermo ([cornice]) e dove si vede ([taglio]),
 * e riceve l'inchiostro nuovo in [Ascoltatore.inchiostro].
 */
class LavagnaVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    fun interface Ascoltatore {
        /** I tratti dopo il dito. [fine] = il dito si è alzato: entra in Annulla/Rifai. */
        fun inchiostro(tratti: List<Tratto>, fine: Boolean)
    }

    var ascoltatore: Ascoltatore? = null
    /** Il riquadro (0..1) sullo schermo, in pixel di questa vista; null = non si disegna. */
    var cornice: () -> RectF? = { null }
    /** Dove l'inchiostro si vede (la foto ritagliata); null = tutta la cornice. */
    var taglio: () -> RectF? = { null }
    /** I tratti che ci sono adesso: la gomma li toglie, la penna ne aggiunge uno. */
    var tratti: List<Tratto> = emptyList()

    var attiva = false
        set(v) { field = v; if (!v) lascia(); invalidate() }
    var strumento = Mano.PENNA
    var colore = 0xFFFFFFFF.toInt()
    var spessore = Mano.SPESSORI[1]

    private val dp = resources.displayMetrics.density
    private val punti = ArrayList<Float>()
    private var disegnando = false
    private var gommando = false
    private var gommati: List<Tratto> = emptyList()
    /** i tratti quando il dito è sceso: l'editor può cambiare [tratti] a metà gesto (anteprima) */
    private var base: List<Tratto> = emptyList()
    private var ux = 0f; private var uy = 0f
    private var dito: Pair<Float, Float>? = null
    private val anello = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = 0xFFFFFFFF.toInt() }
    private val anelloSotto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * dp; color = 0x88000000.toInt() }

    /** Il raggio della gomma, in pixel dello schermo: abbastanza per prendere un tratto col dito. */
    private val raggio get() = 14 * dp

    private fun lascia() {
        punti.clear(); disegnando = false; gommando = false; dito = null
    }

    private fun tratto() = Tratto(punti.toFloatArray(), colore, spessore, if (strumento == Mano.EVIDENZIATORE) Mano.EVIDENZIATORE else Mano.PENNA)

    private fun aggiungi(x: Float, y: Float, c: RectF) {
        val fx = (x - c.left) / c.width(); val fy = (y - c.top) / c.height()
        val n = punti.size
        if (n >= Mano.PUNTI_MAX * 2) return
        if (n >= 2 && !Mano.lontano(punti[n - 2], punti[n - 1], fx, fy, 1.5f * dp, c.width(), c.height())) return
        punti += fx; punti += fy
    }

    private fun gomma(x: Float, y: Float, c: RectF) {
        val fx = (x - c.left) / c.width(); val fy = (y - c.top) / c.height()
        val n = Mano.cancella(gommati, ux, uy, fx, fy, raggio, c.width(), c.height())
        ux = fx; uy = fy
        if (n !== gommati) { gommati = n; ascoltatore?.inchiostro(n, false) }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!attiva) return false
        val c = cornice()?.takeIf { it.width() > 0f && it.height() > 0f } ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lascia()
                parent?.requestDisallowInterceptTouchEvent(true)
                if (strumento == Mano.GOMMA) {
                    gommando = true; base = tratti; gommati = tratti
                    ux = (ev.x - c.left) / c.width(); uy = (ev.y - c.top) / c.height()
                    dito = ev.x to ev.y
                    gomma(ev.x, ev.y, c)
                } else {
                    disegnando = true
                    aggiungi(ev.x, ev.y, c)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // un secondo dito: il tratto a metà non conta (la gomma rimette com'era)
                if (gommando && gommati !== base) ascoltatore?.inchiostro(base, false)
                lascia(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (disegnando) {
                    for (k in 0 until ev.historySize) aggiungi(ev.getHistoricalX(k), ev.getHistoricalY(k), c)
                    aggiungi(ev.x, ev.y, c)
                    invalidate()
                } else if (gommando) {
                    for (k in 0 until ev.historySize) gomma(ev.getHistoricalX(k), ev.getHistoricalY(k), c)
                    gomma(ev.x, ev.y, c)
                    dito = ev.x to ev.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (disegnando && punti.size >= 2) {
                    val t = tratto()
                    lascia()
                    ascoltatore?.inchiostro(tratti + t, true)
                } else if (gommando) {
                    val n = gommati
                    lascia()
                    if (n !== base) ascoltatore?.inchiostro(n, true)
                }
                lascia(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gommando && gommati !== base) ascoltatore?.inchiostro(base, false)
                lascia(); invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (!attiva) return
        val cor = cornice() ?: return
        if (disegnando && punti.size >= 2) {
            c.save()
            taglio()?.let { c.clipRect(it) } ?: c.clipRect(cor)
            c.translate(cor.left, cor.top)
            ManoDisegno.uno(c, tratto(), cor.width(), cor.height())
            c.restore()
        }
        dito?.let { (x, y) ->
            c.drawCircle(x, y, raggio, anelloSotto)
            c.drawCircle(x, y, raggio, anello)
        }
    }
}
