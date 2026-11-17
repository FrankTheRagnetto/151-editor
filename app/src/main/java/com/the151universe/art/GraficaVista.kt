package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Il foglio sullo schermo, con la cornice dell'oggetto scelto. Le dita:
 * un tocco sceglie (sul vuoto toglie la scelta), trascinare sposta con le guide magnetiche,
 * gli angoli ingrandiscono, la maniglia tonda sotto gira (si attacca a 0/45/90°),
 * Due dita ingrandiscono SEMPRE la pagina (mai l'oggetto,
 * «Sì, A» di chi lo usa 05/11); ingrandita, un dito sul vuoto la sposta; doppio tocco sul vuoto = pagina intera.
 * I testi si cambiano sulla pagina: un tocco sceglie la
 * riga, un tocco sulla riga scelta mette il cursore lì e apre la tastiera (questa vista è la casella:
 * niente bolle del telefono), tenere premuto sceglie una parola e trascinando si allarga, le due
 * maniglie rosa spostano gli estremi lettera per lettera, doppio tocco = la parola.
 * La vista non decide niente: dice all'[ascoltatore] cosa fa il dito e mostra quello che riceve.
 */
class GraficaVista @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    interface Ascoltatore {
        fun scelto(id: Long?)
        /** libro aperto: un tocco sull'altra pagina la apre */
        fun apriPagina(p: Int) {}
        /** il foglio dopo il dito; [fine] = il dito si è alzato (entra in Annulla/Rifai) */
        fun gesto(g: Grafica, fine: Boolean)
        /** un tocco su una cella della tabella già scelta */
        fun cella(id: Long, r: Int, c: Int)
        /** doppio tocco su una cella: diventa la cella scelta (subito dopo arriva la parola come [pezzo]) */
        fun riscriviCella(id: Long, r: Int, c: Int)
        /** il pezzo scelto del testo [id] è cambiato (null = nessun pezzo, la riga intera) */
        fun pezzo(id: Long, p: IntRange?)
        /** la tastiera scrive nel testo [id] col cursore in [cursore] (-1 = si è smesso di scrivere) */
        fun scrive(id: Long, cursore: Int)
        /** il testo [id] (o la sua cella [cella]) è diventato [testo] con [segni] (la tastiera ha scritto o cancellato) */
        fun testo(id: Long, cella: Pair<Int, Int>?, testo: String, segni: List<Segno>)
        /** «Select more»: un tocco su [id] lo aggiunge o lo toglie */
        fun tocca(id: Long) {}
        /** il dito ha sfogliato la pagina intera sul vuoto: [verso] +1 = la pagina dopo, -1 = quella prima */
        fun sfoglia(verso: Int) {}
    }

    var ascoltatore: Ascoltatore? = null
    var immagini = GraficaDisegno.Immagini { null }
    private var g: Grafica? = null
    var scelto: Long? = null
        set(v) { field = v; invalidate() }
    /** più oggetti scelti insieme (gruppo o «Select more»): si spostano insieme, niente maniglie */
    var insieme: Set<Long> = emptySet()
        set(v) { field = v; invalidate() }
    /** «Select more» acceso: i tocchi aggiungono e tolgono */
    var aggiungi = false
    /** «Move photo»: il dito sposta la foto dentro il suo riquadro, non il riquadro */
    var dentroFoto = false
    private var toccato: Long? = null
    /** la pagina aperta: si vede e si tocca solo lei */
    var pagina = 0
        set(v) { field = v; invalidate() }
    /** la cella scelta della tabella scelta (riga, colonna), o null */
    var cella: Pair<Int, Int>? = null
        set(v) { field = v; invalidate() }
    /** «Trimmed»: si vede solo la pagina tagliata, senza la fascia dell'abbondanza */
    var tagliata = false
    /** margini e colonne: si vedono e ci si aggancia (solo qui, mai nel PDF) */
    var conGuide = true
        set(v) { field = v; invalidate() }
    private fun ab(gr: Grafica) = if (tagliata || affiancate) 0f else gr.abbondanza

    /**
     * Due pagine affiancate come un libro aperto: la prima da sola a destra, poi 2|3, 4|5…
     * (pari a sinistra contando da 1). Si tocca e si cambia la pagina aperta; l'altra si vede accanto,
     * senza abbondanza perché si toccano alla piega. Un tocco sull'altra la apre, senza muovere niente.
     */
    var affiancate = false
        set(v) { if (field != v) { field = v; baseS = 0f; altaMax = 0f; paginaIntera() } }
    private val gap get() = 0f

    /** Lo spostamento che mette l'angolo della pagina aperta in [oxVoluto] a misura [sn] (una pagina o il libro aperto). */
    private fun pxPer(oxVoluto: Float, sn: Float): Float {
        val larghe = if (affiancate) 2 else 1
        val destra = if (affiancate && !aSinistra(pagina)) Grafica.LARGO * sn else 0f
        return oxVoluto - destra - (width - larghe * Grafica.LARGO * sn) / 2f
    }

    /** La pagina [p] sta a sinistra nel libro aperto (indice da 0: la 1 è destra, la 2 sinistra…). */
    private fun aSinistra(p: Int) = p % 2 == 1
    /** L'altra pagina del libro aperto, o -1 (la prima, o l'ultima pari da sola). */
    private fun compagna(gr: Grafica): Int {
        if (!affiancate) return -1
        val q = if (aSinistra(pagina)) pagina + 1 else pagina - 1
        return if (q in 0 until gr.pagine) q else -1
    }
    /** Dove comincia a sinistra il libro aperto, in pixel. */
    private var libroX = 0f

    /**: il testo lasciato oltre la piega è passato su questa pagina: si apre al dito alzato (-1 = niente). */
    private var daAprire = -1
    private var daScegliere: Long? = null

    /** Il dito si alza dopo uno spostamento a libro aperto: ciò che è oltre la piega passa sull'altra pagina. */
    private fun oltreLaPiega(gr: Grafica) {
        val v = vivo ?: return
        val altra = compagna(gr)
        if (altra < 0) return
        val ids = insieme + listOfNotNull(scelto, base?.id)
        val n = Grafiche.dopoLaPiega(v, ids, pagina, altra, compagnaASinistra = !aSinistra(pagina))
        if (n === v) return
        emetti(n, false)
        daAprire = altra
        daScegliere = if (insieme.size > 1) null else base?.id
    }

    /** il pezzo scelto del testo scelto (lettere da..a esclusa), o null */
    var pezzo: IntRange? = null
        set(v) { field = v; invalidate() }
    /** dove scrive la tastiera nel testo scelto; -1 = non si scrive */
    var cursore = -1
        private set
    val scrivendo get() = cursore >= 0
    /** i pixel in fondo coperti da tastiera e barra: il cursore resta sopra */
    var coperto = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    // il foglio → lo schermo: x_schermo = ox + x * s
    private var s = 1f; private var ox = 0f; private var oy = 0f
    /** lo zoom della vista (1 = pagina intera) e lo spostamento della pagina, in pixel */
    private var z = 1f; private var px = 0f; private var py = 0f

    /**
     * Pixel in basso che la pagina lascia liberi anche quando ci sono: il pannello sparisce se non
     * è scelto niente, la vista si allunga di tanto e la pagina resta ferma sotto il dito.
     */
    var riservaSotto = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Torna alla pagina intera (pagina nuova, formato nuovo, doppio tocco sul vuoto). */
    fun paginaIntera() { corsa?.cancel(); z = 1f; px = 0f; py = 0f; invalidate() }

    /** Il punto del foglio al centro di ciò che si vede (null = pagina intera: il mezzo della pagina va bene). */
    fun centroVisto(): Pair<Float, Float>? {
        if (z <= 1.01f || !misure()) return null
        val alto = if (coperto > 0f) height - coperto else height.toFloat()
        return pagX(width / 2f) to pagY(alto / 2f)
    }

    /** Quanto foglio si vede in larghezza, in punti del foglio. */
    fun largoVisto(): Float = if (misure()) width / s else Grafica.LARGO

    /** Torna alla pagina intera scivolando (il tasto in basso, il doppio tocco sul vuoto). */
    private fun tornaIntera() {
        val z0 = z; val px0 = px; val py0 = py
        if (z0 <= 1f) { paginaIntera(); return }
        corsa?.cancel()
        corsa = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                z = z0 + (1f - z0) * t; px = px0 * (1f - t); py = py0 * (1f - t)
                invalidate()
            }
            start()
        }
    }

    fun mostra(nuova: Grafica) { g = nuova; invalidate() }

    /** Il foglio intero sullo schermo (per la penna: i tratti nuovi stanno in frazioni di lui). */
    fun foglio(): RectF? {
        val gr = g ?: return null
        if (!misure()) return null
        return RectF(ox, oy, ox + Grafica.LARGO * s, oy + gr.alto * s)
    }

    private var altaPrima = 0f
    private var baseS = 0f; private var baseW = 0; private var baseAb = -1f; private var altaMax = 0f

    private fun misure(): Boolean {
        val gr = g ?: return false
        if (width == 0 || height == 0) return false
        val m = 16 * dp
        // il foglio entra intero con la sua abbondanza; ox,oy restano l'angolo del TAGLIO
        val ab = ab(gr)
        val alta = (height - riservaSotto).coerceAtLeast(1f)
        // il pannello va e viene e cambia l'altezza: la pagina ingrandita resta dov'era sullo schermo
        // (riservaSotto è una stima del pannello, l'altezza vera cambia con quello che mostra)
        if (altaPrima > 0f && alta != altaPrima && corsa?.isRunning != true) py += (altaPrima - alta) / 2f
        altaPrima = alta
        // la misura di base (pagina intera) si prende a zoom 1; ingrandita resta quella, o il pannello
        // che cambia l'altezza la farebbe crescere e calare sotto il dito
        // la misura della pagina intera la dà lo spazio più grande visto (senza pannello): quando il pannello
        // sale la pagina non rimpicciolisce, lui ne copre il fondo
        if (baseW != width) { altaMax = 0f; baseS = 0f }
        if (alta > altaMax) { altaMax = alta; baseS = 0f }
        val larghe = if (affiancate) 2 else 1
        val intera = min((width - 2 * m) / (larghe * Grafica.LARGO + 2 * ab), (altaMax - 2 * m) / (gr.alto + 2 * ab))
        if (baseS <= 0f || baseAb != ab) { baseS = intera; baseW = width; baseAb = ab }
        s = baseS * z
        // la pagina si sposta quando è più grande dello spazio, ma un suo bordo non entra mai oltre il margine
        val limX = kotlin.math.max(0f, ((larghe * Grafica.LARGO + 2 * ab) * s - width) / 2f + m)
        // più alta dello spazio: si scorre fino al margine; più bassa: resta dentro, ma non torna per forza
        // in mezzo (il pannello che sale la lascerebbe saltare in su)
        // in alto la pagina va dal margine fino a dove stava a tutto spazio (il pannello che sale ne copre
        // il fondo e lei resta lì), in basso fino al suo margine; pagina più alta dello spazio: si scorre
        val hp = (gr.alto + 2 * ab) * s
        val cima = (alta - hp) / 2f
        val alto0 = minOf(m, alta - hp - m)
        val alto1 = maxOf(m, alta - hp - m, (altaMax - hp) / 2f)
        px = px.coerceIn(-limX, limX); py = py.coerceIn(alto0 - cima, alto1 - cima)
        libroX = (width - larghe * Grafica.LARGO * s) / 2f + px
        ox = if (affiancate && !aSinistra(pagina)) libroX + Grafica.LARGO * s else libroX
        oy = (alta - gr.alto * s) / 2f + py
        // scrivendo, la riga del cursore resta sopra la tastiera (la pagina sale, non rimpicciolisce)
        if (scrivendo) scritta(gr.trova(scelto))?.let { sc ->
            val l = sc.l
            val riga = l.getLineForOffset(cursore.coerceIn(0, sc.testo.length))
            // senza giro basta: la riga in punti del foglio dentro l'oggetto
            val sotto = oy + (sc.e.y + sc.y0 + l.getLineBottom(riga)) * s + 20 * dp
            val sopra = oy + (sc.e.y + sc.y0 + l.getLineTop(riga)) * s - 20 * dp
            val limite = height - coperto
            if (sotto > limite) oy -= sotto - limite else if (sopra < 0f) oy -= sopra
            // e di lato: con la pagina ingrandita la riga può stare in un'altra colonna, fuori dallo schermo
            val cx = ox + (sc.e.x + sc.x0 + l.getPrimaryHorizontal(cursore.coerceIn(0, sc.testo.length))) * s
            val m = 32 * dp
            if (cx > width - m) ox -= cx - (width - m) else if (cx < m) ox += m - cx
        }
        return true
    }

    // ---------------------------------------------------------------- disegno

    private val ombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; setShadowLayer(18f, 0f, 6f, 0xCC000000.toInt()) }
    private val bordoScelta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6 * resources.displayMetrics.density, 4 * resources.displayMetrics.density), 0f)
    }
    private val bordoSotto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000; strokeWidth = 3.5f * resources.displayMetrics.density }
    private val maniglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(4f, 0f, 1f, 0x99000000.toInt()) }
    private val anello = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * resources.displayMetrics.density; color = ContextCompat.getColor(context, R.color.accento)
    }
    private val guida = Paint().apply { color = ContextCompat.getColor(context, R.color.accento); strokeWidth = 1.5f * resources.displayMetrics.density }
    private val bolla = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
    private val bollaTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 13 * resources.displayMetrics.density; textAlign = Paint.Align.CENTER }
    private val cellaScelta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * resources.displayMetrics.density; color = ContextCompat.getColor(context, R.color.accento)
    }
    private val bordoTab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accento) }
    private val iconaGiro = ContextCompat.getDrawable(context, R.drawable.ic_ruota)?.mutate()?.apply { setTint(0xFF121014.toInt()) }

    private val piega = Paint().apply { color = 0x33000000; strokeWidth = 1f * resources.displayMetrics.density }

    // margini e colonne come InDesign: margini rosa, colonne viola, sottili
    private val guidaMargine = Paint().apply { color = ContextCompat.getColor(context, R.color.guida_margine); strokeWidth = 1f * resources.displayMetrics.density }
    private val guidaColonna = Paint().apply { color = ContextCompat.getColor(context, R.color.guida_colonna); strokeWidth = 1f * resources.displayMetrics.density }

    /** Le guide della pagina [p], con la pagina che comincia a [x0] pixel. */
    private fun disegnaGuide(c: Canvas, gr: Grafica, p: Int, x0: Float) {
        if (!conGuide) return
        val q = Grafiche.guide(gr, p) ?: return
        val l = x0 + q.l * s; val r = x0 + q.r * s; val t = oy + q.t * s; val b = oy + q.b * s
        for ((a, z) in q.colonne) { c.drawLine(x0 + a * s, t, x0 + a * s, b, guidaColonna); c.drawLine(x0 + z * s, t, x0 + z * s, b, guidaColonna) }
        c.drawLine(l, t, r, t, guidaMargine); c.drawLine(l, b, r, b, guidaMargine)
        c.drawLine(l, t, l, b, guidaMargine); c.drawLine(r, t, r, b, guidaMargine)
    }

    private var guidaX: Float? = null
    private var guidaY: Float? = null
    private var mostraGradi = false

    // il disegno velo nero 45% fuori dal taglio, riga bianca 65% + una scura 25% dentro
    private val velo = Paint().apply { color = 0x73000000 }
    private val taglio = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xA6FFFFFF.toInt(); strokeWidth = 1f * resources.displayMetrics.density
    }
    private val taglioDentro = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x40000000; strokeWidth = 1f * resources.displayMetrics.density
    }

    override fun onDraw(c: Canvas) {
        val gr = g ?: return
        if (!misure()) return
        val ab = ab(gr) * s
        val tl = ox; val tt = oy; val tr = ox + Grafica.LARGO * s; val tb = oy + gr.alto * s
        val altra = compagna(gr)
        if (altra >= 0) {
            // il libro aperto: un'ombra sola attorno alle due, l'altra pagina accanto, una piega sottile
            val ax = if (aSinistra(pagina)) ox + Grafica.LARGO * s else ox - Grafica.LARGO * s
            c.drawRect(minOf(ox, ax), tt, maxOf(ox, ax) + Grafica.LARGO * s, tb, ombra)
            c.save(); c.translate(ax, oy); c.scale(s, s)
            GraficaDisegno.disegna(context, c, gr, immagini, editor = true, pagina = altra, conAbbondanza = false)
            c.restore()
            c.drawLine(libroX + Grafica.LARGO * s, tt, libroX + Grafica.LARGO * s, tb, piega)
            disegnaGuide(c, gr, altra, ax)
        } else c.drawRect(tl - ab, tt - ab, tr + ab, tb + ab, ombra)
        c.save()
        c.translate(ox, oy); c.scale(s, s)
        GraficaDisegno.disegna(context, c, gr, immagini, editor = true, pagina = pagina, conAbbondanza = ab > 0f) // a libro aperto niente fascia: copriva la piega
        c.restore()
        if (altra >= 0 && modo == Modo.SPOSTA && mosso) {
            //: il pezzo trascinato oltre la piega si vede anche di là (la pagina aperta lo taglia al suo bordo)
            val ids = insieme + listOfNotNull(scelto, base?.id)
            c.save()
            c.clipOutRect(tl, tt, tr, tb)
            c.translate(ox, oy); c.scale(s, s)
            for (x in gr.elementi) if (x.pagina == pagina && x.id in ids) GraficaDisegno.elemento(context, c, x, immagini, true)
            c.restore()
        }
        if (ab > 0f) {
            // l'abbondanza: si vede cosa esce, attenuato; la riga sottile è dove taglia la tipografia
            c.drawRect(tl - ab, tt - ab, tr + ab, tt, velo)
            c.drawRect(tl - ab, tb, tr + ab, tb + ab, velo)
            c.drawRect(tl - ab, tt, tl, tb, velo)
            c.drawRect(tr, tt, tr + ab, tb, velo)
            c.drawRect(tl, tt, tr, tb, taglio)
            val k = 1f * dp
            c.drawRect(tl + k, tt + k, tr - k, tb - k, taglioDentro)
        }
        disegnaGuide(c, gr, pagina, ox)
        guidaX?.let { x -> c.drawLine(ox + x * s, oy, ox + x * s, oy + gr.alto * s, guida) }
        guidaY?.let { y -> c.drawLine(ox, oy + y * s, ox + Grafica.LARGO * s, oy + y * s, guida) }
        if (insieme.size > 1 || aggiungi) {
            // più oggetti: ognuno col suo bordo, niente maniglie (si spostano insieme)
            for (x in gr.elementi) if (x.id in insieme || x.id == scelto) {
                c.save(); c.translate(ox + x.cx * s, oy + x.cy * s); c.rotate(x.rot)
                val r = RectF(-x.w * s / 2f - 4 * dp, -x.h * s / 2f - 4 * dp, x.w * s / 2f + 4 * dp, x.h * s / 2f + 4 * dp)
                c.drawRoundRect(r, 4 * dp, 4 * dp, bordoSotto); c.drawRoundRect(r, 4 * dp, 4 * dp, bordoScelta)
                c.restore()
            }
            return
        }
        val e = gr.trova(scelto) ?: return
        if (e.tipo == Elemento.TESTO) { sceltaTesto(c, e); return }
        c.save()
        c.translate(ox + e.cx * s, oy + e.cy * s)
        c.rotate(e.rot)
        val hw = e.w * s / 2f + 6 * dp; val hh = e.h * s / 2f + 6 * dp
        val r = RectF(-hw, -hh, hw, hh)
        c.drawRoundRect(r, 6 * dp, 6 * dp, bordoSotto)
        c.drawRoundRect(r, 6 * dp, 6 * dp, bordoScelta)
        if (e.tipo == Elemento.TABELLA) {
            val xs = Tabelle.xs(e); val ys = Tabelle.ys(e)
            val ox0 = -e.w * s / 2f; val oy0 = -e.h * s / 2f
            cella?.let { (r, k) ->
                if (r < Tabelle.nr(e) && k < Tabelle.nc(e)) {
                    val u = Tabelle.larga(e, r, k)
                    c.drawRect(ox0 + xs[k] * s, oy0 + ys[r] * s, ox0 + xs[k + u] * s, oy0 + ys[r + 1] * s, cellaScelta)
                }
            }
            // i bordi da trascinare: sopra per le colonne, a sinistra per le righe
            for (i in 1 until xs.size - 1) c.drawRoundRect(RectF(ox0 + xs[i] * s - 5 * dp, -hh - 9 * dp, ox0 + xs[i] * s + 5 * dp, -hh + 3 * dp), 4 * dp, 4 * dp, bordoTab)
            for (i in 1 until ys.size - 1) c.drawRoundRect(RectF(-hw - 9 * dp, oy0 + ys[i] * s - 5 * dp, -hw + 3 * dp, oy0 + ys[i] * s + 5 * dp), 4 * dp, 4 * dp, bordoTab)
        }
        for ((mx, my) in angoli(e)) {
            c.drawCircle(mx * hw, my * hh, 7 * dp, maniglia)
            c.drawCircle(mx * hw, my * hh, 7 * dp, anello)
        }
        val gy = hh + 34 * dp
        c.drawCircle(0f, gy, 15 * dp, maniglia)
        iconaGiro?.let { val k = (9 * dp).roundToInt(); it.setBounds(-k, (gy - k).roundToInt(), k, (gy + k).roundToInt()); it.draw(c) }
        if (mostraGradi) {
            val t = "${e.rot.roundToInt()}°"
            val w = bollaTesto.measureText(t) + 16 * dp
            val by = -hh - 34 * dp
            c.drawRoundRect(RectF(-w / 2f, by - 12 * dp, w / 2f, by + 12 * dp), 12 * dp, 12 * dp, bolla)
            c.drawText(t, 0f, by + 4.5f * dp, bollaTesto)
        }
        c.restore()
        if (e.tipo == Elemento.TABELLA && (pezzo != null || scrivendo)) scritta(e)?.let { disegnaScritta(c, it) }
    }

    // ---------------------------------------------------------------- il testo scelto

    private val accento = ContextCompat.getColor(context, R.color.accento)
    private val velaRiga = Paint().apply { color = (accento and 0x00FFFFFF) or 0x1F000000 }
    private val bordoRiga = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accento; strokeWidth = 1f * dp }
    private val velaPezzo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accento and 0x00FFFFFF) or 0x59000000 }
    private val pennaRosa = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento; strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND }
    private val presaLato = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(3f, 0f, 1f, 0x99000000.toInt()) }
    private var lampo = true
    private val batti = object : Runnable {
        override fun run() { lampo = !lampo; invalidate(); if (scrivendo) postDelayed(this, 530) }
    }

    /**
     * Dove si scrive: un testo, o la cella scelta di una tabella. [l] le sue righe, [x0],[y0] dove
     * cominciano dentro l'oggetto (punti del foglio, prima del giro).
     */
    private class Scritta(val e: Elemento, val r: Int, val c: Int, val testo: String, val segni: List<Segno>,
                          val l: android.text.StaticLayout, val x0: Float, val y0: Float) {
        val cella get() = if (r >= 0) r to c else null
    }

    private fun scritta(e: Elemento?, cel: Pair<Int, Int>? = cella): Scritta? {
        if (e == null) return null
        if (e.tipo == Elemento.TESTO) {
            val (mx, my) = GraficaDisegno.margini(e)
            return Scritta(e, -1, -1, e.testo, e.segni, GraficaDisegno.righe(context, e), mx, my)
        }
        if (e.tipo != Elemento.TABELLA || cel == null) return null
        val k = Tabelle.cella(e, cel.first, cel.second) ?: return null
        val (l, o) = GraficaDisegno.righeCella(context, e, cel.first, cel.second) ?: return null
        return Scritta(e, cel.first, cel.second, k.testo, k.segni, l, o.x, o.y)
    }

    /** Un punto delle righe (x,y, in punti del foglio) sullo schermo. */
    private fun suSchermo(sc: Scritta, x: Float, y: Float): Pair<Float, Float> {
        val e = sc.e
        val a = Math.toRadians(e.rot.toDouble())
        val dx = x + sc.x0 - e.w / 2f; val dy = y + sc.y0 - e.h / 2f
        val rx = (dx * cos(a) - dy * sin(a)).toFloat(); val ry = (dx * sin(a) + dy * cos(a)).toFloat()
        return ox + (e.cx + rx) * s to oy + (e.cy + ry) * s
    }

    /** Le due maniglie del pezzo (inizio, fine): il punto in fondo alla riga, sullo schermo. */
    private fun maniglie(sc: Scritta, p: IntRange): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        fun dove(i: Int): Pair<Float, Float> {
            val k = i.coerceIn(0, sc.testo.length)
            val riga = sc.l.getLineForOffset(k)
            return suSchermo(sc, sc.l.getPrimaryHorizontal(k), sc.l.getLineBottom(riga).toFloat())
        }
        return dove(p.first) to dove(p.last + 1)
    }

    /** Il pezzo scelto in rosa con le sue maniglie, o il cursore che lampeggia. */
    private fun disegnaScritta(c: Canvas, sc: Scritta) {
        val e = sc.e
        val p = pezzo?.takeIf { !it.isEmpty() && it.last < sc.testo.length }
        c.save()
        c.translate(ox + e.cx * s, oy + e.cy * s); c.rotate(e.rot)
        c.scale(s, s); c.translate(-e.w / 2f + sc.x0, -e.h / 2f + sc.y0)
        if (p != null) {
            val path = Path()
            sc.l.getSelectionPath(p.first, p.last + 1, path)
            c.drawPath(path, velaPezzo)
        } else if (scrivendo && lampo) {
            val k = cursore.coerceIn(0, sc.testo.length)
            val riga = sc.l.getLineForOffset(k)
            val x = sc.l.getPrimaryHorizontal(k)
            pennaRosa.strokeWidth = 2f * dp / s
            c.drawLine(x, sc.l.getLineTop(riga).toFloat(), x, sc.l.getLineBottom(riga).toFloat(), pennaRosa)
        }
        c.restore()
        if (p != null) {
            val (a, b) = maniglie(sc, p)
            pennaRosa.strokeWidth = 2f * dp
            for ((x, y) in listOf(a, b)) {
                c.drawLine(x, y - 2 * dp, x, y + 5 * dp, pennaRosa)
                c.drawCircle(x, y + 10 * dp, 5.5f * dp, pennaRosa)
            }
        }
    }

    /**
     * La riga scelta: un velo rosa e un filo (niente cornice tratteggiata che copre le righe vicine),
     * le prese ai lati per la larghezza, la maniglia per girare solo se il testo è alto sullo schermo.
     * Dentro: il pezzo scelto in rosa con le sue due maniglie, o il cursore che lampeggia.
     */
    private fun sceltaTesto(c: Canvas, e: Elemento) {
        c.save()
        c.translate(ox + e.cx * s, oy + e.cy * s); c.rotate(e.rot)
        val hw = e.w * s / 2f; val hh = e.h * s / 2f
        val r = RectF(-hw - 3 * dp, -hh, hw + 3 * dp, hh)
        c.drawRect(r, velaRiga)
        c.drawRect(r, bordoRiga)
        if (!scrivendo && pezzo == null) {
            for (lato in listOf(-1f, 1f)) c.drawRoundRect(RectF(lato * (hw + 3 * dp) - 3 * dp, -12 * dp, lato * (hw + 3 * dp) + 3 * dp, 12 * dp), 3 * dp, 3 * dp, presaLato)
            if (e.h * s >= 64 * dp) {
                val gy = hh + 34 * dp
                c.drawCircle(0f, gy, 15 * dp, maniglia)
                iconaGiro?.let { val k = (9 * dp).roundToInt(); it.setBounds(-k, (gy - k).roundToInt(), k, (gy + k).roundToInt()); it.draw(c) }
            }
        }
        c.restore()
        scritta(e)?.let { disegnaScritta(c, it) }
    }

    // ---------------------------------------------------------------- la lente (mock testo.html, #lente)

    /** La lettera mostrata nella lente mentre si trascina un pezzo o una sua maniglia; -1 = niente lente. */
    private var lenteI = -1
    private var lenteX = 0f
    private val lenteBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33000000; strokeWidth = 1f * dp }
    private val lenteOmbra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(18f * dp / 3f, 0f, 6f * dp / 3f, 0x77000000) }

    private fun lenteSu(i: Int, x: Float) {
        if (lenteI != i || lenteX != x) { lenteI = i; lenteX = x; invalidate() }
    }

    private fun lenteVia() {
        if (lenteI >= 0) { lenteI = -1; invalidate() }
    }

    /**
     * Sopra la riga, 132×52: la riga ingrandita attorno alla lettera che il dito copre
     * (almeno 1,6 volte lo schermo, e una riga alta almeno 24 dp anche a pagina intera), col pezzo in rosa
     * e un filo rosa dove arriva la maniglia.
     */
    // ---------------------------------------------------------------- il tasto «pagina intera»

    private val tastoFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (ContextCompat.getColor(context, R.color.riga) and 0x00FFFFFF) or 0xEB000000.toInt()
        setShadowLayer(8f * dp / 3f, 0f, 2f * dp / 3f, 0x88000000.toInt())
    }
    private val iconaIntera = ContextCompat.getDrawable(context, R.drawable.ic_tutto)?.mutate()

    /** Il centro del tasto, o null quando non serve: pagina già intera, o si scrive (c'è la tastiera). */
    private fun tastoIntera(): Pair<Float, Float>? {
        if (z <= 1.01f || scrivendo || modo == Modo.ZOOM) return null
        return width - 16 * dp - 24 * dp to height - 16 * dp - 24 * dp
    }

    private fun disegnaTastoIntera(c: Canvas) {
        val (x, y) = tastoIntera() ?: return
        c.drawCircle(x, y, 24 * dp, tastoFondo)
        iconaIntera?.let { val k = (12 * dp).roundToInt(); it.setBounds((x - k).roundToInt(), (y - k).roundToInt(), (x + k).roundToInt(), (y + k).roundToInt()); it.draw(c) }
    }

    private var suTastoIntera = false

    override fun onDrawForeground(c: Canvas) {
        super.onDrawForeground(c)
        disegnaTastoIntera(c)
        val gr = g ?: return
        if (lenteI < 0 || (modo != Modo.PEZZO && modo != Modo.MANICO)) return
        val sc = scritta(gr.trova(scelto)) ?: return
        val k = lenteI.coerceIn(0, sc.testo.length)
        val riga = sc.l.getLineForOffset(k)
        val alta = (sc.l.getLineBottom(riga) - sc.l.getLineTop(riga)).toFloat().coerceAtLeast(1f)
        val lx = sc.l.getPrimaryHorizontal(k)
        val ly = (sc.l.getLineTop(riga) + sc.l.getLineBottom(riga)) / 2f
        val (fx, fy) = suSchermo(sc, lx, ly)
        val (_, cima) = suSchermo(sc, lx, sc.l.getLineTop(riga).toFloat())
        val w = 132 * dp; val h = 52 * dp
        val cx = lenteX.coerceIn(w / 2f + 4 * dp, width - w / 2f - 4 * dp)
        val top = (cima - h - 28 * dp).coerceAtLeast(4 * dp)
        val r = RectF(cx - w / 2f, top, cx + w / 2f, top + h)
        val z = maxOf(s * 1.6f, 24 * dp / alta)
        c.drawRoundRect(r, h / 2f, h / 2f, lenteOmbra)
        c.save()
        val bordo = Path().apply { addRoundRect(r, h / 2f, h / 2f, Path.Direction.CW) }
        c.clipPath(bordo)
        // la lettera k al centro della lente: il foglio scala z attorno al suo punto
        c.translate(r.centerX(), r.centerY()); c.scale(z, z); c.translate(-pagX(fx), -pagY(fy))
        GraficaDisegno.disegna(context, c, gr, immagini, editor = true, pagina = pagina, conAbbondanza = !tagliata)
        val e = sc.e
        c.translate(e.cx, e.cy); c.rotate(e.rot); c.translate(-e.w / 2f + sc.x0, -e.h / 2f + sc.y0)
        pezzo?.takeIf { !it.isEmpty() && it.last < sc.testo.length }?.let { p ->
            val path = Path(); sc.l.getSelectionPath(p.first, p.last + 1, path); c.drawPath(path, velaPezzo)
        }
        pennaRosa.strokeWidth = 2f * dp / z
        c.drawLine(lx, sc.l.getLineTop(riga).toFloat(), lx, sc.l.getLineBottom(riga).toFloat(), pennaRosa)
        c.restore()
        c.drawRoundRect(r, h / 2f, h / 2f, lenteBordo)
    }

    // ---------------------------------------------------------------- avvicinarsi a una riga piccola

    private var corsa: android.animation.ValueAnimator? = null

    /**
     * Una riga appena scelta. Troppo piccola per il dito (meno di 16 dp sullo schermo): la pagina scivola
     * fino alla misura di lettura (riga di 20 dp, 2,6 dp per pt; se così esce dai
     * lati, dove entra intera purché resti di 14 dp), con la riga al centro di ciò che si vede. Già
     * leggibile ma coperta dal pannello o fuori dallo schermo: la pagina sale o scende quanto basta.
     * Mai indietro: si torna alla pagina intera con due dita o col doppio tocco sul vuoto.
     * Il pannello cambia l'altezza della vista dopo il tocco: si guarda al fotogramma dopo.
     */
    private fun avvicina(sc: Scritta, toccoX: Float) = post { avvicinaOra(sc, toccoX) }

    private fun avvicinaOra(sc: Scritta, toccoX: Float) {
        val gr = g ?: return
        if (!misure()) return
        val l = sc.l
        if (l.lineCount == 0) return
        val alta = (l.getLineBottom(0) - l.getLineTop(0)).toFloat()
        if (alta <= 0f) return
        var zt = z
        if (alta * s < 16 * dp) {
            val zLeggi = z * 20 * dp / (alta * s)
            val zMin = z * 14 * dp / (alta * s)
            val zEntra = z * (width - 32 * dp) / (l.width * s).coerceAtLeast(1f)
            zt = minOf(zLeggi, maxOf(zEntra, zMin)).coerceAtMost(ZOOM_MAX)
            if (zt <= z * 1.05f) zt = z
        }
        val (cx, cy) = suSchermo(sc, l.width / 2f, l.height / 2f)
        val ingrandisce = zt > z
        val mezza = l.height * s / 2f
        val margine = 24 * dp
        fun dentro(h: Float) = cy.coerceIn(margine + mezza, kotlin.math.max(margine + mezza, h - margine - mezza))
        if (!ingrandisce && dentro(height.toFloat()) == cy) return
        val stretta = l.width * s / z * zt <= width - 31 * dp
        val ax = if (ingrandisce && stretta) cx else toccoX
        val pfx = pagX(ax); val pfy = pagY(cy)
        val z0 = z
        corsa?.cancel()
        corsa = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                if (!misure()) return@addUpdateListener
                val s1 = s / z
                z = z0 + (zt - z0) * t
                val sn = s1 * z
                val vista = height.toFloat()
                val fx = if (ingrandisce && stretta) width / 2f else ax
                val fy = if (ingrandisce) vista / 2f else dentro(vista)
                val tx = ax + (fx - ax) * t
                val ty = cy + (fy - cy) * t
                px = pxPer(tx - pfx * sn, sn)
                py = ty - pfy * sn - (height - riservaSotto - gr.alto * sn) / 2f
                invalidate()
            }
            start()
        }
    }

    /** La lettera sotto il punto dello schermo (x,y). */
    private fun lettera(sc: Scritta, x: Float, y: Float): Int {
        val (lx, ly) = Grafiche.locale(sc.e, pagX(x), pagY(y))
        val riga = sc.l.getLineForVertical((ly - sc.y0).roundToInt().coerceIn(0, sc.l.height))
        return sc.l.getOffsetForHorizontal(riga, lx - sc.x0).coerceIn(0, sc.testo.length)
    }

    /** Dove scrive la tastiera: [i] nel testo scelto (-1 = smetti). */
    fun cursoreA(i: Int) {
        val sc = scritta(g?.trova(scelto))
        if (i < 0 || sc == null) {
            val era = scrivendo
            cursore = -1; casella = null
            removeCallbacks(batti)
            if (era) {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(this)
            }
            invalidate(); return
        }
        val nuova = !scrivendo
        cursore = i.coerceIn(0, sc.testo.length)
        val p = pezzo
        val cas = casella ?: SpannableStringBuilder(sc.testo).also { casella = it }
        if (cas.toString() != sc.testo) cas.replace(0, cas.length, sc.testo)
        if (p != null && !p.isEmpty()) Selection.setSelection(cas, p.first.coerceIn(0, cas.length), (p.last + 1).coerceIn(0, cas.length))
        else Selection.setSelection(cas, cursore)
        lampo = true; removeCallbacks(batti); postDelayed(batti, 530)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (nuova) {
            isFocusableInTouchMode = true; requestFocus()
            imm.restartInput(this)
            imm.showSoftInput(this, 0)
        } else {
            avvisaTastiera()
            // chiusa col tasto Indietro, la tastiera torna al primo tocco sul testo (prima: cursore senza tastiera)
            imm.showSoftInput(this, 0)
        }
        invalidate()
    }

    // ---------------------------------------------------------------- la tastiera scrive qui

    /** Il testo come lo vede la tastiera (con la parola che sta suggerendo); i segni li tiene l'editor. */
    private var casella: SpannableStringBuilder? = null

    override fun onCheckIsTextEditor() = scrivendo

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        if (!scrivendo) return null
        val cas = casella ?: return null
        estrattoId = -1
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        out.initialSelStart = Selection.getSelectionStart(cas); out.initialSelEnd = Selection.getSelectionEnd(cas)
        // la tastiera sa se il cursore è a inizio frase (maiuscola) o in mezzo (minuscola) e vede le
        // parole attorno: senza, apriva sempre in maiuscolo anche dentro «Lui ha saltato»
        out.initialCapsMode = android.text.TextUtils.getCapsMode(cas, out.initialSelStart.coerceAtLeast(0), out.inputType)
        if (android.os.Build.VERSION.SDK_INT >= 30) out.setInitialSurroundingText(cas)
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = casella ?: cas
            private fun dopo(ok: Boolean): Boolean { riporta(); return ok }
            override fun commitText(t: CharSequence?, n: Int) = dopo(super.commitText(t, n))
            override fun setComposingText(t: CharSequence?, n: Int) = dopo(super.setComposingText(t, n))
            override fun setComposingRegion(a: Int, b: Int) = dopo(super.setComposingRegion(a, b))
            override fun finishComposingText() = dopo(super.finishComposingText())
            override fun deleteSurroundingText(a: Int, b: Int) = dopo(super.deleteSurroundingText(a, b))
            override fun deleteSurroundingTextInCodePoints(a: Int, b: Int) = dopo(super.deleteSurroundingTextInCodePoints(a, b))
            override fun setSelection(a: Int, b: Int) = dopo(super.setSelection(a, b))
            override fun performEditorAction(a: Int): Boolean { scriviQui("\n"); return true }
            // la tastiera Samsung chiede solo il testo estratto: con null decide da sola (maiuscolo a metà parola)
            override fun getExtractedText(r: android.view.inputmethod.ExtractedTextRequest?, f: Int): android.view.inputmethod.ExtractedText? {
                val c = casella ?: return null
                if (f and InputConnection.GET_EXTRACTED_TEXT_MONITOR != 0) estrattoId = r?.token ?: 0
                return estratto(c)
            }
        }
    }

    override fun onKeyDown(code: Int, ev: KeyEvent): Boolean {
        if (!scrivendo) return super.onKeyDown(code, ev)
        val cas = casella ?: return super.onKeyDown(code, ev)
        when (code) {
            KeyEvent.KEYCODE_DEL -> {
                val a = Selection.getSelectionStart(cas); val b = Selection.getSelectionEnd(cas)
                if (a != b) cas.delete(minOf(a, b), maxOf(a, b)) else if (a > 0) cas.delete(a - 1, a)
                riporta(); return true
            }
            KeyEvent.KEYCODE_ENTER -> { scriviQui("\n"); return true }
        }
        val ch = ev.unicodeChar
        if (ch != 0 && !ev.isCtrlPressed) { scriviQui(ch.toChar().toString()); return true }
        return super.onKeyDown(code, ev)
    }

    private fun scriviQui(t: String) {
        val cas = casella ?: return
        val a = Selection.getSelectionStart(cas); val b = Selection.getSelectionEnd(cas)
        val da = minOf(a, b).coerceAtLeast(0)
        cas.replace(da, maxOf(a, b).coerceAtLeast(0), t)
        // il cursore dopo le lettere nuove: senza, la scelta restava attorno a loro e la lettera dopo le
        // sostituiva («Nuovo» scritto sopra «Your text» diventava «o»)
        Selection.setSelection(cas, (da + t.length).coerceAtMost(cas.length))
        riporta()
    }

    /** La casella è cambiata: il testo nuovo (coi segni che seguono) all'editor, il cursore qui. */
    private fun riporta() {
        val cas = casella ?: return
        val sc = scritta(g?.trova(scelto)) ?: return
        val nuovo = cas.toString()
        val sel = Selection.getSelectionStart(cas).coerceIn(0, nuovo.length)
        val selFine = Selection.getSelectionEnd(cas).coerceIn(0, nuovo.length)
        cursore = selFine
        pezzo = if (sel != selFine) minOf(sel, selFine) until maxOf(sel, selFine) else null
        lampo = true
        if (nuovo != sc.testo) ascoltatore?.testo(sc.e.id, sc.cella, nuovo, Ricco.dopoScrittura(sc.testo, nuovo, sc.segni))
        ascoltatore?.scrive(sc.e.id, cursore)
        avvisaTastiera()
        invalidate()
    }

    /** La tastiera che ha chiesto di seguire il testo (getExtractedText con MONITOR): il suo numero, o -1. */
    private var estrattoId = -1

    private fun estratto(c: CharSequence) = android.view.inputmethod.ExtractedText().apply {
        text = c.toString(); startOffset = 0; partialStartOffset = -1; partialEndOffset = -1
        selectionStart = Selection.getSelectionStart(c); selectionEnd = Selection.getSelectionEnd(c)
    }

    private fun avvisaTastiera() {
        val cas = casella ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (estrattoId >= 0) imm.updateExtractedText(this, estrattoId, estratto(cas))
        imm.updateSelection(this, Selection.getSelectionStart(cas), Selection.getSelectionEnd(cas),
            BaseInputConnection.getComposingSpanStart(cas), BaseInputConnection.getComposingSpanEnd(cas))
    }

    /** Dove stanno le maniglie per ingrandire, in frazioni del mezzo lato (-1..1). */
    private fun angoli(e: Elemento) = if (e.linea) listOf(-1f to 0f, 1f to 0f) else listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)

    // ---------------------------------------------------------------- dita

    private enum class Modo { NIENTE, VUOTO, SPOSTA, SCALA, GIRA, ZOOM, FERMO, COLONNA, RIGA, PEZZO, MANICO, LARGO }
    /** quale bordo della tabella si trascina */
    private var bordo = 0
    /** il dito è sceso su una tabella già scelta: se non si muove, sceglie una cella */
    private var suTabella = false
    private var modo = Modo.NIENTE
    private var base: Elemento? = null
    private var vivo: Grafica? = null
    private var x0 = 0f; private var y0 = 0f
    private var mosso = false
    private var d0 = 1f; private var a0 = 0f
    private var span0 = 1f; private var z0 = 1f; private var pfx = 0f; private var pfy = 0f
    private var px0 = 0f; private var py0 = 0f
    /** il foglio com'era quando il dito è sceso: se arriva il secondo dito, l'oggetto torna lì */
    private var prima: Grafica? = null
    private var ultimoId: Long? = null; private var ultimoT = 0L; private var vuotoT = 0L
    /** il dito è sceso sull'altra pagina del libro aperto: al dito alzato la apre o sfoglia; -1 = no */
    private var altraToccata = -1
    /** il testo sotto il dito era già scelto quando è sceso (un tocco allora = scrivi lì) */
    private var eraScelto = false
    /** tenendo premuto: la parola da cui si parte; MANICO: quale estremo (0 inizio, 1 fine); LARGO: quale lato */
    private var ancora: IntRange = 0 until 0
    private var estremo = 0
    private val tenuto = Runnable { tieniPremuto() }
    /**
     * Il libro da una lista: ogni cosa sta al suo posto. Un tocco sceglie (per correggere), un dito che
     * striscia gira la pagina e non trascina mai una parola (la registrazione sul telefono di prova, 06/11: «ENGLISH» trascinato via).
     */
    var fissi = false

    /** Pagina ingrandita: la riga toccata e non ancora scelta (diventa scelta al tocco, o tenendo premuto). */
    private var forse: Long? = null
    private val forseTenuto = Runnable {
        val f = forse
        if (f != null && !mosso && modo == Modo.VUOTO) {
            forse = null
            scelto = f; pezzo = null; ascoltatore?.scelto(f)
            modo = Modo.SPOSTA; base = g?.trova(f)
            tieniPremuto()
            if (modo != Modo.PEZZO) modo = Modo.FERMO
        }
    }

    private fun tieniPremuto() {
        val gr = g ?: return
        val sc = scritta(gr.trova(scelto)) ?: return
        if (mosso || modo != Modo.SPOSTA) return
        if (sc.e.tipo == Elemento.TABELLA) {
            // nella tabella si tiene premuto solo dentro la cella scelta
            val (lx, ly) = Grafiche.locale(sc.e, pagX(x0), pagY(y0))
            if (Tabelle.dove(sc.e, lx, ly) != sc.cella) return
        }
        if (scrivendo) { cursoreA(-1); ascoltatore?.scrive(sc.e.id, -1) }
        val w = Ricco.parola(sc.testo, lettera(sc, x0, y0))
        if (w.isEmpty()) return
        ancora = w; pezzo = w; modo = Modo.PEZZO; mosso = true
        lenteSu(lettera(sc, x0, y0), x0)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    /** Sul testo scelto: le maniglie del pezzo, le prese ai lati, la maniglia per girare se c'è. */
    private fun manigliaTesto(e: Elemento, x: Float, y: Float): Modo? {
        val p = pezzo
        if (p != null && !p.isEmpty()) {
            val sc = scritta(e) ?: return null
            val (a, b) = maniglie(sc, p)
            val da = hypot(x - a.first, y - (a.second + 10 * dp)); val db = hypot(x - b.first, y - (b.second + 10 * dp))
            if (minOf(da, db) <= 26 * dp) { estremo = if (da <= db) 0 else 1; return Modo.MANICO }
            return null
        }
        if (scrivendo || e.tipo != Elemento.TESTO) return null
        val (lx, ly) = inScelta(e, x, y)
        val hw = e.w * s / 2f + 3 * dp; val hh = e.h * s / 2f
        if (e.h * s >= 64 * dp && hypot(lx, ly - (hh + 34 * dp)) <= 24 * dp) return Modo.GIRA
        if (kotlin.math.abs(ly) <= maxOf(hh, 18 * dp)) for (lato in listOf(-1, 1))
            if (kotlin.math.abs(lx - lato * hw) <= 16 * dp && kotlin.math.abs(lx) >= hw - 16 * dp) { estremo = lato; return Modo.LARGO }
        return null
    }

    private fun pagX(x: Float) = (x - ox) / s
    private fun pagY(y: Float) = (y - oy) / s

    /** Il punto dello schermo nel sistema girato dell'oggetto, centrato (in pixel). */
    private fun inScelta(e: Elemento, x: Float, y: Float): Pair<Float, Float> {
        val a = Math.toRadians(-e.rot.toDouble())
        val dx = x - (ox + e.cx * s); val dy = y - (oy + e.cy * s)
        return (dx * cos(a) - dy * sin(a)).toFloat() to (dx * sin(a) + dy * cos(a)).toFloat()
    }

    private fun maniglia(e: Elemento, x: Float, y: Float): Modo? {
        val (lx, ly) = inScelta(e, x, y)
        val hw = e.w * s / 2f + 6 * dp; val hh = e.h * s / 2f + 6 * dp
        val r = 24 * dp
        if (e.tipo == Elemento.TABELLA) {
            val xs = Tabelle.xs(e); val ys = Tabelle.ys(e)
            val ox0 = -e.w * s / 2f; val oy0 = -e.h * s / 2f
            val presa = 18 * dp
            if (kotlin.math.abs(ly - (-hh - 3 * dp)) <= presa)
                (1 until xs.size - 1).minByOrNull { kotlin.math.abs(lx - (ox0 + xs[it] * s)) }
                    ?.takeIf { kotlin.math.abs(lx - (ox0 + xs[it] * s)) <= presa }?.let { bordo = it - 1; return Modo.COLONNA }
            if (kotlin.math.abs(lx - (-hw - 3 * dp)) <= presa)
                (1 until ys.size - 1).minByOrNull { kotlin.math.abs(ly - (oy0 + ys[it] * s)) }
                    ?.takeIf { kotlin.math.abs(ly - (oy0 + ys[it] * s)) <= presa }?.let { bordo = it - 1; return Modo.RIGA }
        }
        if (hypot(lx, ly - (hh + 34 * dp)) <= r) return Modo.GIRA
        // dentro l'oggetto vince sempre lo spostamento: le cose piccole si trascinano lo stesso
        val dentro = kotlin.math.abs(lx) <= e.w * s / 2f && kotlin.math.abs(ly) <= e.h * s / 2f
        if (!dentro) for ((mx, my) in angoli(e)) if (hypot(lx - mx * hw, ly - my * hh) <= r) return Modo.SCALA
        return null
    }

    private fun emetti(n: Grafica, fine: Boolean) {
        vivo = n; g = n; invalidate()
        ascoltatore?.gesto(n, fine)
    }

    private fun conElemento(e: Elemento): Grafica? = g?.let { Grafiche.sostituisci(it, e) }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val gr = g ?: return false
        if (!misure()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                forse = null; removeCallbacks(forseTenuto)
                val altra = compagna(gr)
                if (altra >= 0 && !scrivendo) {
                    val ax = if (aSinistra(pagina)) ox + Grafica.LARGO * s else ox - Grafica.LARGO * s
                    if (ev.x >= ax && ev.x <= ax + Grafica.LARGO * s && ev.y >= oy && ev.y <= oy + gr.alto * s) {
                        // un tocco sull'altra pagina del libro aperto la apre (resta dov'è), al dito alzato:
                        // se il dito corre di lato è una sfogliata
                        x0 = ev.x; y0 = ev.y; mosso = false; altraToccata = altra; toccato = null
                        modo = Modo.VUOTO
                        return true
                    }
                }
                suTastoIntera = tastoIntera()?.let { (x, y) -> hypot(ev.x - x, ev.y - y) <= 28 * dp } == true
                if (suTastoIntera) return true
                corsa?.takeIf { it.isRunning }?.end()
                x0 = ev.x; y0 = ev.y; mosso = false; vivo = null; prima = gr; px0 = px; py0 = py
                toccato = null
                if (insieme.size > 1 || aggiungi) {
                    val id = Grafiche.tocca(gr, pagX(ev.x), pagY(ev.y), 10 * dp / s, pagina)
                    if (id != null && (id in insieme || id == scelto)) {
                        // si trascinano tutti; un tocco fermo con «Select more» lo toglie
                        toccato = id; modo = Modo.SPOSTA; base = gr.trova(id); eraScelto = true
                        return true
                    }
                    if (id != null && aggiungi) { toccato = id; modo = Modo.FERMO; return true }
                    if (id == null) { modo = Modo.VUOTO; return true }
                    // senza «Select more» un altro oggetto lo sceglie da solo (sotto, come sempre)
                }
                val sc = gr.trova(scelto)
                val m = sc?.let {
                    when {
                        it.tipo == Elemento.TESTO -> manigliaTesto(it, ev.x, ev.y)
                        it.tipo == Elemento.TABELLA && pezzo != null -> manigliaTesto(it, ev.x, ev.y) ?: maniglia(it, ev.x, ev.y)
                        else -> maniglia(it, ev.x, ev.y)
                    }
                }
                if (sc != null && m != null) {
                    modo = m; base = sc
                    val cx = ox + sc.cx * s; val cy = oy + sc.cy * s
                    d0 = hypot(ev.x - cx, ev.y - cy).coerceAtLeast(1f)
                    a0 = Math.toDegrees(atan2((ev.y - cy).toDouble(), (ev.x - cx).toDouble())).toFloat()
                    mostraGradi = m == Modo.GIRA
                    if (m == Modo.MANICO) pezzo?.let { p -> lenteSu(if (estremo == 0) p.first else p.last + 1, ev.x) }
                    invalidate()
                    return true
                }
                val id = Grafiche.tocca(gr, pagX(ev.x), pagY(ev.y), 10 * dp / s, pagina)
                if (id == null) {
                    modo = Modo.VUOTO
                    if (ev.eventTime - vuotoT < 320) { vuotoT = 0L; modo = Modo.FERMO; tornaIntera() } else vuotoT = ev.eventTime
                    return true
                }
                val ora = ev.eventTime
                val tab = gr.trova(id)?.takeIf { it.tipo == Elemento.TABELLA }
                suTabella = tab != null && id == scelto
                if (tab != null && id == ultimoId && ora - ultimoT < 320) {
                    val (lx, ly) = Grafiche.locale(tab, pagX(ev.x), pagY(ev.y))
                    Tabelle.dove(tab, lx, ly)?.let { (r, k) ->
                        // doppio tocco su una cella: lei scelta e la parola sotto il dito
                        ultimoId = null; modo = Modo.FERMO
                        if (scrivendo) { cursoreA(-1); ascoltatore?.scrive(id, -1) }
                        ascoltatore?.riscriviCella(id, r, k)
                        scritta(tab, r to k)?.let { sc ->
                            val w = Ricco.parola(sc.testo, lettera(sc, ev.x, ev.y))
                            if (!w.isEmpty() && sc.testo.isNotEmpty()) { pezzo = w; ascoltatore?.pezzo(id, w) }
                        }
                        return true
                    }
                }
                val testo = gr.trova(id)?.takeIf { it.tipo == Elemento.TESTO }
                if (testo != null && id == ultimoId && ora - ultimoT < 320) {
                    // doppio tocco su un testo: la parola
                    ultimoId = null; modo = Modo.FERMO
                    if (scrivendo) { cursoreA(-1); ascoltatore?.scrive(id, -1) }
                    val w = scritta(testo)?.let { Ricco.parola(it.testo, lettera(it, ev.x, ev.y)) } ?: (0 until 0)
                    if (!w.isEmpty()) { pezzo = w; ascoltatore?.pezzo(id, w) }
                    return true
                }
                ultimoId = id; ultimoT = ora
                eraScelto = id == scelto
                if ((z > 1.01f || fissi) && id != scelto) {
                    // pagina ingrandita: è quasi tutta righe, il dito che scorre sposta la pagina, non la riga
                    // sotto di lui; un tocco la sceglie, tenere premuto sceglie la parola. Si sposta solo ciò
                    // che è già scelto.
                    forse = id; modo = Modo.VUOTO
                    if (testo != null) postDelayed(forseTenuto, 420)
                    return true
                }
                if (id != scelto) { scelto = id; pezzo = null; ascoltatore?.scelto(id) }
                modo = Modo.SPOSTA; base = gr.trova(id)
                if (testo != null || (tab != null && eraScelto)) postDelayed(tenuto, 420)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (suTastoIntera) return true
                forse = null; removeCallbacks(forseTenuto)
                removeCallbacks(tenuto)
                if (ev.pointerCount == 2) {
                    // il primo dito stava spostando qualcosa: torna dov'era, nessun passo in Annulla
                    val p = prima
                    if (vivo != null && p != null) { g = p; ascoltatore?.gesto(p, false) }
                    vivo = null; base = null; mostraGradi = false; guidaX = null; guidaY = null
                    corsa?.cancel()
                    modo = Modo.ZOOM; mosso = true; z0 = z
                    span0 = hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0)).coerceAtLeast(1f)
                    val fx = (ev.getX(0) + ev.getX(1)) / 2f; val fy = (ev.getY(0) + ev.getY(1)) / 2f
                    pfx = pagX(fx); pfy = pagY(fy)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (suTastoIntera) return true
                if (modo == Modo.ZOOM) {
                    if (ev.pointerCount < 2) return true
                    val span = hypot(ev.getX(1) - ev.getX(0), ev.getY(1) - ev.getY(0))
                    val fx = (ev.getX(0) + ev.getX(1)) / 2f; val fy = (ev.getY(0) + ev.getY(1)) / 2f
                    // il punto della pagina fra le dita resta fra le dita
                    val s1 = s / z
                    z = (z0 * span / span0).coerceIn(1f, ZOOM_MAX)
                    val sn = s1 * z
                    px = pxPer(fx - pfx * sn, sn)
                    py = fy - pfy * sn - (height - riservaSotto - gr.alto * sn) / 2f
                    invalidate()
                    return true
                }
                if (modo == Modo.VUOTO) {
                    if (!mosso && hypot(ev.x - x0, ev.y - y0) < slop) return true
                    mosso = true; vuotoT = 0L
                    if (altraToccata >= 0) return true
                    // la pagina intera che sta tutta nello schermo non si trascina in giro
                    if (z <= 1.01f) return true
                    px = px0 + (ev.x - x0); py = py0 + (ev.y - y0)
                    invalidate()
                    return true
                }
                if (modo == Modo.PEZZO || modo == Modo.MANICO) {
                    val sc = scritta(gr.trova(scelto)) ?: return true
                    val i = lettera(sc, ev.x, ev.y)
                    lenteSu(i, ev.x)
                    pezzo = if (modo == Modo.PEZZO) {
                        val w = Ricco.parola(sc.testo, i).takeIf { !it.isEmpty() } ?: (i until i)
                        minOf(ancora.first, w.first) until maxOf(ancora.last + 1, w.last + 1)
                    } else {
                        val p = pezzo ?: return true
                        if (estremo == 0) minOf(i, p.last) until p.last + 1 else p.first until maxOf(i, p.first + 1)
                    }
                    return true
                }
                val b = base ?: return true
                if (!mosso && hypot(ev.x - x0, ev.y - y0) < slop) return true
                removeCallbacks(tenuto)
                // un testo col pezzo scelto o con la tastiera aperta non scivola via sotto il dito
                if (modo == Modo.SPOSTA && (pezzo != null || scrivendo)) return true
                // nel libro da una lista niente si sposta: il dito che corre diventa una sfogliata (o sposta la pagina ingrandita)
                if (modo == Modo.SPOSTA && fissi) { mosso = true; modo = Modo.VUOTO; base = null; return true }
                mosso = true
                val n: Elemento = when (modo) {
                    Modo.SPOSTA -> {
                        val nx = b.x + (ev.x - x0) / s; val ny = b.y + (ev.y - y0) / s
                        val tutti = insieme + listOfNotNull(scelto)
                        if (tutti.size > 1 && b.id in tutti) {
                            // tutti insieme: si aggancia il riquadro che li tiene, agli altri oggetti (non a loro)
                            val p0 = prima ?: gr
                            val r = Grafiche.ingombroDi(p0, tutti) ?: return true
                            val finto = Elemento(-1, Elemento.FORMA, r.l, r.t, r.r - r.l, r.b - r.t, pagina = b.pagina)
                            val dx0 = nx - b.x; val dy0 = ny - b.y
                            val a = Grafiche.aggancia(p0.copy(elementi = p0.elementi.filter { it.id !in tutti }), finto, r.l + dx0, r.t + dy0, 8 * dp / s, conGuide)
                            guidaX = a.guidaX; guidaY = a.guidaY
                            mosso = true
                            emetti(Grafiche.spostaTutti(p0, tutti, dx0 + a.dx, dy0 + a.dy), false)
                            return true
                        }
                        if (dentroFoto && b.tipo == Elemento.FOTO && b.id == scelto) {
                            val bm = immagini.di(b.uri) ?: return true
                            // il dito nel verso della foto (girata anche lei)
                            val ra = Math.toRadians(-b.rot.toDouble())
                            val ddx = (ev.x - x0) / s; val ddy = (ev.y - y0) / s
                            val lx = (ddx * cos(ra) - ddy * sin(ra)).toFloat(); val ly = (ddx * sin(ra) + ddy * cos(ra)).toFloat()
                            mosso = true
                            conElemento(Grafiche.spostaFoto(b, bm.width.toFloat(), bm.height.toFloat(), lx, ly))?.let { emetti(it, false) }
                            return true
                        }
                        val a = Grafiche.aggancia(gr, b, nx, ny, 8 * dp / s, conGuide)
                        guidaX = a.guidaX; guidaY = a.guidaY
                        b.copy(x = nx + a.dx, y = ny + a.dy)
                    }
                    Modo.SCALA -> {
                        val cx = ox + b.cx * s; val cy = oy + b.cy * s
                        testoGiusto(Grafiche.scala(b, hypot(ev.x - cx, ev.y - cy) / d0))
                    }
                    Modo.LARGO -> {
                        val a = Math.toRadians(-b.rot.toDouble())
                        val d = (((ev.x - x0) * cos(a) - (ev.y - y0) * sin(a)) / s).toFloat()
                        var w = (b.w + estremo * d).coerceAtLeast(Elemento.MIN)
                        // dritto, il lato tirato si attacca a margini, colonne e agli altri oggetti
                        guidaX = null
                        if (b.rot == 0f) {
                            val lato = if (estremo > 0) b.x + w else b.x + b.w - w
                            Grafiche.agganciaLato(gr, b, lato, 8 * dp / s, conGuide)?.let { x ->
                                val w2 = if (estremo > 0) x - b.x else b.x + b.w - x
                                if (w2 >= Elemento.MIN) { w = w2; guidaX = x }
                            }
                        }
                        // si tira un lato: l'altro resta fermo (giro compreso), la cima pure
                        val sh = (w - b.w) / 2f * estremo
                        val ra = Math.toRadians(b.rot.toDouble())
                        val ncx = b.cx + (sh * cos(ra)).toFloat(); val ncy = b.cy + (sh * sin(ra)).toFloat()
                        val n = b.copy(w = w, x = ncx - w / 2f, y = ncy - b.h / 2f)
                        n.copy(h = GraficaDisegno.altezzaTesto(context, n))
                    }
                    Modo.COLONNA, Modo.RIGA -> {
                        val (lx, ly) = Grafiche.locale(b, pagX(ev.x), pagY(ev.y))
                        if (modo == Modo.COLONNA) Tabelle.spostaColonna(b, bordo, lx) else Tabelle.spostaRiga(b, bordo, ly)
                    }
                    Modo.GIRA -> {
                        val cx = ox + b.cx * s; val cy = oy + b.cy * s
                        val a = Math.toDegrees(atan2((ev.y - cy).toDouble(), (ev.x - cx).toDouble())).toFloat()
                        b.copy(rot = Grafiche.gradi(b.rot + a - a0))
                    }
                    else -> return true
                }
                conElemento(n)?.let { emetti(it, false) }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (modo == Modo.ZOOM) { modo = Modo.FERMO; prima = null }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (suTastoIntera) {
                    suTastoIntera = false
                    if (ev.actionMasked == MotionEvent.ACTION_UP) tornaIntera()
                    return true
                }
                val altraQui = altraToccata; altraToccata = -1
                if (altraQui >= 0) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val v = Sfoglia.verso(ev.x - x0, ev.y - y0, ev.eventTime - ev.downTime, dp)
                        if (mosso && v != 0) ascoltatore?.sfoglia(v) else if (!mosso) ascoltatore?.apriPagina(altraQui)
                    }
                    modo = Modo.NIENTE
                    return true
                }
                removeCallbacks(tenuto)
                if (modo == Modo.SPOSTA && mosso && ev.actionMasked == MotionEvent.ACTION_UP) oltreLaPiega(gr)
                if ((insieme.size > 1 || aggiungi) && toccato != null) {
                    val t = toccato; toccato = null
                    if (!mosso && aggiungi && ev.actionMasked == MotionEvent.ACTION_UP && t != null) ascoltatore?.tocca(t)
                    fine(); modo = Modo.NIENTE
                    return true
                }
                if (modo == Modo.PEZZO || modo == Modo.MANICO) {
                    lenteVia()
                    gr.trova(scelto)?.let { ascoltatore?.pezzo(it.id, pezzo?.takeIf { p -> !p.isEmpty() }) }
                    modo = Modo.NIENTE; mosso = false; vivo = null; base = null
                    return true
                }
                if (modo == Modo.SPOSTA && !mosso && eraScelto && ev.actionMasked == MotionEvent.ACTION_UP) {
                    gr.trova(scelto)?.takeIf { it.tipo == Elemento.TESTO }?.let { t ->
                        // un tocco sul testo già scelto: il cursore lì, la tastiera si apre
                        val i = scritta(t)?.let { lettera(it, ev.x, ev.y) } ?: t.testo.length
                        pezzo = null; ascoltatore?.pezzo(t.id, null)
                        cursoreA(i); ascoltatore?.scrive(t.id, i)
                        fine(); modo = Modo.NIENTE
                        return true
                    }
                }
                if (modo == Modo.SPOSTA && !mosso && !eraScelto && ev.actionMasked == MotionEvent.ACTION_UP) {
                    gr.trova(scelto)?.takeIf { it.tipo == Elemento.TESTO }?.let { t -> scritta(t)?.let { avvicina(it, ev.x) } }
                }
                if (modo == Modo.SPOSTA && suTabella && !mosso && ev.actionMasked == MotionEvent.ACTION_UP) {
                    gr.trova(scelto)?.let { t ->
                        val (lx, ly) = Grafiche.locale(t, pagX(ev.x), pagY(ev.y))
                        Tabelle.dove(t, lx, ly)?.let { (r, k) ->
                            val sc = if (cella == r to k) scritta(t, r to k) else null
                            if (sc != null) {
                                // un tocco sulla cella già scelta: si scrive lì, come in una riga
                                val i = lettera(sc, ev.x, ev.y)
                                pezzo = null; ascoltatore?.pezzo(t.id, null)
                                cursoreA(i); ascoltatore?.scrive(t.id, i)
                            } else {
                                ascoltatore?.cella(t.id, r, k)
                                scritta(t, r to k)?.let { avvicina(it, ev.x) }
                            }
                        }
                    }
                }
                if (modo == Modo.VUOTO && forse != null) {
                    val f = forse; forse = null; removeCallbacks(forseTenuto)
                    if (mosso && ev.actionMasked == MotionEvent.ACTION_UP && z <= 1.01f && !scrivendo) {
                        val v = Sfoglia.verso(ev.x - x0, ev.y - y0, ev.eventTime - ev.downTime, dp)
                        if (v != 0) ascoltatore?.sfoglia(v)
                    }
                    if (!mosso && ev.actionMasked == MotionEvent.ACTION_UP && f != null) {
                        scelto = f; pezzo = null; ascoltatore?.scelto(f)
                        gr.trova(f)?.takeIf { it.tipo == Elemento.TESTO }?.let { t -> scritta(t)?.let { avvicina(it, ev.x) } }
                    }
                    fine(); modo = Modo.NIENTE
                    return true
                }
                // sfogliare come un libro: pagina intera, un dito sul vuoto che corre di lato
                if (modo == Modo.VUOTO && mosso && ev.actionMasked == MotionEvent.ACTION_UP && z <= 1.01f && !scrivendo) {
                    val dx = ev.x - x0; val dy = ev.y - y0
                    if (Sfoglia.verso(dx, dy, ev.eventTime - ev.downTime, dp) != 0) ascoltatore?.sfoglia(Sfoglia.verso(dx, dy, ev.eventTime - ev.downTime, dp))
                }
                if (modo == Modo.VUOTO && !mosso && ev.actionMasked == MotionEvent.ACTION_UP && hypot(ev.x - x0, ev.y - y0) < slop) {
                    if (scelto != null) { scelto = null; ascoltatore?.scelto(null) }
                    performClick()
                }
                fine()
                modo = Modo.NIENTE
                return true
            }
        }
        return true
    }

    /** Un testo ingrandito: l'altezza vera la danno le righe, il centro resta dov'era. */
    private fun testoGiusto(e: Elemento): Elemento {
        if (e.tipo != Elemento.TESTO) return e
        val h = GraficaDisegno.altezzaTesto(context, e)
        return e.copy(y = e.cy - h / 2f, h = h)
    }

    private fun fine() {
        guidaX = null; guidaY = null; mostraGradi = false
        val v = vivo
        if (v != null && mosso) { vivo = null; ascoltatore?.gesto(v, true) }
        vivo = null; base = null
        val p = daAprire; val id = daScegliere
        daAprire = -1; daScegliere = null
        if (p >= 0) { ascoltatore?.apriPagina(p); if (id != null) ascoltatore?.scelto(id) }
        invalidate()
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        /** 8× basta per leggere il testo di 5 pt del quaderno grande come quello dell'app */
        const val ZOOM_MAX = 8f
    }
}
