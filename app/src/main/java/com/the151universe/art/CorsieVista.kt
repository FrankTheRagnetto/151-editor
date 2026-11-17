package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * La timeline del progetto Audio: le stesse mani della timeline del video ([Timeline]) su più
 * corsie di suoni, una sopra l'altra, alte quanto lo schermo. A sinistra il
 * numero di ogni corsia: toccato apre Mute/Solo/volume della corsia; dice se è muta o in solo.
 * La riga bianca fissa al centro dice dove sei; trascini per scorrere, pizzichi per lo zoom;
 * tocco su un blocco = lo scegli. Il blocco scelto ha due bordi che tagliano e il corpo che si
 * trascina (tempo e corsia); tenuto premuto = scegli e trascina. I bordi si attaccano a quelli
 * vicini con un colpetto; vicino ai lati la timeline scorre da sola. Col Loop acceso il pezzo che
 * si ripete è una fascia rosa, coi suoi due bordi da trascinare nel righello.
 */
class CorsieVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Ascoltatore {
        /** La riga è su [t] (ms del progetto), mossa dal dito. */
        fun scorri(t: Long)
        /** Il dito si è fermato (anche dopo una spinta). */
        fun fermo(t: Long)
        /** Scelto il pezzo [i] (-1 = niente). */
        fun scegli(i: Int)
        /** Bordo del pezzo [i] mosso di [delta] ms da quando il dito l'ha preso. */
        fun bordo(i: Int, sinistro: Boolean, delta: Long, finito: Boolean)
        /** Pezzo [i] trascinato di [delta] ms, verso la corsia [corsia]. */
        fun trascina(i: Int, delta: Long, corsia: Int, finito: Boolean)
        /** Toccato il numero della corsia [c]. */
        fun corsia(c: Int)
        /** Il dito ha spostato un bordo del loop: ora va da [a] a [b]. */
        fun loop(a: Long, b: Long)
    }

    var ascoltatore: Ascoltatore? = null
    private val d = resources.displayMetrics.density

    private var pezzi: List<Musica> = emptyList()
    private var regolazioni: List<Corsia> = emptyList()
    private var nCorsie = 1
    /** il pezzo scelto, -1 = niente */
    var scelto = -1
        private set
    /** la corsia aperta nella barra in basso (si vede col numero acceso), -1 = nessuna */
    var corsiaAperta = -1
        set(v) { field = v; invalidate() }
    /** il pezzo che si ripete col Loop acceso (ms del progetto), null = spento */
    var loop: LongRange? = null
        set(v) { field = v; invalidate() }
    /** La riga: ms del progetto. */
    var t = 0L
        private set
    private var resto = 0f
    /** Zoom: pixel per millisecondo. Partenza: 40 dp al secondo (i suoni sono più lunghi delle clip). */
    private var pxMs = 40 * d / 1000f
    val toccando get() = dito != Dito.NIENTE || !scroller.isFinished

    // --- misure: righello in alto, i numeri delle corsie a sinistra, poi le corsie fino in fondo ---
    private val righello = 24 * d
    private val testate = 44 * d
    private val centro get() = testate + (width - testate) / 2f
    private val maniglia = 16 * d
    private val presa = 28 * d
    private val fessura = 2 * d
    private val striscia = 18 * d
    /** mai una corsia più bassa di un pollice: oltre, le corsie scorrono su e giù */
    private val minimoCorsia = 48 * d
    private val spazioCorsie get() = height - righello - 2 * d
    /** l'altezza di una corsia: tutto lo spazio diviso per quelle visibili (almeno 3 di posto), mai sotto [minimoCorsia] */
    private val altezzaCorsia get() = Suono.altezzaCorsia(spazioCorsie, nCorsie, minimoCorsia)
    /** quanto sono scorse in su le corsie (px) */
    private var su = 0f
    private fun suMax() = Suono.scorrimentoMax(spazioCorsie, nCorsie, minimoCorsia)
    private fun cima(c: Int) = righello + 2 * d + c * altezzaCorsia - su
    private fun corsiaDi(y: Float) = ((y - righello - 2 * d + su) / altezzaCorsia).toInt().coerceIn(0, Suono.CORSIE_MAX - 1)

    private val onde = Onde(ctx) { massimi.clear(); invalidate() }
    /** il picco più alto di ogni suono: l'onda si disegna in scala a lui (una voce bassa si vede) */
    private val massimi = HashMap<String, Float>()

    // --- colori e pennelli (solo da colors.xml) ---
    private fun col(id: Int) = ContextCompat.getColor(context, id)
    private val coloreFondo = col(R.color.fondo)
    private val accento = col(R.color.accento)
    private val bianco = col(R.color.bianco)
    private val pLinea = Paint().apply { color = col(R.color.riga) }
    private val pBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.blocco_testo) }
    private val pColore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBloccoMuto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.blocco_musica) }
    private val pBordoBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.riga); style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val pOnda = Paint().apply { color = col(R.color.testo); alpha = 200 }
    private val pOndaMuta = Paint().apply { color = col(R.color.testo_piano); alpha = 110 }
    private val pScelto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val pManiglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento }
    private val pFreccia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco; style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pRiga = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco }
    private val pTacca = Paint().apply { color = col(R.color.testo_piano) }
    private val pTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo_piano); textSize = 11 * d; fontFeatureSettings = "tnum" }
    private val pNome = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo); textSize = 12 * d; isFakeBoldText = true }
    private val pDissolvenza = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA0000000.toInt() }
    private val pVuoto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo_piano); textSize = 14 * d; textAlign = Paint.Align.CENTER }
    private val pNumero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo_piano); textSize = 14 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pSegno = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pTestata = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.carta) }
    private val pFondoTestate = Paint().apply { color = coloreFondo }
    private val pTestataAperta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento; style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val pLoop = Paint().apply { color = accento; alpha = 34 }
    private val pLoopBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento }

    private val rBlocco = RectF()
    private val rSx = RectF()
    private val rDx = RectF()
    private val rTestata = RectF()
    private val frecce = Path()
    private val triangolo = Path()
    private val scritteRighello = HashMap<Long, String>()

    // --- il dito ---
    private enum class Dito { NIENTE, SCORRE, BORDO_SX, BORDO_DX, TRASCINA, LOOP_A, LOOP_B }
    private var dito = Dito.NIENTE
    private var ditoId = -1
    private var zoomato = false
    private var x0 = 0f
    private var mosso = false
    private val scroller = OverScroller(ctx)
    /** il pezzo preso dal dito, fisso per tutto il gesto (l'elenco si riordina mentre si muove) */
    private var preso = -1
    private var corsiaPresa = 0
    private var corsiaVoluta = -1
    private var t0 = 0L
    private var ultimoX = 0f; private var ultimoY = 0f
    private var bordiMobili = LongArray(0); private var fissi = LongArray(0)
    private var agganciato: Long? = null
    private var velBordo = 0f
    private var restoBordo = 0f
    private var scorreDaSola = false
    /** il verso del gesto di scorrimento, deciso al primo movimento: 0 = non ancora, 1 = tempo, 2 = corsie */
    private var asse = 0
    private var spintaSu = false
    private var velSu = 0f

    /** i segnalini: una riga dorata dal righello in giù, i suoni ci si attaccano */
    var segni: List<Long> = emptyList()

    fun imposta(nuovi: List<Musica>, sceltoNuovo: Int, corsie: List<Corsia>, segniNuovi: List<Long> = segni) {
        segni = segniNuovi
        pezzi = nuovi
        regolazioni = corsie
        nCorsie = Suono.corsieVisibili(pezzi)
        scelto = if (sceltoNuovo in pezzi.indices) sceltoNuovo else -1
        t = t.coerceIn(0, totale())
        // il suono scelto (appena messo, anche nella sesta corsia) si vede sempre intero
        val m = pezzi.getOrNull(scelto)
        if (m != null && !toccando && height > 0) {
            val alto = m.corsia * altezzaCorsia; val basso = alto + altezzaCorsia
            if (alto < su) su = alto else if (basso > su + spazioCorsie) su = basso - spazioCorsie
        }
        su = su.coerceIn(0f, suMax())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { su = su.coerceIn(0f, suMax()) }

    fun scegli(i: Int) { scelto = if (i in pezzi.indices) i else -1; invalidate() }

    /** La riga va a [nuovo] da fuori (il suono che va, Start/End); ignorato se chi lo usa sta toccando. */
    fun posizione(nuovo: Long) {
        if (toccando) return
        val n = nuovo.coerceIn(0, totale())
        if (n == t) return
        t = n
        invalidate()
    }

    /** «See all»: lo zoom che fa stare tutto il progetto sullo schermo, con la riga dove sta. */
    fun vediTutto() {
        val metà = (width - testate) / 2f - 12 * d
        val serve = max(t, totale() - t).coerceAtLeast(1000)
        pxMs = (metà / serve).coerceIn(0.5f * d / 1000f, 600 * d / 1000f)
        invalidate()
    }

    /** I bordi della parte che si vede (ms): il Loop nuovo parte da qui. */
    fun visibile(): LongRange {
        val a = (t - (centro - testate) / pxMs).toLong().coerceAtLeast(0)
        val b = (t + (width - centro) / pxMs).toLong()
        return a..b
    }

    /** L'onda già letta di un suono (per «Even out»), null = non ancora. */
    fun onda(uri: String): FloatArray? = onde.prendi(uri)

    fun chiudi() { onde.chiudi() }

    private fun totale() = Suono.totaleMs(pezzi)
    private fun xDi(ms: Long) = centro + (ms - t) * pxMs

    private fun pezzoSotto(x: Float, y: Float): Int {
        if (y < righello || x < testate) return -1
        val c = corsiaDi(y)
        val g = t + ((x - centro) / pxMs).toLong()
        return pezzi.indexOfFirst { it.corsia == c && g >= it.posMs && g < it.fineGlobaleMs }
    }

    private fun massimo(uri: String, o: FloatArray): Float = massimi.getOrPut(uri) { sqrt((o.maxOrNull() ?: 0f).coerceIn(0f, 1f)).coerceAtLeast(0.05f) }

    // --- disegno ---

    override fun onDraw(c: Canvas) {
        c.drawColor(coloreFondo)
        // le corsie scorrono sotto il righello, che resta fermo
        c.save(); c.clipRect(0f, righello, width.toFloat(), height.toFloat())
        for (k in 0..nCorsie) c.drawRect(testate, cima(k) - 0.5f * d, width.toFloat(), cima(k) + 0.5f * d, pLinea)
        if (corsiaVoluta >= 0) c.drawRect(testate, cima(corsiaVoluta), width.toFloat(), cima(corsiaVoluta + 1), pLoop)
        c.restore()
        if (pezzi.isEmpty()) {
            c.drawText("Tap Sound or Voice below to start", centro, cima(0) + altezzaCorsia / 2f + 5 * d, pVuoto)
            c.save(); c.clipRect(0f, righello, width.toFloat(), height.toFloat()); disegnaTestate(c); c.restore()
            return
        }
        disegnaLoop(c)
        disegnaRighello(c)
        maniglieLoop(c)
        c.save(); c.clipRect(testate, righello, width.toFloat(), height.toFloat())
        for ((i, m) in pezzi.withIndex()) disegnaPezzo(c, i, m)
        c.restore()
        disegnaSegni(c)
        c.save(); c.clipRect(0f, righello, width.toFloat(), height.toFloat()); disegnaTestate(c); c.restore()
        val xc = centro
        c.drawRect(xc - 1 * d, righello, xc + 1 * d, height.toFloat(), pRiga)
        triangolo.rewind()
        triangolo.moveTo(xc - 6 * d, righello - 2 * d); triangolo.lineTo(xc + 6 * d, righello - 2 * d); triangolo.lineTo(xc, righello + 6 * d); triangolo.close()
        c.drawPath(triangolo, pRiga)
    }

    private val pSegnoTempo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0B429.toInt() }
    private val bandierina = android.graphics.Path()

    /** I segnalini: una riga sottile su tutte le corsie e una bandierina nel righello. */
    private fun disegnaSegni(c: Canvas) {
        for (t in segni) {
            val x = xDi(t)
            if (x < testate || x > width) continue
            c.drawRect(x - 0.75f * d, righello, x + 0.75f * d, height.toFloat(), pSegnoTempo)
            bandierina.rewind()
            bandierina.moveTo(x, 2 * d); bandierina.lineTo(x + 9 * d, 6 * d); bandierina.lineTo(x, 10 * d); bandierina.close()
            c.drawRect(x - 0.75f * d, 2 * d, x + 0.75f * d, righello, pSegnoTempo)
            c.drawPath(bandierina, pSegnoTempo)
        }
    }

    /** I numeri delle corsie, a sinistra: «M» se muta, «S» se in solo; quella aperta ha il bordo rosa. */
    private fun disegnaTestate(c: Canvas) {
        c.drawRect(0f, 0f, testate, height.toFloat(), pFondoTestate)
        for (k in 0 until nCorsie) {
            rTestata.set(4 * d, cima(k) + 3 * d, testate - 4 * d, cima(k + 1) - 3 * d)
            c.drawRoundRect(rTestata, 8 * d, 8 * d, pTestata)
            if (k == corsiaAperta) c.drawRoundRect(rTestata, 8 * d, 8 * d, pTestataAperta)
            val r = Suono.corsia(ProgettoSuono("", corsie = regolazioni), k)
            val cy = rTestata.centerY()
            c.drawText("${k + 1}", rTestata.centerX(), cy + (if (r.muto || r.solo || r.volume != 100) -2 * d else 5 * d), pNumero)
            val segno = when { r.solo -> "S"; r.muto -> "M"; r.volume != 100 -> "${r.volume}%"; else -> null }
            if (segno != null) {
                pSegno.color = if (r.solo) accento else col(R.color.testo_piano)
                c.drawText(segno, rTestata.centerX(), cy + 14 * d, pSegno)
            }
        }
    }

    private fun disegnaLoop(c: Canvas) {
        val l = loop ?: return
        val a = xDi(l.first); val b = xDi(l.last)
        c.drawRect(max(a, testate), 0f, b, height.toFloat(), pLoop)
        for (x in floatArrayOf(a, b)) if (x >= testate) c.drawRect(x - 1 * d, 0f, x + 1 * d, height.toFloat(), pLoopBordo)
    }

    /** I centri delle due maniglie del Loop (px), vuoto se spento. */
    private fun centriLoop(): FloatArray { val l = loop ?: return FloatArray(0); return floatArrayOf(xDi(l.first), xDi(l.last)) }

    /** Le maniglie del Loop nel righello, sopra le tacche: le scritte che toccherebbero non si disegnano ([disegnaRighello]). */
    private fun maniglieLoop(c: Canvas) {
        for (x in centriLoop()) if (x >= testate) c.drawRoundRect(x - MEZZA_MANIGLIA_DP * d, 2 * d, x + MEZZA_MANIGLIA_DP * d, righello - 2 * d, 4 * d, 4 * d, pLoopBordo)
    }

    private fun disegnaPezzo(c: Canvas, i: Int, m: Musica) {
        val a = xDi(m.posMs); val b = xDi(m.fineGlobaleMs)
        if (b < testate || a > width) return
        val top = cima(m.corsia) + 4 * d; val bottom = cima(m.corsia + 1) - 4 * d
        // un suono cortissimo si vede lo stesso: mai meno di 6 dp
        rBlocco.set(a + fessura / 2, top, max(b - fessura / 2, a + 6 * d), bottom)
        val muto = Suono.guadagnoCorsia(ProgettoSuono("", corsie = regolazioni), m.corsia) == 0f
        val tinta = Suono.colore(m)
        if (tinta != null && !muto) { pColore.color = tinta; c.drawRoundRect(rBlocco, 6 * d, 6 * d, pColore) }
        else c.drawRoundRect(rBlocco, 6 * d, 6 * d, if (muto) pBloccoMuto else pBlocco)
        c.save(); c.clipRect(rBlocco)
        // il nome in una striscia in alto; sotto l'onda, in scala al picco più alto di QUEL suono
        val info = m.nome + (if (m.velocita != 1f) " · " + Progetto.velocitaScritta(m.velocita) else "") +
            when (m.volume) { 100 -> ""; 0 -> " · muted"; else -> " · ${m.volume}%" }
        val xn = max(rBlocco.left, testate) + 7 * d
        c.drawText(info, xn, rBlocco.top + 13 * d, pNome)
        val o = onde.prendi(m.uri)
        if (o != null && o.isNotEmpty()) {
            val g = (m.volume / 100f).coerceIn(0f, 1.25f)
            val su = rBlocco.top + striscia; val giu = rBlocco.bottom - 3 * d
            val mezzo = (su + giu) / 2f; val alto = (giu - su) / 2f
            val scala = massimo(m.uri, o)
            val passo = 2 * d
            var x = max(rBlocco.left, testate)
            val fine = min(rBlocco.right, width.toFloat())
            while (x < fine) {
                // la velocità stringe o allarga l'onda: un px del progetto vale «velocità» px del file
                val da = m.inizioMs + ((x - a) / pxMs * m.velocita).toLong()
                val h = (Onde.massimo(o, da, da + (passo / pxMs * m.velocita).toLong()) / scala * g).coerceAtMost(1f) * alto
                if (h > 0.5f) c.drawRect(x, mezzo - h, x + passo * 0.6f, mezzo + h, if (muto) pOndaMuta else pOnda)
                x += passo
            }
        }
        // le dissolvenze: triangoli scuri lunghi quanto durano
        if (m.entra) {
            val w = min(m.durEntra.coerceAtMost(m.suonaMs / 2) * pxMs, rBlocco.width())
            triangolo.rewind(); triangolo.moveTo(rBlocco.left, rBlocco.top + striscia); triangolo.lineTo(rBlocco.left + w, rBlocco.top + striscia)
            triangolo.lineTo(rBlocco.left, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza)
        }
        if (m.esce) {
            val w = min(m.durEsce.coerceAtMost(m.suonaMs / 2) * pxMs, rBlocco.width())
            triangolo.rewind(); triangolo.moveTo(rBlocco.right, rBlocco.top + striscia); triangolo.lineTo(rBlocco.right - w, rBlocco.top + striscia)
            triangolo.lineTo(rBlocco.right, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza)
        }
        c.restore()
        c.drawRoundRect(rBlocco, 6 * d, 6 * d, pBordoBlocco)
        if (i == scelto) disegnaScelta(c, rBlocco)
    }

    private fun disegnaScelta(c: Canvas, r: RectF) {
        c.drawRoundRect(r, 6 * d, 6 * d, pScelto)
        rSx.set(r.left - maniglia, r.top, r.left, r.bottom)
        rDx.set(r.right, r.top, r.right + maniglia, r.bottom)
        c.drawRoundRect(rSx, 4 * d, 4 * d, pManiglia)
        c.drawRoundRect(rDx, 4 * d, 4 * d, pManiglia)
        val ym = r.centerY()
        val h = min(6 * d, r.height() / 4)
        frecce.rewind()
        frecce.moveTo(rSx.centerX() + 2 * d, ym - h); frecce.lineTo(rSx.centerX() - 2 * d, ym); frecce.lineTo(rSx.centerX() + 2 * d, ym + h)
        frecce.moveTo(rDx.centerX() - 2 * d, ym - h); frecce.lineTo(rDx.centerX() + 2 * d, ym); frecce.lineTo(rDx.centerX() - 2 * d, ym + h)
        c.drawPath(frecce, pFreccia)
    }

    private fun disegnaRighello(c: Canvas) {
        var passo = PASSI_RIGHELLO.last()
        for (p in PASSI_RIGHELLO) if (p * pxMs >= 64 * d) { passo = p; break }
        val tot = totale()
        val primo = max(0L, ((t - (centro - testate) / pxMs) / passo).toLong() * passo)
        val decimi = passo < 1000
        if (scritteRighello.size > 600) scritteRighello.clear()
        val maniglie = centriLoop()
        var m = primo
        while (m <= tot) {
            val x = xDi(m)
            if (x > width) break
            if (x >= testate) {
                c.drawRect(x, righello - 6 * d, x + 1 * d, righello, pTacca)
                val chiave = if (decimi) -(m + 1) else m
                val testo = scritteRighello.getOrPut(chiave) {
                    val s = m / 1000
                    if (decimi) "%d:%02d.%d".format(s / 60, s % 60, (m % 1000) / 100) else "%d:%02d".format(s / 60, s % 60)
                }
                val xs = x + 3 * d
                if (Suono.scrittaLibera(xs, xs + pTesto.measureText(testo), maniglie, (MEZZA_MANIGLIA_DP + 2) * d)) c.drawText(testo, xs, righello - 8 * d, pTesto)
            }
            m += passo
        }
    }

    // --- tocco ---

    private val gesti = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dito != Dito.NIENTE && dito != Dito.SCORRE) return false
            if (zoomato || e2.pointerCount > 1) return true
            dito = Dito.SCORRE
            if (asse == 0) asse = if (abs(dy) > abs(dx) && suMax() > 0f) 2 else 1
            if (asse == 2) { su = (su + dy).coerceIn(0f, suMax()); invalidate(); return true }
            val ms = dx / pxMs + resto
            val intero = ms.toLong()
            resto = ms - intero
            val nuovo = (t + intero).coerceIn(0, totale())
            if (nuovo != t) { t = nuovo; ascoltatore?.scorri(t); invalidate() }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (dito != Dito.SCORRE || zoomato) return false
            spintaSu = asse == 2
            if (spintaSu) {
                scroller.fling(0, su.toInt(), 0, -vy.toInt(), 0, 0, 0, suMax().toInt())
                postInvalidateOnAnimation()
                return true
            }
            scroller.fling((t * pxMs).toInt(), 0, -vx.toInt(), 0, 0, (totale() * pxMs).toInt(), 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (zoomato) return true
            if (e.x < testate) {
                if (e.y >= righello) { val c = corsiaDi(e.y); if (c < nCorsie) ascoltatore?.corsia(c) }
                return true
            }
            val s = pezzoSotto(e.x, e.y)
            scelto = if (s == scelto) -1 else s
            ascoltatore?.scegli(scelto)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            // tenuto premuto un pezzo non scelto: si sceglie e si trascina subito
            if (dito != Dito.NIENTE || zoomato) return
            val s = pezzoSotto(e.x, e.y)
            if (s < 0) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            scelto = s; ascoltatore?.scegli(s)
            dito = Dito.TRASCINA; x0 = e.x; mosso = false
            iniziaTrascinamento()
            parent?.requestDisallowInterceptTouchEvent(true)
            invalidate()
        }
    })

    private val zoom = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(s: ScaleGestureDetector): Boolean {
            if (dito != Dito.NIENTE && dito != Dito.SCORRE) return false
            zoomato = true
            return true
        }

        override fun onScale(s: ScaleGestureDetector): Boolean {
            pxMs = (pxMs * s.scaleFactor).coerceIn(0.5f * d / 1000f, 600 * d / 1000f)
            invalidate()
            return true
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (spintaSu) { su = scroller.currY.toFloat().coerceIn(0f, suMax()); postInvalidateOnAnimation(); return }
            t = (scroller.currX / pxMs).toLong().coerceIn(0, totale())
            ascoltatore?.scorri(t)
            if (scroller.isFinished) ascoltatore?.fermo(t)
            postInvalidateOnAnimation()
        }
    }

    private fun trascinando() = dito == Dito.BORDO_SX || dito == Dito.BORDO_DX || dito == Dito.TRASCINA
    private fun loopando() = dito == Dito.LOOP_A || dito == Dito.LOOP_B

    private fun iniziaTrascinamento() {
        preso = scelto
        val m = pezzi.getOrNull(preso) ?: return
        corsiaPresa = m.corsia; corsiaVoluta = -1
        t0 = t; agganciato = null; velBordo = 0f; restoBordo = 0f
        bordiMobili = when (dito) {
            Dito.BORDO_SX -> longArrayOf(m.posMs)
            Dito.BORDO_DX -> longArrayOf(m.fineGlobaleMs)
            else -> longArrayOf(m.posMs, m.fineGlobaleMs)
        }
        fissi = Suono.bordi(pezzi, preso, segni)
    }

    /** Il dito (più quanto è scorsa la timeline da sola), attaccato a un bordo vicino; quando si attacca, un colpetto. */
    private fun delta(x: Float): Long {
        val libero = ((x - x0) / pxMs).toLong() + (t - t0)
        val bersagli = if (velBordo == 0f) fissi + t else fissi
        val c = Progetto.calamita(bordiMobili, bersagli, libero, (CALAMITA_DP * d / pxMs).toLong())
        if (c != agganciato) { if (c != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); agganciato = c }
        return c ?: libero
    }

    private fun muovi(x: Float, y: Float) {
        if (loopando()) {
            val l = loop ?: return
            mosso = true
            val dl = delta(x)
            val n = (bordiMobili[0] + dl).coerceIn(0, totale())
            val nuovo = if (dito == Dito.LOOP_A) n.coerceAtMost(l.last - LOOP_MIN)..l.last else l.first..n.coerceAtLeast(l.first + LOOP_MIN)
            loop = nuovo
            ascoltatore?.loop(nuovo.first, nuovo.last)
            return
        }
        if (preso < 0) return
        if (!mosso && abs(x - x0) < 6 * d && t == t0 && (dito != Dito.TRASCINA || corsiaDi(y) == corsiaPresa)) return
        mosso = true
        val dl = delta(x)
        if (dito == Dito.TRASCINA) {
            val c = corsiaDi(y).coerceAtMost(nCorsie - 1)
            if (c != corsiaVoluta) { if (corsiaVoluta >= 0 || c != corsiaPresa) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); corsiaVoluta = c }
            ascoltatore?.trascina(preso, dl, c, false)
        } else ascoltatore?.bordo(preso, dito == Dito.BORDO_SX, dl, false)
    }

    private fun finisci(x: Float, y: Float, annullato: Boolean) {
        val era = dito
        velBordo = 0f; velSu = 0f; removeCallbacks(scorriSola); scorreDaSola = false
        val scorsa = t != t0
        dito = Dito.NIENTE; ditoId = -1
        if (era == Dito.LOOP_A || era == Dito.LOOP_B) { if (scorsa) ascoltatore?.fermo(t); invalidate(); return }
        val i = preso
        val c = if (annullato || !mosso) corsiaPresa else corsiaDi(y).coerceAtMost(nCorsie - 1)
        val dl = if (annullato || !mosso) 0L else delta(x)
        corsiaVoluta = -1; preso = -1
        when (era) {
            Dito.BORDO_SX, Dito.BORDO_DX -> ascoltatore?.bordo(i, era == Dito.BORDO_SX, dl, true)
            Dito.TRASCINA -> ascoltatore?.trascina(i, dl, c, true)
            else -> {}
        }
        if (scorsa) ascoltatore?.fermo(t)
        invalidate()
    }

    /** Vicino al lato sinistro o destro la timeline scorre da sola, più svelta più si è vicini; un suono portato in alto o in basso fa scorrere le corsie. */
    private fun bordoVicino(x: Float, y: Float) {
        val zv = ZONA_SU_DP * d
        velSu = when {
            dito != Dito.TRASCINA || suMax() <= 0f -> 0f
            y < righello + zv -> -((righello + zv - y) / zv).coerceIn(0f, 1f) * VEL_SU_DP * d
            y > height - zv -> ((y - (height - zv)) / zv).coerceIn(0f, 1f) * VEL_SU_DP * d
            else -> 0f
        }
        val zona = ZONA_BORDO_DP * d
        velBordo = when {
            x < testate + zona -> -((testate + zona - x) / zona).coerceAtMost(1f) * VEL_BORDO_DP * d
            x > width - zona -> ((x - (width - zona)) / zona).coerceAtMost(1f) * VEL_BORDO_DP * d
            else -> 0f
        }
        if ((velBordo != 0f || velSu != 0f) && !scorreDaSola) { scorreDaSola = true; postOnAnimation(scorriSola) }
    }

    private val scorriSola = object : Runnable {
        override fun run() {
            if ((velBordo == 0f && velSu == 0f) || !(trascinando() || loopando())) { scorreDaSola = false; return }
            var cambiato = false
            if (velSu != 0f) {
                val n = (su + velSu).coerceIn(0f, suMax())
                if (n != su) { su = n; cambiato = true }
            }
            if (velBordo != 0f) {
                val ms = velBordo / pxMs + restoBordo
                val intero = ms.toLong(); restoBordo = ms - intero
                // oltre la fine si può andare: il pezzo trascinato allunga il progetto
                val n = (t + intero).coerceIn(0, totale() + 10_000)
                if (n != t) { t = n; ascoltatore?.scorri(t); cambiato = true }
            }
            if (cambiato) { muovi(ultimoX, ultimoY); invalidate() }
            postOnAnimation(this)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pezzi.isEmpty()) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) { scroller.forceFinished(true); ascoltatore?.fermo(t) }
                dito = Dito.NIENTE; ditoId = e.getPointerId(0)
                zoomato = false; mosso = false; resto = 0f; x0 = e.x; asse = 0
                spintaSu = false
                // i bordi del loop si prendono nel righello
                val l = loop
                if (l != null && e.y < righello + 12 * d && e.x >= testate) {
                    val da = abs(e.x - xDi(l.first)); val db = abs(e.x - xDi(l.last))
                    if (min(da, db) < 24 * d) {
                        dito = if (da <= db) Dito.LOOP_A else Dito.LOOP_B
                        t0 = t; agganciato = null; velBordo = 0f; restoBordo = 0f
                        bordiMobili = longArrayOf(if (dito == Dito.LOOP_A) l.first else l.last)
                        fissi = Suono.bordi(pezzi, -1, segni)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                val m = pezzi.getOrNull(scelto)
                if (m != null && e.x >= testate && e.y >= cima(m.corsia) && e.y <= cima(m.corsia + 1)) {
                    val a = xDi(m.posMs); val b = max(xDi(m.fineGlobaleMs), a + 6 * d)
                    // un suono corto: toccato dentro si sposta, i bordi si prendono solo fuori
                    val corto = b - a < 2 * presa
                    if (corto && e.x > a && e.x < b) {
                        dito = Dito.TRASCINA
                        iniziaTrascinamento()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    val dSx = abs(e.x - (a - maniglia / 2)); val dDx = abs(e.x - (b + maniglia / 2))
                    if (min(dSx, dDx) < presa) {
                        dito = if (dSx <= dDx) Dito.BORDO_SX else Dito.BORDO_DX
                        iniziaTrascinamento()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                    if (e.x > a && e.x < b) {
                        dito = Dito.TRASCINA
                        iniziaTrascinamento()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (trascinando() || loopando()) return true
            MotionEvent.ACTION_POINTER_UP -> if (trascinando() || loopando()) {
                if (e.getPointerId(e.actionIndex) == ditoId) finisci(e.getX(e.actionIndex), e.getY(e.actionIndex), false)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (trascinando() || loopando()) {
                val k = e.findPointerIndex(ditoId)
                if (k < 0) return true
                ultimoX = e.getX(k); ultimoY = e.getY(k)
                muovi(ultimoX, ultimoY)
                if (mosso) bordoVicino(ultimoX, ultimoY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val annullato = e.actionMasked == MotionEvent.ACTION_CANCEL
                if (trascinando() || loopando()) {
                    val eraTocco = dito == Dito.TRASCINA && !mosso
                    finisci(e.x, e.y, annullato)
                    // un tocco senza movimento sul pezzo scelto lo lascia
                    if (eraTocco && !annullato) { scelto = -1; ascoltatore?.scegli(-1); invalidate() }
                    return true
                }
                zoom.onTouchEvent(e)
                gesti.onTouchEvent(e)
                val era = dito
                dito = Dito.NIENTE; ditoId = -1
                if (era == Dito.SCORRE && scroller.isFinished) ascoltatore?.fermo(t)
                return true
            }
        }
        zoom.onTouchEvent(e)
        gesti.onTouchEvent(e)
        return true
    }

    private companion object {
        const val CALAMITA_DP = 10f
        const val ZONA_BORDO_DP = 44f
        const val VEL_BORDO_DP = 9f
        /** in alto e in basso: la fascia dove un suono trascinato fa scorrere le corsie, e quanto per fotogramma */
        const val ZONA_SU_DP = 28f
        const val VEL_SU_DP = 6f
        /** metà larghezza di una maniglia del Loop nel righello */
        const val MEZZA_MANIGLIA_DP = 7f
        /** il loop più corto */
        const val LOOP_MIN = 300L
        val PASSI_RIGHELLO = longArrayOf(500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000)
    }
}
