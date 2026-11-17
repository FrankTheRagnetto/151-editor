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

/**
 * La timeline a tre corsie: TESTI sopra, VIDEO al centro (i blocchi SONO il video, strisce
 * dei suoi fotogrammi), MUSICA sotto. Una riga fissa al centro dice dove sei; trascini per
 * scorrere, pizzichi per lo zoom. Tocco su un blocco = lo scegli. Sul blocco scelto i due
 * bordi chiari tagliano; testo e musica scelti si trascinano per spostarli; una clip si
 * sposta tenendola premuta. A DESTRA, accanto al pannello, il «+» di ogni corsia: testo e penna,
 * video, audio — aggiungono alla riga bianca. I testi possono sovrapporsi: chi si sovrappone va
 * sulla riga sotto (Progetto.righeScritte). Fra due clip un tondo «+» apre le transizioni
 * (pieno = c'è). Un disegno si vede in piccolo nel suo blocco.
 * I blocchi dicono il loro stato senza aprirli: «2×», volume, triangoli delle dissolvenze.
 */
class Timeline @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class Tipo { NIENTE, CLIP, MUSICA, TESTO, TRANSIZIONE }
    data class Scelta(val tipo: Tipo, val i: Int) {
        companion object { val NESSUNA = Scelta(Tipo.NIENTE, -1) }
    }

    interface Ascoltatore {
        /** La riga e' su [t] (ms del video finito); [dalDito] = l'ha mossa chi lo usa. */
        fun scorri(t: Long, dalDito: Boolean)
        /** Il dito si e' fermato (anche dopo una spinta). */
        fun fermo(t: Long)
        fun scegli(s: Scelta)
        /** Taglio della clip [i]: nuovi [inizio]/[fine] nel file; [sinistro] = bordo d'inizio. */
        fun taglio(i: Int, inizio: Long, fine: Long, sinistro: Boolean, finito: Boolean)
        /** Riordino delle clip. */
        fun sposta(da: Int, a: Int)
        /** Bordo di musica/testo mosso di [delta] ms da quando il dito l'ha preso. */
        fun bordo(tipo: Tipo, i: Int, sinistro: Boolean, delta: Long, finito: Boolean)
        /** Musica/testo trascinato di [delta] ms da quando il dito l'ha preso. */
        fun trascina(tipo: Tipo, i: Int, delta: Long, finito: Boolean)
        /** Tocco su un «+» di corsia: aggiungi lì ([Tipo.CLIP] = un video, [Tipo.MUSICA] = un suono). */
        fun aggiungi(tipo: Tipo)
        /** Il «+» della penna, accanto a quello del testo. */
        fun aggiungiDisegno()
        /** Il «+» rosso accanto all'audio: registrare la voce. */
        fun registra()
        /** Il tondo fra la clip [k]-1 e la clip [k] (0 = l'inizio, n = la fine). */
        fun transizione(k: Int)
        /** Il testo [i] trascinato su o giù: va sulla riga [r] (il suo livello). */
        fun riga(i: Int, r: Int)
    }

    var ascoltatore: Ascoltatore? = null
    private val d = resources.displayMetrics.density

    private var bozza = Bozza("", emptyList())
    private val clip get() = bozza.clip
    /** Dove comincia ogni clip nel video finito. */
    private var inizi = LongArray(0)
    private var durate: Array<String> = emptyArray()
    var scelta = Scelta.NESSUNA
        private set
    /** La riga: ms del video finito. */
    var t = 0L
        private set
    private var resto = 0f
    /** Zoom: pixel per millisecondo. Partenza: 64 dp al secondo. */
    private var pxMs = 64 * d / 1000f
    val toccando get() = dito != Dito.NIENTE || !scroller.isFinished

    // --- le corsie (dall'alto): righello 18, testi 40 (fino a 3 righe), video 58, musica 36 ---
    private val righello = 18 * d
    private val testoA = 22 * d; private val testoB = 62 * d
    private val videoA = 66 * d; private val videoB = 124 * d
    private val musicaA = 128 * d; private val musicaB = 164 * d
    /** I confini del tocco: ogni corsia vale almeno 44 dp in altezza. */
    private val toccoTesto = 64 * d; private val toccoVideo = 126 * d
    /** la colonna dei «+» delle corsie, a destra */
    private val colonna = 92 * d
    /** il centro della parte che scorre (la riga bianca): a sinistra della colonna */
    private val centro get() = (width - colonna) / 2f
    /** quanto è largo il video rispetto all'altezza: le miniature dei disegni lo seguono */
    private var rapporto = 9f / 16f
    fun formato(w: Int, h: Int) { if (w > 0 && h > 0) { rapporto = w.toFloat() / h; invalidate() } }
    private val maniglia = 16 * d
    private val presa = 28 * d
    private val fessura = 2 * d

    private val foto = Fotogrammi(ctx, (64 * d).toInt()) { invalidate() }
    private val onde = Onde(ctx) { invalidate() }
    private val pOnda = Paint().apply { color = 0xD8FFFFFF.toInt() }
    /** sulla musica l'onda sta dietro al nome: più tenue, il nome resta leggibile */
    private val pOndaPiano = Paint().apply { color = 0x48FFFFFF }
    private val pFondoOnda = Paint().apply { color = 0x70000000 }

    // --- colori e pennelli (solo da colors.xml) ---
    private fun col(id: Int) = ContextCompat.getColor(context, id)
    private val coloreFondo = col(R.color.fondo)
    private val accento = col(R.color.accento)
    private val bianco = col(R.color.bianco)
    private val pFondo = Paint().apply { color = col(R.color.riga) }
    private val pFondoColonna = Paint().apply { color = coloreFondo }
    private val pTestoBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.blocco_testo) }
    private val pMusicaBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.blocco_musica) }
    private val pBordoBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.riga); style = Paint.Style.STROKE; strokeWidth = 1 * d }
    private val pFoto = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pScelto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val pManiglia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento }
    private val pFreccia = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco; style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pRiga = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco }
    private val pTacca = Paint().apply { color = col(R.color.testo_piano) }
    private val pTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo_piano); textSize = 10 * d; fontFeatureSettings = "tnum" }
    private val pDurata = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco; textSize = 11 * d; fontFeatureSettings = "tnum"; setShadowLayer(3 * d, 0f, 0f, col(R.color.nero)) }
    private val pEtichetta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco; textSize = 11 * d; isFakeBoldText = true }
    private val pFondoEtichetta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0000000.toInt() }
    private val pScrittaBlocco = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo); textSize = 13 * d }
    private val pVolo = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 190 }
    private val pVuoto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo_piano); textSize = 14 * d; textAlign = Paint.Align.CENTER }
    private val pPiu = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento; textSize = 15 * d; isFakeBoldText = true }
    private val pCella = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.riga) }
    /** su che riga della corsia dei testi sta ognuno, e quante righe servono (al massimo 3 visibili) */
    private var righe = IntArray(0)
    private var nRighe = 1
    private val pDissolvenza = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0000000.toInt() }

    private val icone = listOf(
        ContextCompat.getDrawable(ctx, R.drawable.ic_testo)!!.mutate(),
        ContextCompat.getDrawable(ctx, R.drawable.ic_video)!!.mutate(),
        ContextCompat.getDrawable(ctx, R.drawable.ic_musica)!!.mutate(),
    ).onEach { it.alpha = 150 }

    // --- oggetti del disegno, preparati una volta ---
    private val rBlocco = RectF()
    private val rQuadro = RectF()
    private val rVolo = RectF()
    private val rSx = RectF()
    private val rDx = RectF()
    private val rEt = RectF()
    private val frecce = Path()
    private val triangolo = Path()
    private val scritteRighello = HashMap<Long, String>()
    private var scrittaTaglio = ""

    // --- stato del dito ---
    private var y0 = 0f
    /** il tondo delle transizioni preso al tocco (-1 = nessuno) */
    private var giunzioneGiu = -1
    /** la riga dove sta andando il testo trascinato (-1 = non si è mosso su o giù) */
    private var rigaVoluta = -1
    private enum class Dito { NIENTE, SCORRE, TAGLIA_SX, TAGLIA_DX, SPOSTA, BORDO_SX, BORDO_DX, TRASCINA }
    private var dito = Dito.NIENTE
    private var ditoId = -1
    private var zoomato = false
    private var tagliaA = 0L; private var tagliaB = 0L
    private var tagliaOrigA = 0L; private var tagliaOrigB = 0L
    private var x0 = 0f
    private var mosso = false
    private var spostaDa = -1; private var spostaX = 0f
    private val scroller = OverScroller(ctx)

    // --- trascinando: calamita con vibrazione, e la timeline che scorre da sola al bordo ---
    /** la riga quando il dito è sceso: se la timeline scorre da sola, il blocco resta sotto il dito */
    private var t0 = 0L
    private var ultimoX = 0f; private var ultimoY = 0f
    /** i bordi del blocco che si muove (a dito fermo) e quelli a cui si attacca */
    private var bordiMobili = LongArray(0); private var fissi = LongArray(0)
    private var agganciato: Long? = null
    private var destVista = -1
    /** px per fotogramma della timeline che scorre da sola; 0 = ferma */
    private var velBordo = 0f
    private var scorreDaSola = false

    fun imposta(nuova: Bozza, sceltaNuova: Scelta) {
        bozza = nuova
        inizi = LongArray(clip.size).also { a -> var s = 0L; for (i in clip.indices) { a[i] = s; s += clip[i].vistaMs } }
        durate = Array(clip.size) { Progetto.secondi(clip[it].vistaMs).replace(" ", "") }
        righe = Progetto.righeScritte(bozza.scritte)
        nRighe = ((righe.maxOrNull() ?: 0) + 1).coerceIn(1, 3)
        scelta = valida(sceltaNuova)
        t = t.coerceIn(0, totale())
        invalidate()
    }

    /** La fascia verticale del testo [i]: la sua riga dentro la corsia. */
    private fun fasciaTesto(i: Int, r: RectF) {
        val h = (testoB - testoA) / nRighe
        val k = righe.getOrElse(i) { 0 }.coerceAtMost(nRighe - 1)
        r.top = testoA + k * h + (if (k > 0) 1 * d else 0f); r.bottom = testoA + (k + 1) * h
    }

    private fun valida(s: Scelta): Scelta = when (s.tipo) {
        Tipo.CLIP -> if (s.i in clip.indices) s else Scelta.NESSUNA
        Tipo.MUSICA -> if (s.i in bozza.musica.indices) s else Scelta.NESSUNA
        Tipo.TESTO -> if (s.i in bozza.scritte.indices) s else Scelta.NESSUNA
        Tipo.TRANSIZIONE -> if (clip.isNotEmpty() && s.i in 0..clip.size) s else Scelta.NESSUNA
        Tipo.NIENTE -> Scelta.NESSUNA
    }

    fun scegli(s: Scelta) { scelta = valida(s); invalidate() }

    /** La riga va a [nuovo] da fuori (il video che suona); ignorato se chi lo usa sta toccando. */
    fun posizione(nuovo: Long) {
        if (toccando) return
        val n = nuovo.coerceIn(0, totale())
        if (n == t) return
        t = n
        invalidate()
    }

    fun chiudi() { foto.chiudi(); onde.chiudi() }

    /**
     * L'onda del suono in fondo al blocco [r]: una barretta ogni 2 dp, alta quanto il picco di quel
     * pezzo di file. [msFile] dice quale ms del file sta alla x; il volume la abbassa (muto = piatta).
     */
    private fun onda(c: Canvas, uri: String, r: RectF, alto: Float, volume: Int, sfondo: Boolean, msFile: (Float) -> Long) {
        val o = onde.prendi(uri) ?: return
        if (o.isEmpty()) return
        val g = (volume / 100f).coerceIn(0f, 1.3f)
        val fondo = r.bottom
        if (sfondo) c.drawRect(r.left, fondo - alto, r.right, fondo, pFondoOnda)
        val passo = 2 * d
        var x = max(r.left, 0f)
        val fine = min(r.right, width.toFloat())
        while (x < fine) {
            val h = (Onde.massimo(o, msFile(x), msFile(x + passo)) * g).coerceAtMost(1f) * (alto - 2 * d)
            if (h > 0.5f) c.drawRect(x, fondo - 1 * d - h, x + passo * 0.6f, fondo - 1 * d, if (sfondo) pOnda else pOndaPiano)
            x += passo
        }
    }

    private fun totale() = Progetto.totaleMs(clip)
    private fun xDi(globale: Long) = centro + (globale - t) * pxMs
    private fun tagliando() = dito == Dito.TAGLIA_SX || dito == Dito.TAGLIA_DX
    private fun clipScelta() = if (scelta.tipo == Tipo.CLIP) scelta.i else -1

    private fun bordoA(i: Int): Float {
        val x = xDi(inizi[i])
        return if (i == clipScelta() && tagliando()) x + (tagliaA - tagliaOrigA) / clip[i].velocita * pxMs else x
    }

    private fun bordoB(i: Int): Float {
        val x = xDi(inizi[i])
        val c = clip[i]
        return if (i == clipScelta() && tagliando()) x + (tagliaB - tagliaOrigA) / c.velocita * pxMs else x + c.vistaMs * pxMs
    }

    /** Il blocco sotto il dito, in qualsiasi corsia. */
    private fun bloccoSotto(x: Float, y: Float): Scelta {
        val g = t + ((x - centro) / pxMs).toLong()
        when {
            y < toccoTesto -> {
                // i testi a quel momento: vince quello sulla riga del dito, se no il primo
                val qui = bozza.scritte.indices.filter { g >= bozza.scritte[it].inizioMs && g < bozza.scritte[it].fineMs }
                val riga = ((y - testoA) / ((testoB - testoA) / nRighe)).toInt().coerceIn(0, nRighe - 1)
                (qui.firstOrNull { righe.getOrElse(it) { 0 }.coerceAtMost(nRighe - 1) == riga } ?: qui.firstOrNull())?.let { return Scelta(Tipo.TESTO, it) }
            }
            y < toccoVideo -> { for (i in clip.indices) if (x >= bordoA(i) && x <= bordoB(i)) return Scelta(Tipo.CLIP, i) }
            else -> bozza.musica.indexOfFirst { g >= it.posMs && g < it.fineGlobaleMs }.let { if (it >= 0) return Scelta(Tipo.MUSICA, it) }
        }
        return Scelta.NESSUNA
    }

    // --- disegno ---

    /** Un tocco nella colonna dei «+»: quale casella (con un po' di margine attorno). */
    private fun tocchiColonna(x: Float, y: Float) {
        val (r, k, _) = caselle().minByOrNull { (r, _, _) ->
            val dx = max(0f, max(r.left - x, x - r.right)); val dy = max(0f, max(r.top - y, y - r.bottom)); dx + dy
        } ?: return
        if (y < righello && !r.contains(x, y)) return
        when (k) {
            0 -> ascoltatore?.aggiungi(Tipo.TESTO)
            3 -> ascoltatore?.aggiungiDisegno()
            1 -> ascoltatore?.aggiungi(Tipo.CLIP)
            4 -> ascoltatore?.registra()
            else -> ascoltatore?.aggiungi(Tipo.MUSICA)
        }
    }

    private val iconaPenna = ContextCompat.getDrawable(ctx, R.drawable.ic_pennello)!!.mutate()
    private val pCellaTesto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.testo); textSize = 12 * d }
    private val pRec = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }

    /** Le caselle «+» a destra: [+T][+✎] per i testi, «+ Video», [+♪][+●] per l'audio (file, registrazione). */
    private fun caselle(): List<Triple<RectF, Int, String?>> {
        val x0 = width - colonna + 6 * d; val x1 = width - 4 * d; val mezzo = (x0 + x1) / 2f
        return listOf(
            Triple(RectF(x0, testoA + 1 * d, mezzo - 2 * d, testoB - 1 * d), 0, null),
            Triple(RectF(mezzo + 2 * d, testoA + 1 * d, x1, testoB - 1 * d), 3, null),
            Triple(RectF(x0, videoA + 1 * d, x1, videoB - 1 * d), 1, "Video"),
            Triple(RectF(x0, musicaA + 1 * d, mezzo - 2 * d, musicaB - 1 * d), 2, null),
            Triple(RectF(mezzo + 2 * d, musicaA + 1 * d, x1, musicaB - 1 * d), 4, null),
        )
    }

    private fun disegnaColonna(c: Canvas) {
        c.drawRect(width - colonna, 0f, width.toFloat(), height.toFloat(), pFondoColonna)
        val lato = (16 * d).toInt()
        for ((r, k, nome) in caselle()) {
            c.drawRoundRect(r, 8 * d, 8 * d, pCella)
            if (k == 4) {
                // registrare: il pallino rosso di ogni registratore
                c.drawText("+", r.centerX() - 13 * d, r.centerY() + 5 * d, pPiu)
                c.drawCircle(r.centerX() + 5 * d, r.centerY(), 6 * d, pRec)
                continue
            }
            val icona = if (k == 3) iconaPenna else icone[k]
            icona.alpha = 230
            val larghezza = 10 * d + lato + (if (nome != null) 4 * d + pCellaTesto.measureText(nome) else 0f)
            var x = r.centerX() - larghezza / 2f
            val cy = r.centerY()
            c.drawText("+", x, cy + 5 * d, pPiu)
            x += 10 * d
            icona.setBounds(x.toInt(), (cy - lato / 2).toInt(), x.toInt() + lato, (cy + lato / 2).toInt())
            icona.draw(c)
            if (nome != null) c.drawText(nome, x + lato + 4 * d, cy + 4 * d, pCellaTesto)
        }
    }

    /** Il tondo delle transizioni fra la clip k-1 e la clip k: x a schermo. */
    private fun xGiunzione(k: Int): Float = if (k < clip.size) bordoA(k) else bordoB(clip.size - 1)

    private val pGiunzione = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.fondo) }
    private val pGiunzioneBordo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bianco; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val pGiunzioneSi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accento }
    private val pSfuma = Paint()

    /** Le giunzioni: sempre, anche accanto alla clip scelta (il tondo vince sulle maniglie, al centro). */
    private fun giunzioni(): List<Int> =
        if (clip.isEmpty() || dito == Dito.SPOSTA || tagliando()) emptyList() else (0..clip.size).toList()

    /** Il tondo sotto il dito, o -1. */
    private fun giunzioneSotto(x: Float, y: Float): Int {
        val cy = (videoA + videoB) / 2f
        if (abs(y - cy) > 18 * d) return -1
        val k = giunzioni().minByOrNull { abs(xGiunzione(it) - x) } ?: return -1
        return if (abs(xGiunzione(k) - x) <= 16 * d) k else -1
    }

    private fun disegnaGiunzioni(c: Canvas) {
        val cy = (videoA + videoB) / 2f
        val r = 11 * d
        for (k in giunzioni()) {
            val x = xGiunzione(k)
            if (x < -r || x > width - colonna + r) continue
            val si = Progetto.transizione(clip, k)
            if (si) {
                // la durata si VEDE: il nero sfuma sulla fine della clip prima e sull'inizio di quella dopo,
                // largo quanto dura; sotto, una riga rosa da un capo all'altro
                val prima = if (k > 0) minOf(clip[k - 1].durEsce, clip[k - 1].vistaMs / 2) * pxMs else 0f
                val dopo = if (k < clip.size) minOf(clip[k].durEntra, clip[k].vistaMs / 2) * pxMs else 0f
                if (prima > 0f) {
                    pSfuma.shader = android.graphics.LinearGradient(x - prima, 0f, x, 0f, 0x00000000, 0xD0000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(x - prima, videoA, x, videoB, pSfuma)
                }
                if (dopo > 0f) {
                    pSfuma.shader = android.graphics.LinearGradient(x, 0f, x + dopo, 0f, 0xD0000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
                    c.drawRect(x, videoA, x + dopo, videoB, pSfuma)
                }
                c.drawRect(x - prima, videoB - 3 * d, x + dopo, videoB, pGiunzioneSi)
            }
            c.drawCircle(x, cy, r, if (si) pGiunzioneSi else pGiunzione)
            c.drawCircle(x, cy, r, pGiunzioneBordo)
            if (scelta.tipo == Tipo.TRANSIZIONE && scelta.i == k) c.drawCircle(x, cy, r + 4 * d, pScelto)
            if (si) {
                // due triangoli che si guardano: una dissolvenza
                frecce.rewind()
                frecce.moveTo(x - 6 * d, cy - 5 * d); frecce.lineTo(x - 1 * d, cy); frecce.lineTo(x - 6 * d, cy + 5 * d); frecce.close()
                frecce.moveTo(x + 6 * d, cy - 5 * d); frecce.lineTo(x + 1 * d, cy); frecce.lineTo(x + 6 * d, cy + 5 * d); frecce.close()
                c.drawPath(frecce, pRiga)
            } else {
                c.drawRect(x - 5 * d, cy - 0.9f * d, x + 5 * d, cy + 0.9f * d, pRiga)
                c.drawRect(x - 0.9f * d, cy - 5 * d, x + 0.9f * d, cy + 5 * d, pRiga)
            }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(coloreFondo)
        if (clip.isEmpty()) {
            c.drawText("Tap + Video to start", (width - colonna) / 2f, (videoA + videoB) / 2f + 5 * d, pVuoto)
            disegnaColonna(c)
            return
        }
        disegnaRighello(c)
        disegnaScritte(c)
        disegnaClip(c)
        disegnaMusica(c)
        disegnaGiunzioni(c)
        disegnaColonna(c)
        // la riga di dove sei
        val xc = centro
        c.drawRect(xc - 1 * d, righello, xc + 1 * d, height.toFloat(), pRiga)
        triangolo.rewind()
        triangolo.moveTo(xc - 6 * d, righello - 2 * d); triangolo.lineTo(xc + 6 * d, righello - 2 * d); triangolo.lineTo(xc, righello + 6 * d); triangolo.close()
        c.drawPath(triangolo, pRiga)
    }

    private fun disegnaClip(c: Canvas) {
        val lato = videoB - videoA
        val msQuadro = lato / pxMs
        var passo = PASSI_FOTO[0]
        for (p in PASSI_FOTO) if (p <= msQuadro) passo = p
        val sc = clipScelta()
        for (i in clip.indices) {
            if (dito == Dito.SPOSTA && i == spostaDa) continue
            val a = bordoA(i); val b = bordoB(i)
            if (b < 0 || a > width) continue
            rBlocco.set(a + fessura / 2, videoA, b - fessura / 2, videoB)
            if (rBlocco.width() <= 0) continue
            c.save()
            c.clipRect(rBlocco)
            c.drawRect(rBlocco, pFondo)
            val cl = clip[i]
            val origA = if (i == sc && tagliando()) tagliaOrigA else cl.inizioMs
            val x0b = xDi(inizi[i])
            var k = kotlin.math.floor((max(rBlocco.left, 0f) - x0b) / lato).toInt()
            val fine = min(rBlocco.right, width.toFloat())
            while (true) {
                val xq = x0b + k * lato
                if (xq > fine) break
                val msFile = origA + ((k * lato + lato / 2) / pxMs * cl.velocita).toLong()
                val q = (msFile / passo) * passo
                val bm = foto.prendi(cl.uri, q.coerceIn(0, cl.durataMs)) ?: foto.segnaposto(cl.uri)
                if (bm != null) {
                    rQuadro.set(xq, videoA, xq + lato, videoB)
                    c.drawBitmap(bm, null, rQuadro, pFoto)
                }
                k++
            }
            // l'onda del suono, nel terzo basso del blocco
            onda(c, cl.uri, rBlocco, (videoB - videoA) * 0.4f, cl.volume, true) { x -> origA + ((x - x0b) / pxMs * cl.velocita).toLong() }
            // dissolvenze: triangoli scuri sugli angoli alti
            val tri = min(24 * d, rBlocco.width() / 3)
            if (cl.entra) {
                triangolo.rewind(); triangolo.moveTo(rBlocco.left, rBlocco.top); triangolo.lineTo(rBlocco.left + tri, rBlocco.top)
                triangolo.lineTo(rBlocco.left, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza)
            }
            if (cl.esce) {
                triangolo.rewind(); triangolo.moveTo(rBlocco.right, rBlocco.top); triangolo.lineTo(rBlocco.right - tri, rBlocco.top)
                triangolo.lineTo(rBlocco.right, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza)
            }
            // etichette: solo quello che e' diverso dal normale
            var xe = rBlocco.left + 5 * d + (if (cl.entra) tri / 2 else 0f)
            if (rBlocco.width() > 60 * d) {
                if (cl.velocita != 1f) xe = etichetta(c, Progetto.velocitaScritta(cl.velocita), xe)
                if (cl.volume != 100) xe = etichetta(c, if (cl.volume == 0) "Muted" else "${cl.volume}%", xe)
            }
            if (rBlocco.width() > 44 * d) {
                val scritta = if (i == sc && tagliando()) scrittaTaglio else durate[i]
                c.drawText(scritta, rBlocco.right - pDurata.measureText(scritta) - 5 * d - (if (cl.esce) tri / 2 else 0f), videoA + 14 * d, pDurata)
            }
            c.restore()
            if (i == sc) disegnaScelta(c, rBlocco)
        }
        // il blocco in volo, sotto il dito
        if (dito == Dito.SPOSTA && spostaDa in clip.indices) {
            val w = 72 * d
            rVolo.set(spostaX - w / 2, videoA - 6 * d, spostaX + w / 2, videoB - 6 * d)
            val cv = clip[spostaDa]
            val bm = foto.prendi(cv.uri, (cv.inizioMs / PASSI_FOTO[0]) * PASSI_FOTO[0]) ?: foto.segnaposto(cv.uri)
            c.drawRect(rVolo, pFondo)
            if (bm != null) c.drawBitmap(bm, null, rVolo, pVolo)
            c.drawRect(rVolo, pScelto)
            val dove = destinazione(spostaX)
            val ultimo = clip.lastIndex
            val xs = if (dove >= clip.size - 1 && spostaX > bordoB(ultimo)) bordoB(ultimo) else puntoInserimento(dove)
            c.drawRect(xs - 1.5f * d, videoA, xs + 1.5f * d, videoB, pManiglia)
        }
    }

    private fun etichetta(c: Canvas, s: String, x: Float): Float {
        val w = pEtichetta.measureText(s) + 10 * d
        rEt.set(x, videoA + 4 * d, x + w, videoA + 20 * d)
        c.drawRoundRect(rEt, 4 * d, 4 * d, pFondoEtichetta)
        c.drawText(s, x + 5 * d, videoA + 16 * d, pEtichetta)
        return x + w + 4 * d
    }

    private fun disegnaScritte(c: Canvas) {
        if (rigaVoluta >= 0) {
            val h = (testoB - testoA) / Progetto.RIGHE_MAX
            c.drawRect(0f, testoA + rigaVoluta * h, width - colonna, testoA + (rigaVoluta + 1) * h, pFondoOnda)
        }
        for ((i, s) in bozza.scritte.withIndex()) {
            val a = xDi(s.inizioMs); val b = xDi(s.fineMs)
            if (b < 0 || a > width) continue
            rBlocco.set(a + fessura / 2, testoA, b - fessura / 2, testoB)
            fasciaTesto(i, rBlocco)
            c.drawRoundRect(rBlocco, 5 * d, 5 * d, pTestoBlocco)
            c.drawRoundRect(rBlocco, 5 * d, 5 * d, pBordoBlocco)
            c.save(); c.clipRect(rBlocco)
            pScrittaBlocco.textSize = if (nRighe == 1) 13 * d else 11 * d
            if (s.disegno) {
                // il disegno in piccolo, una volta sola, nella forma del video
                val h = rBlocco.height() - 4 * d
                val w = h * rapporto
                val x = max(rBlocco.left, 0f) + 3 * d
                rQuadro.set(x, rBlocco.top + 2 * d, x + w, rBlocco.top + 2 * d + h)
                c.drawRect(rQuadro, pFondoOnda)
                c.save(); c.clipRect(rQuadro); c.translate(rQuadro.left, rQuadro.top)
                ManoDisegno.disegna(c, s.tratti, w, h)
                c.restore()
            } else c.drawText("T  " + s.testo.testo.replace('\n', ' '), rBlocco.left + 8 * d, rBlocco.centerY() + pScrittaBlocco.textSize * 0.35f, pScrittaBlocco)
            c.restore()
            if (scelta.tipo == Tipo.TESTO && scelta.i == i) disegnaScelta(c, rBlocco)
        }
    }

    private fun disegnaMusica(c: Canvas) {
        for ((i, m) in bozza.musica.withIndex()) {
            val a = xDi(m.posMs); val b = xDi(m.fineGlobaleMs)
            if (b < 0 || a > width) continue
            rBlocco.set(a + fessura / 2, musicaA, b - fessura / 2, musicaB)
            c.drawRoundRect(rBlocco, 5 * d, 5 * d, pMusicaBlocco)
            c.drawRoundRect(rBlocco, 5 * d, 5 * d, pBordoBlocco)
            c.save(); c.clipRect(rBlocco)
            val xm = xDi(m.posMs)
            onda(c, m.uri, rBlocco, rBlocco.height(), m.volume, false) { x -> m.inizioMs + ((x - xm) / pxMs).toLong() }
            val tri = min(24 * d, rBlocco.width() / 3)
            if (m.entra) { triangolo.rewind(); triangolo.moveTo(rBlocco.left, rBlocco.top); triangolo.lineTo(rBlocco.left + tri, rBlocco.top); triangolo.lineTo(rBlocco.left, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza) }
            if (m.esce) { triangolo.rewind(); triangolo.moveTo(rBlocco.right, rBlocco.top); triangolo.lineTo(rBlocco.right - tri, rBlocco.top); triangolo.lineTo(rBlocco.right, rBlocco.bottom); triangolo.close(); c.drawPath(triangolo, pDissolvenza) }
            val info = buildString {
                append("♪  ").append(m.nome)
                append(" · ").append(if (m.volume == 0) "muted" else "${m.volume}%")
            }
            c.drawText(info, rBlocco.left + 8 * d + (if (m.entra) tri / 2 else 0f), rBlocco.centerY() + 4.5f * d, pScrittaBlocco)
            c.restore()
            if (scelta.tipo == Tipo.MUSICA && scelta.i == i) disegnaScelta(c, rBlocco)
        }
    }

    private fun disegnaScelta(c: Canvas, r: RectF) {
        c.drawRect(r, pScelto)
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
        val primo = max(0L, ((t - centro / pxMs) / passo).toLong() * passo)
        val decimi = passo < 1000
        if (scritteRighello.size > 600) scritteRighello.clear()
        var m = primo
        while (m <= tot) {
            val x = xDi(m)
            if (x > width) break
            c.drawRect(x, righello - 6 * d, x + 1 * d, righello, pTacca)
            val chiave = if (decimi) -(m + 1) else m
            var testo = scritteRighello[chiave]
            if (testo == null) {
                val s = m / 1000
                testo = if (decimi) "%d:%02d.%d".format(s / 60, s % 60, (m % 1000) / 100) else "%d:%02d".format(s / 60, s % 60)
                scritteRighello[chiave] = testo
            }
            c.drawText(testo, x + 3 * d, righello - 7 * d, pTesto)
            m += passo
        }
    }

    // --- spostare una clip: dove cade ---

    private fun destinazione(x: Float): Int {
        var n = 0
        for (i in clip.indices) {
            if (i == spostaDa) continue
            if (x > (bordoA(i) + bordoB(i)) / 2) n++
        }
        return n.coerceIn(0, clip.size - 1)
    }

    private fun puntoInserimento(dove: Int): Float {
        var n = 0
        var ultimo = -1
        for (i in clip.indices) {
            if (i == spostaDa) continue
            if (n == dove) return bordoA(i)
            ultimo = i; n++
        }
        return if (ultimo >= 0) bordoB(ultimo) else centro
    }

    // --- tocco ---

    private val gesti = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dito != Dito.NIENTE && dito != Dito.SCORRE) return false
            if (zoomato || e2.pointerCount > 1) return true
            dito = Dito.SCORRE
            val ms = dx / pxMs + resto
            val intero = ms.toLong()
            resto = ms - intero
            val nuovo = (t + intero).coerceIn(0, totale())
            if (nuovo != t) {
                t = nuovo
                ascoltatore?.scorri(t, true)
                invalidate()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (dito != Dito.SCORRE || zoomato) return false
            val maxX = (totale() * pxMs).toInt()
            scroller.fling((t * pxMs).toInt(), 0, -vx.toInt(), 0, 0, maxX, 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (zoomato) return true
            if (e.x > width - colonna) { tocchiColonna(e.x, e.y); return true }
            val s = bloccoSotto(e.x, e.y)
            scelta = if (s == scelta) Scelta.NESSUNA else s
            ascoltatore?.scegli(scelta)
            invalidate()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (dito != Dito.NIENTE || zoomato) return
            val s = bloccoSotto(e.x, e.y)
            if (s.tipo == Tipo.TESTO) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                scelta = s; ascoltatore?.scegli(s)
                dito = Dito.TRASCINA; x0 = e.x; mosso = false; rigaVoluta = -1
                iniziaTrascinamento()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return
            }
            if (s.tipo != Tipo.CLIP || clip.size < 2) return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            dito = Dito.SPOSTA; spostaDa = s.i; spostaX = e.x
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
            pxMs = (pxMs * s.scaleFactor).coerceIn(4 * d / 1000f, 600 * d / 1000f)
            invalidate()
            return true
        }
    })

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            t = (scroller.currX / pxMs).toLong().coerceIn(0, totale())
            ascoltatore?.scorri(t, true)
            if (scroller.isFinished) ascoltatore?.fermo(t)
            postInvalidateOnAnimation()
        }
    }

    private fun xDito(e: MotionEvent): Float? {
        val i = e.findPointerIndex(ditoId)
        return if (i >= 0) e.getX(i) else null
    }

    private fun finisciTaglio(annullato: Boolean) {
        val era = dito
        fermaTrascinamento()
        dito = Dito.NIENTE
        ditoId = -1
        if (annullato) { tagliaA = tagliaOrigA; tagliaB = tagliaOrigB }
        ascoltatore?.taglio(scelta.i, tagliaA, tagliaB, era == Dito.TAGLIA_SX, true)
        invalidate()
    }

    private fun finisciSposta(lascia: Boolean) {
        val scorsa = fermaTrascinamento()
        dito = Dito.NIENTE
        ditoId = -1
        val a = destinazione(spostaX)
        val da = spostaDa
        spostaDa = -1
        if (lascia && a != da) ascoltatore?.sposta(da, a)
        if (scorsa) ascoltatore?.fermo(t)
        invalidate()
    }

    /** Fine di un bordo o di un trascinamento di musica/testo: [annullato] = torna dov'era. */
    private fun finisciCorsia(x: Float, annullato: Boolean) {
        val era = dito
        val scorsa = fermaTrascinamento()
        dito = Dito.NIENTE
        ditoId = -1
        val delta = if (annullato || !mosso) 0L else deltaCorsia(x)
        when (era) {
            Dito.BORDO_SX, Dito.BORDO_DX -> ascoltatore?.bordo(scelta.tipo, scelta.i, era == Dito.BORDO_SX, delta, true)
            Dito.TRASCINA -> ascoltatore?.trascina(scelta.tipo, scelta.i, delta, true)
            else -> {}
        }
        if (scorsa) ascoltatore?.fermo(t)
        invalidate()
    }

    private fun corsia() = dito == Dito.BORDO_SX || dito == Dito.BORDO_DX || dito == Dito.TRASCINA
    private fun trascinando() = tagliando() || corsia() || dito == Dito.SPOSTA

    /** Un trascinamento comincia: dove sta la riga, quali bordi si muovono, a quali si attaccano. */
    private fun iniziaTrascinamento() {
        t0 = t; agganciato = null; destVista = spostaDa; velBordo = 0f; restoBordo = 0f
        val (a, b) = when (scelta.tipo) {
            Tipo.MUSICA -> bozza.musica.getOrNull(scelta.i)?.let { it.posMs to it.fineGlobaleMs }
            Tipo.TESTO -> bozza.scritte.getOrNull(scelta.i)?.let { it.inizioMs to it.fineMs }
            else -> null
        } ?: (0L to 0L)
        bordiMobili = when (dito) {
            Dito.BORDO_SX -> longArrayOf(a)
            Dito.BORDO_DX -> longArrayOf(b)
            Dito.TRASCINA -> longArrayOf(a, b)
            else -> LongArray(0)
        }
        fissi = buildList {
            addAll(inizi.toList()); add(totale())
            bozza.musica.forEachIndexed { k, m -> if (scelta.tipo != Tipo.MUSICA || k != scelta.i) { add(m.posMs); add(m.fineGlobaleMs) } }
            bozza.scritte.forEachIndexed { k, w -> if (scelta.tipo != Tipo.TESTO || k != scelta.i) { add(w.inizioMs); add(w.fineMs) } }
        }.toLongArray()
    }

    /** Fine del trascinamento: la timeline smette di scorrere da sola. true = si era mossa la riga. */
    private fun fermaTrascinamento(): Boolean {
        velBordo = 0f
        removeCallbacks(scorriSola); scorreDaSola = false
        return t != t0
    }

    /**
     * Quanto si sposta il blocco di musica o testo: il dito (più quanto è scorsa la timeline da sola),
     * attaccato a un bordo vicino se ce n'è uno; quando si attacca, un colpetto nella mano.
     */
    private fun deltaCorsia(x: Float): Long {
        val libero = ((x - x0) / pxMs).toLong() + (t - t0)
        if (bordiMobili.isEmpty()) return libero
        // la riga bianca è un bordo anche lei, ma non mentre scorre da sola
        val bersagli = if (velBordo == 0f) fissi + t else fissi
        val c = Progetto.calamita(bordiMobili, bersagli, libero, (CALAMITA_DP * d / pxMs).toLong())
        if (c != agganciato) { if (c != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); agganciato = c }
        return c ?: libero
    }

    /** Il dito (o la timeline che scorre sotto di lui) è in [x],[y]: il blocco trascinato lo segue. */
    private fun muovi(x: Float, y: Float) {
        when (dito) {
            Dito.TAGLIA_SX, Dito.TAGLIA_DX -> {
                val c = clip[scelta.i]
                val delta = (((x - x0) / pxMs + (t - t0)) * c.velocita).toLong()
                val min = Progetto.MINIMO_MS.coerceAtMost(c.durataMs)
                val primaA = tagliaA; val primaB = tagliaB
                if (dito == Dito.TAGLIA_SX) tagliaA = (tagliaOrigA + delta).coerceIn(0, tagliaOrigB - min)
                else tagliaB = (tagliaOrigB + delta).coerceIn(tagliaOrigA + min, c.durataMs)
                if (tagliaA != primaA || tagliaB != primaB) {
                    scrittaTaglio = Progetto.secondi(((tagliaB - tagliaA) / c.velocita).toLong()).replace(" ", "")
                    ascoltatore?.taglio(scelta.i, tagliaA, tagliaB, dito == Dito.TAGLIA_SX, false)
                }
                invalidate()
            }
            Dito.SPOSTA -> {
                spostaX = x
                // la clip passa sopra un'altra: il posto dove cadrebbe cambia, un colpetto
                val dest = destinazione(x)
                if (dest != destVista) { destVista = dest; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
            }
            Dito.BORDO_SX, Dito.BORDO_DX, Dito.TRASCINA -> {
                if (!mosso && abs(x - x0) < 6 * d && t == t0) return
                mosso = true
                val delta = deltaCorsia(x)
                if (dito == Dito.TRASCINA && scelta.tipo == Tipo.TESTO) {
                    // su e giù = il livello: la corsia dei testi divisa in tre righe
                    val r = ((y - testoA) / ((testoB - testoA) / Progetto.RIGHE_MAX)).toInt().coerceIn(0, Progetto.RIGHE_MAX - 1)
                    if (r != rigaVoluta) { rigaVoluta = r; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); invalidate() }
                }
                if (dito == Dito.TRASCINA) ascoltatore?.trascina(scelta.tipo, scelta.i, delta, false)
                else ascoltatore?.bordo(scelta.tipo, scelta.i, dito == Dito.BORDO_SX, delta, false)
            }
            else -> {}
        }
    }

    /** Vicino al bordo sinistro o destro (prima della colonna dei «+») la timeline scorre da sola, più svelta più si è vicini. */
    private fun bordoVicino(x: Float) {
        val zona = ZONA_BORDO_DP * d
        val destra = width - colonna
        velBordo = when {
            x < zona -> -((zona - x) / zona).coerceAtMost(1f) * VEL_BORDO_DP * d
            x > destra - zona -> ((x - (destra - zona)) / zona).coerceAtMost(1f) * VEL_BORDO_DP * d
            else -> 0f
        }
        if (velBordo != 0f && !scorreDaSola) { scorreDaSola = true; postOnAnimation(scorriSola) }
    }

    private var restoBordo = 0f
    private val scorriSola = object : Runnable {
        override fun run() {
            if (velBordo == 0f || !trascinando()) { scorreDaSola = false; return }
            val ms = velBordo / pxMs + restoBordo
            val intero = ms.toLong(); restoBordo = ms - intero
            val n = (t + intero).coerceIn(0, totale())
            if (n != t) {
                t = n
                // il taglio mostra il suo fotogramma: l'immagine la rifà lui alla fine
                if (!tagliando()) ascoltatore?.scorri(t, true)
                muovi(ultimoX, ultimoY)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    /** I bordi (a schermo) e la fascia verticale del blocco scelto, o null. */
    private fun sceltoA(): Triple<Float, Float, ClosedFloatingPointRange<Float>>? = when (scelta.tipo) {
        Tipo.CLIP -> Triple(bordoA(scelta.i), bordoB(scelta.i), righello..toccoVideo)
        Tipo.TESTO -> bozza.scritte.getOrNull(scelta.i)?.let { Triple(xDi(it.inizioMs), xDi(it.fineMs), righello..toccoTesto) }
        Tipo.MUSICA -> bozza.musica.getOrNull(scelta.i)?.let { Triple(xDi(it.posMs), xDi(it.fineGlobaleMs), toccoVideo..height.toFloat()) }
        Tipo.TRANSIZIONE, Tipo.NIENTE -> null
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (clip.isEmpty()) {
            // senza clip si può solo cominciare: «+ Video» (o testo e musica, che aspettano un video)
            if (e.actionMasked == MotionEvent.ACTION_UP && e.x > width - colonna) tocchiColonna(e.x, e.y)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) { scroller.forceFinished(true); ascoltatore?.fermo(t) }
                dito = Dito.NIENTE
                ditoId = e.getPointerId(0)
                zoomato = false
                mosso = false
                resto = 0f
                x0 = e.x
                y0 = e.y
                giunzioneGiu = giunzioneSotto(e.x, e.y)
                if (giunzioneGiu >= 0) return true
                val sc = sceltoA()
                if (sc != null && e.y in sc.third) {
                    val (a, b) = sc
                    val dSx = abs(e.x - (a - maniglia / 2))
                    val dDx = abs(e.x - (b + maniglia / 2))
                    if (min(dSx, dDx) < presa) {
                        if (scelta.tipo == Tipo.CLIP) {
                            dito = if (dSx <= dDx) Dito.TAGLIA_SX else Dito.TAGLIA_DX
                            val c = clip[scelta.i]
                            tagliaA = c.inizioMs; tagliaB = c.fineMs
                            tagliaOrigA = c.inizioMs; tagliaOrigB = c.fineMs
                            scrittaTaglio = durate[scelta.i]
                        } else {
                            dito = if (dSx <= dDx) Dito.BORDO_SX else Dito.BORDO_DX
                        }
                        iniziaTrascinamento()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                    if (scelta.tipo != Tipo.CLIP && e.x > a && e.x < b) {
                        // testo o musica scelti: il corpo si trascina
                        dito = Dito.TRASCINA
                        iniziaTrascinamento()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (tagliando() || dito == Dito.SPOSTA || corsia()) return true
            MotionEvent.ACTION_POINTER_UP -> if (tagliando() || dito == Dito.SPOSTA || corsia()) {
                if (e.getPointerId(e.actionIndex) == ditoId) {
                    when {
                        tagliando() -> finisciTaglio(false)
                        dito == Dito.SPOSTA -> finisciSposta(true)
                        else -> finisciCorsia(e.getX(e.actionIndex), false)
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (giunzioneGiu >= 0) return true else when (dito) {
                Dito.TAGLIA_SX, Dito.TAGLIA_DX, Dito.SPOSTA, Dito.BORDO_SX, Dito.BORDO_DX, Dito.TRASCINA -> {
                    val k = e.findPointerIndex(ditoId)
                    if (k < 0) return true
                    ultimoX = e.getX(k); ultimoY = e.getY(k)
                    muovi(ultimoX, ultimoY)
                    // solo dopo che il dito si è mosso davvero: un blocco già vicino al bordo non parte da solo
                    if (mosso || abs(ultimoX - x0) >= 6 * d) bordoVicino(ultimoX)
                    return true
                }
                else -> {}
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val annullato = e.actionMasked == MotionEvent.ACTION_CANCEL
                if (giunzioneGiu >= 0) {
                    val k = giunzioneGiu; giunzioneGiu = -1
                    if (!annullato && abs(e.x - x0) < 12 * d && abs(e.y - y0) < 12 * d) ascoltatore?.transizione(k)
                    return true
                }
                when (dito) {
                    Dito.TAGLIA_SX, Dito.TAGLIA_DX -> { finisciTaglio(annullato); return true }
                    Dito.SPOSTA -> { finisciSposta(!annullato); return true }
                    Dito.BORDO_SX, Dito.BORDO_DX, Dito.TRASCINA -> {
                        val eraTrascina = dito == Dito.TRASCINA && !mosso
                        val r = rigaVoluta; rigaVoluta = -1
                        finisciCorsia(e.x, annullato)
                        if (!annullato && r >= 0 && scelta.tipo == Tipo.TESTO && r != righe.getOrElse(scelta.i) { 0 }) ascoltatore?.riga(scelta.i, r)
                        // un tocco (senza movimento) sul blocco scelto lo lascia, come per le clip
                        if (eraTrascina && !annullato) { scelta = Scelta.NESSUNA; ascoltatore?.scegli(scelta); invalidate() }
                        return true
                    }
                    else -> {}
                }
                zoom.onTouchEvent(e)
                gesti.onTouchEvent(e)
                val era = dito
                dito = Dito.NIENTE
                ditoId = -1
                if (era == Dito.SCORRE && scroller.isFinished) ascoltatore?.fermo(t)
                return true
            }
        }
        zoom.onTouchEvent(e)
        gesti.onTouchEvent(e)
        return true
    }

    private companion object {
        /** a quanti dp un bordo si attacca; dove comincia a scorrere da sola; quanto svelta, al massimo (dp per fotogramma) */
        const val CALAMITA_DP = 10f
        const val ZONA_BORDO_DP = 44f
        const val VEL_BORDO_DP = 9f
        val PASSI_FOTO = longArrayOf(100, 200, 500, 1000, 2000, 5000, 10000, 30000, 60000)
        val PASSI_RIGHELLO = longArrayOf(500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000)
    }
}
