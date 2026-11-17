package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Come si disegna un «Design». La STESSA funzione serve lo schermo, le miniature, il PNG e il
 * PDF: tutto in punti del foglio (largo 1080), chi chiama sceglie solo la scala.
 * I testi si impaginano in punti, mai in pixel: così vanno a capo uguale a ogni scala.
 */
object GraficaDisegno {
    /** Le foto: uri → immagine pronta (o null se non è ancora arrivata / non si legge). */
    fun interface Immagini { fun di(uri: String): Bitmap? }

    private val pennello = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val vuoto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2530.toInt() }
    private val vuotoSegno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA79FB0.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val vuotoScritta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA79FB0.toInt(); textAlign = Paint.Align.CENTER }

    /**
     * Una pagina, in punti, dentro (0,0)-(1080,alto) del [c]. [editor] = mostra i posti
     * vuoti per le foto (nel file non si vedono). [conAbbondanza] = disegna anche la fascia oltre
     * il taglio (da -abbondanza a 1080+abbondanza): la vogliono il PDF per la tipografia e l'editor.
     */
    fun disegna(ctx: Context, c: Canvas, g: Grafica, foto: Immagini, editor: Boolean, pagina: Int = 0, conAbbondanza: Boolean = false) {
        val ab = if (conAbbondanza) g.abbondanza else 0f
        val tutto = RectF(-ab, -ab, Grafica.LARGO + ab, g.alto + ab)
        c.save()
        c.clipRect(tutto)
        c.drawColor(g.sfondo)
        if (g.fotoSfondo.isNotEmpty()) {
            foto.di(g.fotoSfondo)?.let { b ->
                // la foto di sfondo copre anche l'abbondanza, centrata sulla pagina tagliata
                riempi(c, b, tutto)
                if (g.vela > 0) c.drawColor(((g.vela.coerceIn(0, Grafica.VELA_MAX) * 255 / 100) shl 24))
            }
        }
        // solo ciò che tocca la parte visibile (ingrandita sullo schermo, o nella lente: una striscia);
        // un oggetto girato occupa al massimo il cerchio della sua diagonale (+ ombre e contorni)
        val vista = android.graphics.Rect()
        val tutta = !c.getClipBounds(vista)
        // prima la pagina madre (sotto tutto), col numero di questa pagina al posto di «#»
        val madre = if (pagina >= 0) g.madre(pagina) else 0
        val quali = if (madre > 0) g.elementi.filter { it.pagina == -madre }.map { Grafiche.numera(it, pagina + 1) } + g.elementi.filter { it.pagina == pagina }
            else g.elementi.filter { it.pagina == pagina }
        for (e in quali) {
            if (!tutta) {
                val r = kotlin.math.hypot(e.w, e.h) / 2f + e.misura
                if (e.cx + r < vista.left || e.cx - r > vista.right || e.cy + r < vista.top || e.cy - r > vista.bottom) continue
            }
            elemento(ctx, c, e, foto, editor)
        }
        c.restore()
    }

    fun elemento(ctx: Context, c: Canvas, e: Elemento, foto: Immagini, editor: Boolean) {
        if (e.opacita <= 0) return
        // saveLayerAlpha è un secondo save: si torna al punto di partenza, mai un restore solo
        // (prima lo spostamento di un oggetto trasparente restava addosso a tutti quelli dopo)
        val n = c.save()
        c.translate(e.cx, e.cy)
        if (e.rot != 0f) c.rotate(e.rot)
        c.translate(-e.w / 2f, -e.h / 2f)
        if (e.opacita < 100) {
            // il velo dell'opacità copre anche ombre e contorni che escono dal riquadro
            val m = max(e.w, e.h)
            c.saveLayerAlpha(-m, -m, e.w + m, e.h + m, e.opacita * 255 / 100)
        }
        when (e.tipo) {
            Elemento.TESTO -> testo(ctx, c, e, editor)
            Elemento.FOTO -> fotoIn(c, e, foto, editor)
            Elemento.DISEGNO -> ManoDisegno.disegna(c, e.tratti, e.w, e.h)
            Elemento.TABELLA -> tabella(ctx, c, e, editor)
            else -> forma(c, e)
        }
        c.restoreToCount(n)
    }

    // ---------------------------------------------------------------- testi

    fun margini(e: Elemento): Pair<Float, Float> =
        if (e.stile == Testo.RIQUADRO) e.misura * 0.35f to e.misura * 0.2f else 0f to 0f

    /**
     * Le lettere a misura vera: senza LINEAR/SUBPIXEL Android arrotonda ogni lettera al pixel intero
     * della misura (il foglio è in punti: 6 pt = 15 punti) e i testi piccoli venivano stretti del 5%
     * e con gli spazi storti (pagina 71, 05/11). Una ricetta sola: schermo, PNG e PDF misurano uguale.
     */
    const val LETTERE = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG

    private fun pittore(ctx: Context, e: Elemento) = TextPaint(LETTERE).apply {
        typeface = Caratteri.di(ctx, e.font)
        textSize = e.misura
    }

    private fun impagina(ctx: Context, e: Elemento, p: TextPaint): StaticLayout {
        val (mx, _) = margini(e)
        return impagina(ctx, e.testo, e.segni, e.font, e.allinea, p, e.w - 2 * mx)
    }

    /** Ciò che decide le righe di un testo (il colore lo mette la penna al momento di disegnare). */
    private data class Chiave(val testo: String, val segni: List<Segno>, val font: String, val allinea: Int,
                              val misura: Float, val largo: Float, val bordo: Float)

    /**
     * Le righe già fatte, solo per lo schermo: la pagina 71 ha 139 righe e rifarle a ogni fotogramma
     * costava ~69 ms (14 al secondo, misurato sul telefono di prova col dito che scorre). Il PDF e il PNG (altro
     * thread) le rifanno ogni volta: la penna di una riga in cache cambia colore a ogni disegno.
     */
    private val giaFatte = android.util.LruCache<Chiave, StaticLayout>(800)

    private fun suSchermo() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** Le righe di [e] con la loro penna: [bordo] > 0 = la penna del contorno, spessa tanto. */
    private fun righeDi(ctx: Context, e: Elemento, bordo: Float = 0f): StaticLayout {
        fun fai(): StaticLayout {
            val p = pittore(ctx, e)
            if (bordo > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bordo; p.strokeJoin = Paint.Join.ROUND }
            return impagina(ctx, e, p)
        }
        if (!suSchermo()) return fai()
        val k = Chiave(e.testo, e.segni, e.font, e.allinea, e.misura, e.w - 2 * margini(e).first, bordo)
        return giaFatte.get(k) ?: fai().also { giaFatte.put(k, it) }
    }

    /**
     * Le righe di un testo largo [largo] punti, coi pezzi misti: grassetto e corsivo coi file veri
     * del font quando li ha ([Caratteri.variante]), sottolineato come lo fa il telefono.
     */
    fun impagina(ctx: Context, testo: String, segni: List<Segno>, font: String, allinea: Int, p: TextPaint, largo: Float): StaticLayout {
        val al = when (allinea) {
            Elemento.SINISTRA -> Layout.Alignment.ALIGN_NORMAL
            Elemento.DESTRA -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val t: CharSequence = if (testo.isEmpty()) " " else if (segni.isEmpty()) testo else misto(ctx, testo, segni, font)
        return StaticLayout.Builder.obtain(t, 0, t.length, p, largo.toInt().coerceAtLeast(1))
            .setAlignment(al).setIncludePad(false).setLineSpacing(0f, 1f).build()
    }

    /**
     * L'aspetto di un pezzo di testo: font intero (TypefaceSpan(Typeface) c'è solo dal 9), misura in
     * punti del foglio come numero con la virgola (AbsoluteSizeSpan vuole pixel interi: 6 pt = 15,4
     * diventerebbe 15) e colore. null / 0 = come il resto del testo.
     */
    private class Carattere(val tf: Typeface?, val misura: Float = 0f, val colore: Int = 0) : MetricAffectingSpan() {
        private fun metti(tp: TextPaint) {
            if (tf != null) tp.typeface = tf
            if (misura > 0f) tp.textSize = misura
        }
        override fun updateDrawState(tp: TextPaint) { metti(tp); if (colore != 0) tp.color = colore }
        override fun updateMeasureState(tp: TextPaint) { metti(tp) }
    }

    fun misto(ctx: Context, testo: String, segni: List<Segno>, font: String): SpannableString {
        val sp = SpannableString(testo)
        for (g in Ricco.normalizza(testo.length, segni)) {
            val forma = g.stile and (Ricco.GRASSETTO or Ricco.CORSIVO)
            val f = g.font.ifEmpty { font }
            val tf = if (forma != 0 || g.font.isNotEmpty()) Caratteri.variante(ctx, f, forma) else null
            if (tf != null || g.misura > 0f || g.colore != 0)
                sp.setSpan(Carattere(tf, g.misura, g.colore), g.da, g.a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (g.stile and Ricco.SOTTOLINEATO != 0) sp.setSpan(UnderlineSpan(), g.da, g.a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    /** Le righe di un testo come si disegnano (per il cursore e la scelta sulla pagina), dentro i [margini]. */
    fun righe(ctx: Context, e: Elemento): StaticLayout = righeDi(ctx, e)

    /**
     * Le righe della cella (r,c) di una tabella come si disegnano, e dove cominciano dentro la
     * tabella (punti del foglio, prima del giro): per scrivere nella cella sulla pagina.
     */
    fun righeCella(ctx: Context, e: Elemento, r: Int, c: Int): Pair<StaticLayout, android.graphics.PointF>? {
        val cel = Tabelle.cella(e, r, c) ?: return null
        val xs = Tabelle.xs(e); val ys = Tabelle.ys(e)
        val u = Tabelle.larga(e, r, c)
        val m = margineCella(e)
        val p = TextPaint(LETTERE).apply { typeface = Caratteri.di(ctx, e.font); textSize = e.misura; color = e.colore }
        val l = impagina(ctx, cel.testo, cel.segni, e.font, cel.allinea, p, xs[c + u] - xs[c] - 2 * m)
        return l to android.graphics.PointF(xs[c] + m, ys[r] + m)
    }

    /** L'altezza vera di un testo (righe, a capo, riquadro), in punti. */
    fun altezzaTesto(ctx: Context, e: Elemento): Float {
        // un riquadro del filo ha l'alto suo: il testo che non ci sta va nel prossimo
        if (e.filo != 0L) return e.h
        val (_, my) = margini(e)
        return impagina(ctx, e, pittore(ctx, e)).height + 2 * my
    }

    /** Quante lettere di [testo] stanno nel riquadro [e] (righe intere, dentro l'alto del riquadro). */
    fun quanteCiStanno(ctx: Context, e: Elemento, testo: String, segni: List<Segno>): Int {
        if (testo.isEmpty()) return 0
        val (mx, my) = margini(e)
        val l = impagina(ctx, testo, segni, e.font, e.allinea, pittore(ctx, e), e.w - 2 * mx)
        val alto = e.h - 2 * my + 0.5f
        var n = 0
        for (i in 0 until l.lineCount) { if (l.getLineBottom(i) <= alto) n = l.getLineEnd(i) else break }
        return n
    }

    /**
     * Il filo [k] rifatto: tutto il testo
     * dei suoi riquadri si versa di nuovo dal primo, ognuno tiene ciò che ci sta. Con [crea] il testo che avanza
     * apre riquadri nuovi (colonna dopo, poi pagina dopo, pagine nuove con la stessa madre) fino a [Grafica.PAGINE_MAX].
     */
    fun scorri(ctx: Context, g0: Grafica, k: Long, crea: Boolean): Grafica {
        var g = g0
        val riquadri = Grafiche.filoDi(g, k).toMutableList()
        if (riquadri.isEmpty()) return g
        val (tutto, segniTutti) = Grafiche.testoDelFilo(riquadri)
        val nuovi = HashMap<Long, Elemento>()
        var da = 0
        var i = 0
        while (i < riquadri.size) {
            val e = riquadri[i]
            val n = quanteCiStanno(ctx, e, tutto.substring(da), Grafiche.segniDi(segniTutti, da, tutto.length))
            var fino = da + n
            if (i == riquadri.lastIndex && fino < tutto.length) {
                val d = Grafiche.dopo(g, e)
                val p = d[0].toInt()
                if (crea && n > 0 && p < Grafica.PAGINE_MAX) {
                    // il testo avanza: un riquadro nuovo (colonna dopo, o pagina dopo; pagine nuove con la stessa madre)
                    if (p >= g.pagine) g = Grafiche.aggiungiPagine(g, g.pagine - 1, p - g.pagine + 1).first
                    val nuovo = e.copy(id = Grafiche.prossimoId(g), pagina = p, x = d[1], y = d[2], w = d[3], h = d[4],
                        testo = "", segni = emptyList(), filoN = e.filoN + 1, gruppo = 0L)
                    g = g.copy(elementi = (g.elementi + nuovo).sortedBy { it.pagina })
                    riquadri += nuovo
                } else fino = tutto.length // l'ultimo tiene ciò che avanza: non si vede, nell'editor c'è il segno rosso
            }
            nuovi[e.id] = e.copy(testo = tutto.substring(da, fino), segni = Grafiche.segniDi(segniTutti, da, fino))
            da = fino
            i++
        }
        return g.copy(elementi = g.elementi.map { nuovi[it.id] ?: it })
    }

    /** Ogni testo con la sua altezza vera: chi aggancia e chi ancora deve conoscerla. */
    /**
     * Ogni testo prende la sua altezza vera. [gia] = una grafica già misurata: i suoi elementi rimasti identici (lo
     * stesso oggetto: l'altezza dipende solo dall'elemento) non si rimisurano. Sul libro di 177 pagine rimisurare
     * tutto a ogni lettera scritta costava secondi (telefono di prova, 06/11).
     */
    fun misura(ctx: Context, g: Grafica, gia: Grafica? = null): Grafica {
        val noti: MutableSet<Elemento> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        gia?.let { noti.addAll(it.elementi) }
        var cambiato = false
        val el = g.elementi.map { e ->
            if (e.tipo != Elemento.TESTO || e in noti) return@map e
            val h = altezzaTesto(ctx, e)
            if (kotlin.math.abs(h - e.h) < 0.01f) e else { cambiato = true; e.copy(h = h) }
        }
        return if (cambiato) g.copy(elementi = el) else g
    }

    private fun testo(ctx: Context, c: Canvas, e: Elemento, editor: Boolean = false) {
        if (e.testo.isBlank()) return
        val (mx, my) = margini(e)
        if (e.filo != 0L) {
            // un riquadro del filo: ciò che non ci sta non si vede (aspetta il riquadro dopo); nell'editor un segno rosso
            if (editor && righeDi(ctx, e).height > e.h - 2 * my + 1f) c.drawRect(e.w - e.misura * 0.5f, e.h - e.misura * 0.5f, e.w, e.h, troppo)
            c.save(); c.clipRect(0f, 0f, e.w, e.h)
            try { testoDentro(ctx, c, e, mx, my) } finally { c.restore() }
            return
        }
        testoDentro(ctx, c, e, mx, my)
    }

    private fun testoDentro(ctx: Context, c: Canvas, e: Elemento, mx: Float, my: Float) {
        var colore = e.colore
        if (e.stile == Testo.RIQUADRO) {
            val r = e.misura * 0.25f
            val (fondo, lettere) = Testo.riquadro(e.colore, e.secondo)
            c.drawRoundRect(RectF(0f, 0f, e.w, e.h), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fondo })
            colore = lettere
        }
        c.save()
        c.translate(mx, my)
        if (e.stile == Testo.CONTORNO) {
            val b = righeDi(ctx, e, e.misura * e.spessoreTesto)
            b.paint.color = Testo.coloreBordo(e.colore, e.secondo)
            b.draw(c)
        }
        val l = righeDi(ctx, e)
        l.paint.color = colore
        if (e.stile == Testo.OMBRA) l.paint.setShadowLayer(e.misura * 0.08f, e.misura * 0.03f, e.misura * 0.04f, 0xAA000000.toInt())
        else l.paint.clearShadowLayer()
        l.draw(c)
        c.restore()
    }

    // ---------------------------------------------------------------- tabelle

    /** Il margine dentro ogni cella, in punti: un terzo delle lettere. */
    fun margineCella(e: Elemento) = e.misura * 0.3f

    private val troppo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }

    /** Fondi, testi (dall'alto, tagliati dentro la cella), poi le linee sopra a tutto. */
    private fun tabella(ctx: Context, c: Canvas, e: Elemento, editor: Boolean) {
        val xs = Tabelle.xs(e); val ys = Tabelle.ys(e)
        val nr = Tabelle.nr(e); val nc = Tabelle.nc(e)
        val m = margineCella(e)
        val p = TextPaint(LETTERE).apply { typeface = Caratteri.di(ctx, e.font); textSize = e.misura; color = e.colore }
        for (r in 0 until nr) {
            var k = 0
            while (k < nc) {
                val u = Tabelle.larga(e, r, k)
                val cel = Tabelle.cella(e, r, k)!!
                val box = RectF(xs[k], ys[r], xs[k + u], ys[r + 1])
                if (cel.fondo != 0) c.drawRect(box, Paint().apply { color = cel.fondo })
                if (cel.testo.isNotBlank()) {
                    val l = impagina(ctx, cel.testo, cel.segni, e.font, cel.allinea, p, box.width() - 2 * m)
                    c.save(); c.clipRect(box); c.translate(box.left + m, box.top + m); l.draw(c); c.restore()
                    // il testo non ci sta: un punto rosso nell'angolo, come InDesign (solo nell'editor)
                    if (editor && l.height > box.height() - 2 * m + 0.5f) c.drawCircle(box.right - m, box.bottom - m, max(m * 0.8f, 4f), troppo)
                }
                k += u
            }
        }
        if (e.contorno == 0) return
        val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = e.spessore; color = e.contorno; strokeCap = Paint.Cap.SQUARE }
        for (r in 0..nr) c.drawLine(0f, ys[r], e.w, ys[r], linea)
        for (r in 0 until nr) {
            c.drawLine(0f, ys[r], 0f, ys[r + 1], linea)
            var k = 0
            while (k < nc) { k += Tabelle.larga(e, r, k); c.drawLine(xs[k], ys[r], xs[k], ys[r + 1], linea) }
        }
    }

    // ---------------------------------------------------------------- foto

    private fun cornice(e: Elemento): Path = Path().apply {
        val r = RectF(0f, 0f, e.w, e.h)
        when (e.maschera) {
            Elemento.CERCHIO -> addOval(r, Path.Direction.CW)
            Elemento.ARROTONDATA -> { val k = min(Elemento.RAGGIO_ARROTONDATA, min(e.w, e.h) / 2f); addRoundRect(r, k, k, Path.Direction.CW) }
            else -> addRect(r, Path.Direction.CW)
        }
    }

    /** [b] copre tutto [r] senza deformarsi (il di più si taglia, come «cover»). */
    private fun riempi(c: Canvas, b: Bitmap, r: RectF) {
        val k = max(r.width() / b.width, r.height() / b.height)
        val w = b.width * k; val h = b.height * k
        c.save(); c.clipRect(r)
        c.drawBitmap(b, null, RectF(r.centerX() - w / 2f, r.centerY() - h / 2f, r.centerX() + w / 2f, r.centerY() + h / 2f), pennello)
        c.restore()
    }

    private fun fotoIn(c: Canvas, e: Elemento, foto: Immagini, editor: Boolean) {
        val forma = cornice(e)
        val b = if (e.uri.isEmpty()) null else foto.di(e.uri)
        c.save()
        c.clipPath(forma)
        if (b != null) {
            // la foto nel riquadro: riempie o intera, ingrandita, col suo fuoco (Grafiche.rettangoloFoto)
            val q = Grafiche.rettangoloFoto(e, b.width.toFloat(), b.height.toFloat())
            c.drawBitmap(b, null, RectF(q.l, q.t, q.r, q.b), pennello)
        } else if (editor) {
            c.drawRect(0f, 0f, e.w, e.h, vuoto)
            val s = min(e.w, e.h)
            vuotoSegno.strokeWidth = (s * 0.025f).coerceAtLeast(2f)
            val k = s * 0.08f
            val cy = e.h / 2f - (if (e.uri.isEmpty()) k * 0.6f else 0f)
            c.drawLine(e.w / 2f - k, cy, e.w / 2f + k, cy, vuotoSegno)
            c.drawLine(e.w / 2f, cy - k, e.w / 2f, cy + k, vuotoSegno)
            if (e.uri.isEmpty()) {
                vuotoScritta.textSize = (s * 0.07f).coerceAtLeast(14f)
                c.drawText("Add photo", e.w / 2f, cy + k * 1.6f + vuotoScritta.textSize, vuotoScritta)
            }
        }
        c.restore()
        if (e.bordo != 0) {
            // bordo DENTRO la cornice: si traccia largo il doppio e si taglia fuori
            c.save(); c.clipPath(forma)
            c.drawPath(forma, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Elemento.BORDO * 2.8f; color = e.bordo })
            c.restore()
        }
    }

    // ---------------------------------------------------------------- forme

    /** Stella e cuore, disegnati in un riquadro 100×100. */
    private val stella: Path by lazy {
        Path().apply {
            for (i in 0 until 10) {
                val a = Math.toRadians(-90.0 + i * 36.0)
                val r = if (i % 2 == 0) 50.0 else 20.5
                val x = (50 + r * cos(a)).toFloat(); val y = (52 + r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
    private val cuore: Path by lazy {
        Path().apply {
            moveTo(50f, 92f)
            cubicTo(20f, 70f, 2f, 52f, 2f, 30f)
            cubicTo(2f, 14f, 14f, 3f, 28f, 3f)
            cubicTo(38f, 3f, 46f, 9f, 50f, 18f)
            cubicTo(54f, 9f, 62f, 3f, 72f, 3f)
            cubicTo(86f, 3f, 98f, 14f, 98f, 30f)
            cubicTo(98f, 52f, 80f, 70f, 50f, 92f)
            close()
        }
    }

    fun sagoma(e: Elemento): Path {
        val r = RectF(0f, 0f, e.w, e.h)
        return when (e.forma) {
            Elemento.TONDO -> Path().apply { addOval(r, Path.Direction.CW) }
            Elemento.STELLA, Elemento.CUORE -> Path(if (e.forma == Elemento.STELLA) stella else cuore).apply {
                transform(Matrix().apply { setScale(e.w / 100f, e.h / 100f) })
            }
            else -> Path().apply {
                val k = e.raggio.coerceIn(0f, min(e.w, e.h) / 2f)
                addRoundRect(r, k, k, Path.Direction.CW)
            }
        }
    }

    private fun forma(c: Canvas, e: Elemento) {
        if (e.linea) { linea(c, e); return }
        val s = sagoma(e)
        if (e.riempi != 0) c.drawPath(s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.riempi })
        if (e.contorno != 0) c.drawPath(s, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = Elemento.BORDO; strokeJoin = Paint.Join.ROUND; color = e.contorno
        })
    }

    private fun linea(c: Canvas, e: Elemento) {
        if (e.riempi == 0) return
        val t = e.spessore
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = t; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = e.riempi
        }
        val y = e.h / 2f
        if (e.tratto == Elemento.TRATTEGGIO) p.pathEffect = DashPathEffect(floatArrayOf(t * 3f, t * 2f), 0f)
        c.drawLine(t / 2f, y, e.w - t / 2f, y, p)
        if (e.tratto == Elemento.FRECCIA) {
            p.pathEffect = null
            val punta = Path().apply { moveTo(e.w - t * 4f, y - t * 3f); lineTo(e.w - t / 2f, y); lineTo(e.w - t * 4f, y + t * 3f) }
            c.drawPath(punta, p)
        }
    }

    // ---------------------------------------------------------------- fuori dall'editor

    /** Una pagina intera (tagliata, senza abbondanza) in una immagine larga [larghezza] pixel. */
    fun immagine(ctx: Context, g: Grafica, larghezza: Int, foto: Immagini, editor: Boolean, pagina: Int = 0): Bitmap {
        val k = larghezza / Grafica.LARGO
        val b = Bitmap.createBitmap(larghezza, (g.alto * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(k, k)
        disegna(ctx, c, g, foto, editor, pagina)
        return b
    }
}
