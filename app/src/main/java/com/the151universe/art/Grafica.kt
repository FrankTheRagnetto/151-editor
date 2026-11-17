package com.the151universe.art

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * L'editor «Design» (volantini, post, storie), senza Android: il foglio, gli oggetti, i conti
 * delle dita (agganci, ancore, tocchi su oggetti girati), i modelli pronti e il salvataggio.
 * Tutto puro, così si prova sul PC (app/src/test/GraficaTest.kt).
 *
 * Il foglio è largo sempre [Grafica.LARGO] punti; l'altezza la dà il formato. Ogni oggetto:
 * x,y = angolo in alto a sinistra PRIMA del giro, w×h, rot in gradi attorno al proprio centro.
 * Lo schermo e il file finito disegnano gli stessi punti, solo a scale diverse.
 */

/** Millimetri → punti di stampa (1/72 di pollice). */
fun mm(v: Float): Float = v * 72f / 25.4f

/**
 * I formati. [pdfW]×[pdfH] = la pagina rifilata, in punti di stampa (1/72 di pollice).
 * [abbondanzaMm] = quanto il disegno esce oltre il taglio per lato (la tipografia vuole 3 mm):
 * nel PDF la pagina diventa più grande di tanto, con TrimBox e BleedBox (vedi [PdfScatole]).
 */
data class Formato(
    val id: String, val nome: String, val corto: String, val sotto: String,
    val alto: Float, val pxW: Int, val pxH: Int, val pdfW: Float, val pdfH: Float,
    val abbondanzaMm: Float = 0f,
) {
    /** Punti di stampa per un punto del foglio. */
    val ptPerPunto get() = pdfW / Grafica.LARGO
    /** L'abbondanza in punti di stampa. */
    val abbondanzaPt get() = mm(abbondanzaMm)
    /** L'abbondanza in punti del foglio: il disegno va da -abbondanza a LARGO+abbondanza. */
    val abbondanza get() = abbondanzaPt / ptPerPunto
    /** La pagina del PDF, abbondanza compresa, in punti di stampa. */
    val mediaW get() = pdfW + 2 * abbondanzaPt
    val mediaH get() = pdfH + 2 * abbondanzaPt
    /** Per la stampa (tutti tranne Post e Story): il PDF è la scelta di partenza, misure in mm. */
    val stampa get() = id != "post" && id != "story"
    /** La pagina rifilata in mm. */
    val mmW get() = pdfW * 25.4f / 72f
    val mmH get() = pdfH * 25.4f / 72f
    /** «63 × 88 mm» */
    val misureMm get() = "${mmCorto(mmW)} × ${mmCorto(mmH)} mm"

    companion object {
        private fun mmCorto(v: Float) = Math.round(v * 10f).let { if (it % 10 == 0) "${it / 10}" else "${it / 10f}" }

        /** Un formato da stampa di [w]×[h] mm a 300 dpi, con [abbondanza] mm. */
        private fun stampa(id: String, nome: String, corto: String, sotto: String, w: Float, h: Float, abbondanza: Float) =
            Formato(id, nome, corto, sotto, Grafica.LARGO * h / w, Math.round(w / 25.4f * 300f), Math.round(h / 25.4f * 300f), mm(w), mm(h), abbondanza)

        val A4 = Formato("a4", "A4 flyer", "A4", "Print or PDF", Grafica.LARGO * 297f / 210f, 2480, 3508, 595f, 842f)
        val A5 = Formato("a5", "A5 print", "A5", "3 mm bleed", Grafica.LARGO * 210f / 148f, 1748, 2480, mm(148f), mm(210f), 3f)
        val POST = Formato("post", "Post", "Post", "Square 1:1", Grafica.LARGO, 2160, 2160, 540f, 540f)
        val STORIA = Formato("story", "Story", "Story", "Vertical 9:16", Grafica.LARGO * 16f / 9f, 1080, 1920, 540f, 960f)
        // carte e piccoli formati da stampa, tutti con 3 mm di abbondanza
        val A6 = stampa("a6", "A6 postcard", "A6", "105 × 148 mm", 105f, 148f, 3f)
        val CARTA = stampa("card", "Playing card", "Card", "63 × 88 mm", 63f, 88f, 3f)
        val BIGLIETTO = stampa("bizcard", "Business card", "Business", "85 × 55 mm", 85f, 55f, 3f)

        /** I quattro grandi della pagina «New design». */
        val entries = listOf(A4, A5, POST, STORIA)
        /** Tutti quelli pronti, nell'ordine del pannello Page. */
        val TUTTI = listOf(A4, A5, A6, CARTA, BIGLIETTO, POST, STORIA)
        const val MM_MIN = 20f
        const val MM_MAX = 1000f

        /** Una misura libera in mm (fra [MM_MIN] e [MM_MAX]), da stampa, con 3 mm di abbondanza. */
        fun suMisura(w: Float, h: Float): Formato {
            val ww = (Math.round(w.coerceIn(MM_MIN, MM_MAX) * 10f) / 10f); val hh = (Math.round(h.coerceIn(MM_MIN, MM_MAX) * 10f) / 10f)
            TUTTI.firstOrNull { it.stampa && kotlin.math.abs(it.mmW - ww) < 0.05f && kotlin.math.abs(it.mmH - hh) < 0.05f }?.let { return it }
            val n = "${mmCorto(ww)} × ${mmCorto(hh)} mm"
            return stampa("mm:${ww}x${hh}", n, n.removeSuffix(" mm"), "Your size", ww, hh, 3f)
        }

        fun da(id: String): Formato {
            TUTTI.firstOrNull { it.id == id }?.let { return it }
            if (id.startsWith("mm:")) {
                val q = id.removePrefix("mm:").split('x').map { it.toFloatOrNull() }
                if (q.size == 2 && q.all { it != null && it.isFinite() }) return suMisura(q[0]!!, q[1]!!)
            }
            return A4
        }
    }
}

/**
 * Un oggetto sul foglio. Un solo tipo con tutti i campi (testo, foto, forma): copiarlo,
 * salvarlo e confrontarlo resta banale. Colore 0 = nessuno (trasparente).
 */
data class Elemento(
    val id: Long,
    val tipo: Int,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rot: Float = 0f,
    val opacita: Int = 100,
    // testo
    val testo: String = "",
    val font: String = Caratteri.PRIMO,
    /** altezza delle lettere, in punti */
    val misura: Float = 96f,
    val colore: Int = BIANCO,
    val stile: Int = Testo.SEMPLICE,
    val allinea: Int = CENTRO,
    /** il secondo colore del testo (bordo di «Outline», fondo di «Box»), 0 = automatico; vedi [Testo] */
    val secondo: Int = 0,
    /** lo spessore del bordo di «Outline», in frazioni dell'altezza delle lettere */
    val spessoreTesto: Float = Testo.SPESSORE_STD,
    // foto: uri vuoto = posto per una foto (nell'editor si vede, nel file no)
    val uri: String = "",
    val maschera: Int = QUADRATA,
    val bordo: Int = 0,
    // forma (per la linea [riempi] è il colore della linea)
    val forma: String = RETTANGOLO,
    val riempi: Int = 0,
    val contorno: Int = 0,
    val spessore: Float = 8f,
    val tratto: Int = PIENO,
    val raggio: Float = 0f,
    // disegno a mano libera: i tratti in frazioni della scatola dell'oggetto (vedi Mano)
    val tratti: List<Tratto> = emptyList(),
    /** su quale pagina sta (0 = la prima) */
    val pagina: Int = 0,
    /** testo misto: i pezzi in grassetto/corsivo/sottolineati (vedi [Ricco]) */
    val segni: List<Segno> = emptyList(),
    // tabella (vedi [Tabelle]): larghezze e altezze in frazioni, celle riga per riga
    val colonne: List<Float> = emptyList(),
    val righe: List<Float> = emptyList(),
    val celle: List<Cella> = emptyList(),
    /** lo stile di testo (Title, Word…) a cui il testo è legato: 1..stili, 0 = nessuno (vedi [StileTesto]) */
    val classe: Int = 0,
    /** il gruppo: oggetti col gruppo uguale (≠ 0) si scelgono e si spostano insieme */
    val gruppo: Long = 0,
    // la foto dentro il suo riquadro (come InDesign): [adatta] = intera (se no riempie), [zoomFoto] 1..5,
    // [fuocoX]/[fuocoY] = il punto della foto (0..1) che sta al centro del riquadro
    val adatta: Boolean = false,
    val zoomFoto: Float = 1f,
    val fuocoX: Float = 0.5f,
    val fuocoY: Float = 0.5f,
    /**
     * Il filo del testo (come InDesign): riquadri col filo uguale (≠ 0) sono UN testo che continua dall'uno
     * all'altro nell'ordine [filoN]; ognuno ha alto fisso e tiene il pezzo che ci sta (vedi [GraficaDisegno.scorri]).
     */
    val filo: Long = 0,
    val filoN: Int = 0,
    /** ferma: si vede ma il dito la attraversa (la pagina-foto di un PDF aperto: si sfoglia, non si sposta) */
    val fermo: Boolean = false,
    /** da dove viene nel file della lista (a book made from a word list): l'id della parola (o «#riga»), «v:id,id…» per una tabella di verbi */
    val chiave: String = "",
) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val linea get() = tipo == FORMA && forma == LINEA

    companion object {
        const val TESTO = 0
        const val FOTO = 1
        const val FORMA = 2
        const val DISEGNO = 3
        const val TABELLA = 4

        const val SINISTRA = 0
        const val CENTRO = 1
        const val DESTRA = 2

        const val QUADRATA = 0
        const val ARROTONDATA = 1
        const val CERCHIO = 2

        const val RETTANGOLO = "rect"
        const val TONDO = "circle"
        const val LINEA = "line"
        const val STELLA = "star"
        const val CUORE = "heart"
        val FORME = listOf(RETTANGOLO to "Rectangle", TONDO to "Circle", LINEA to "Line", STELLA to "Star", CUORE to "Heart")

        const val PIENO = 0
        const val TRATTEGGIO = 1
        const val FRECCIA = 2

        const val BIANCO = 0xFFFFFFFF.toInt()
        const val NERO = 0xFF000000.toInt()

        /** Il lato più piccolo che un oggetto può avere, in punti. */
        const val MIN = 24f
        const val ALTO_LINEA = 40f
        /** Lo spessore dei bordi di foto e forme, in punti. */
        const val BORDO = 10f
        const val RAGGIO_ARROTONDATA = 40f
    }
}

/**
 * Un progetto «Design». [fotoSfondo] vuoto = solo colore; [vela] = quanto si scurisce la foto di sfondo, 0..80.
 * [pagine] ≥ 1; sfondo e formato valgono per tutte. Gli oggetti stanno in [elementi] tutti insieme,
 * ognuno con la sua [Elemento.pagina]: dentro una pagina l'ordine della lista è l'ordine dei livelli.
 */
data class Grafica(
    val nome: String,
    val formato: Formato,
    val sfondo: Int = Elemento.BIANCO,
    val fotoSfondo: String = "",
    val vela: Int = 0,
    val elementi: List<Elemento> = emptyList(),
    val pagine: Int = 1,
    /** l'abbondanza scelta in mm (null = quella del formato); conta solo per i formati da stampa */
    val sceltaAbbondanza: Float? = null,
    /**
     * Le pagine madri (come in InDesign, chi lo usa 05/11): i loro oggetti stanno su [Elemento.pagina] = -k
     * (k = 1, 2…) e si disegnano sotto ogni pagina che usa la madre k. [nomiMadri] il nome di ognuna,
     * [madreDi] per ogni pagina quale madre usa (0 = nessuna). Nel testo di una madre «#» = numero di pagina.
     */
    val nomiMadri: List<String> = emptyList(),
    val madreDi: List<Int> = emptyList(),
    /** margini e colonne di tutte le pagine (null = nessuno) */
    val gabbia: Gabbia? = null,
    /** margini propri di ogni madre, lunga quanto [nomiMadri] (null = quelli del documento) */
    val gabbieMadri: List<Gabbia?> = emptyList(),
    /** gli stili di testo (Title, Heading, Body…); un testo ci si lega con [Elemento.classe] = posizione + 1 */
    val stili: List<StileTesto> = emptyList(),
    /** come si salva il PDF da stampa: segni di taglio, carte su fogli A4 (scelti nel Save, si ricordano) */
    val perStampa: Stampa = Stampa(),
    /** il libro nato da una lista (a book made from a word list): la copia del file delle parole e di quello delle liste ("" = nessuno) */
    val fonteParole: String = "",
    val fonteListe: String = "",
) {
    val madri get() = nomiMadri.size
    /** La madre della pagina [p] (0 = nessuna). */
    fun madre(p: Int): Int = madreDi.getOrElse(p) { 0 }.takeIf { it in 1..madri } ?: 0
    val alto get() = formato.alto

    /** L'abbondanza vera di questo progetto, in mm: Post e Story non ne hanno mai. */
    val abbondanzaMm get() = if (formato.stampa) (sceltaAbbondanza ?: formato.abbondanzaMm).coerceIn(0f, 10f) else 0f
    val abbondanzaPt get() = mm(abbondanzaMm)
    /** in punti del foglio: il disegno va da -abbondanza a LARGO+abbondanza */
    val abbondanza get() = abbondanzaPt / formato.ptPerPunto
    /** La pagina del PDF, abbondanza compresa, in punti di stampa. */
    val mediaW get() = formato.pdfW + 2 * abbondanzaPt
    val mediaH get() = formato.pdfH + 2 * abbondanzaPt

    fun trova(id: Long?): Elemento? = if (id == null) null else elementi.firstOrNull { it.id == id }

    /** Gli oggetti di una pagina, dal più in basso al più in alto. */
    fun di(pagina: Int): List<Elemento> = elementi.filter { it.pagina == pagina }

    companion object {
        const val LARGO = 1080f
        const val VELA_MAX = 80
        const val PAGINE_MAX = 500
    }
}

/**
 * Margini e colonne (come InDesign, chi lo usa 05/11): righe guida che si vedono solo nell'editor, mai in
 * PDF o PNG, e a cui gli oggetti si agganciano. In mm sulla pagina tagliata. Con più pagine il progetto è
 * un libro: [interno] è il lato della piega, che sulle pagine di sinistra (2, 4…) sta a destra.
 */
data class Gabbia(
    val alto: Float = 15f, val basso: Float = 15f, val interno: Float = 15f, val esterno: Float = 15f,
    val colonne: Int = 1, val spazio: Float = 5f,
) {
    /** La stessa, coi numeri dentro i limiti. */
    fun giusta() = Gabbia(alto.mmOk(), basso.mmOk(), interno.mmOk(), esterno.mmOk(), colonne.coerceIn(1, COLONNE_MAX), spazio.mmOk(SPAZIO_MAX))

    companion object {
        const val MM_MAX = 100f
        const val SPAZIO_MAX = 50f
        const val COLONNE_MAX = 12
        private fun Float.mmOk(max: Float = MM_MAX) = if (isFinite()) coerceIn(0f, max) else 0f
    }
}

/**
 * Uno stile di testo, come gli stili di paragrafo di InDesign: un
 * aspetto con un nome. Un testo legato prende tutto lo stile; cambiato lo stile, cambiano tutti i suoi testi.
 * [segno] = grassetto/corsivo/sottolineato su tutto il testo (bit di [Ricco]).
 */
data class StileTesto(val nome: String, val font: String, val misura: Float, val colore: Int, val allinea: Int, val segno: Int = 0)

/**
 * Le righe guida di una pagina, in punti del foglio: il riquadro dentro i margini e i bordi di ogni
 * colonna (sinistra, destra). [verticali] e [orizzontali] sono dove ci si aggancia.
 */
data class Guide(val l: Float, val t: Float, val r: Float, val b: Float, val colonne: List<Pair<Float, Float>>) {
    val verticali get() = (listOf(l, r) + colonne.flatMap { listOf(it.first, it.second) }).distinct()
    val orizzontali get() = listOf(t, b)
}

/** Quanto agganciare (in punti) e dove disegnare le guide; null = niente. */
data class Aggancio(val dx: Float, val dy: Float, val guidaX: Float?, val guidaY: Float?)

/** Un rettangolo dritto, in punti. */
data class Riquadro(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx get() = (l + r) / 2f
    val cy get() = (t + b) / 2f
}

object Grafiche {
    /**
     * Il formato per le pagine di un PDF aperto, larghe [w] e alte [h] punti di stampa, e l'abbondanza che
     * già contengono (mm): A5/A4 esatti = senza abbondanza (il libro del vocabolario: 419,5 × 595,3 = A5);
     * A5/A4 più una fascia uguale su ogni lato = quella fascia; altrimenti la forma più vicina, senza.
     */
    fun formatoPer(w: Float, h: Float): Pair<Formato, Float> {
        for (f in listOf(Formato.A5, Formato.A4)) {
            if (kotlin.math.abs(w - f.pdfW) < 2f && kotlin.math.abs(h - f.pdfH) < 2f) return f to 0f
            val bw = (w - f.pdfW) / 2f; val bh = (h - f.pdfH) / 2f
            if (bw > 0f && kotlin.math.abs(bw - bh) < 1f && bw <= mm(10f)) return f to Math.round(bw / mm(1f) * 2f) / 2f
        }
        val r = w / h.coerceAtLeast(1f)
        return Formato.entries.minByOrNull { kotlin.math.abs(it.pdfW / it.pdfH - r) }!! to 0f
    }

    /** I colori: quelli degli altri due editor + sei toni morbidi per gli sfondi. */
    val COLORI: IntArray = Ui.COLORI + intArrayOf(
        0xFFFFF4D6.toInt(), 0xFFCDEBFF.toInt(), 0xFFCFF5E1.toInt(), 0xFFFFD9E4.toInt(), 0xFF1B2A4A.toInt(), 0xFF2A1E3A.toInt(),
    )

    /** Bianco o nero, quello che si legge meglio sopra [c]: lo stesso conto dei testi ([Testo.contrario]). */
    fun contrario(c: Int): Int = Testo.contrario(c)

    fun nuova(nome: String, f: Formato) = Grafica(nome, f)

    fun prossimoId(g: Grafica): Long = (g.elementi.maxOfOrNull { it.id } ?: 0L) + 1

    // ---------------------------------------------------------------- geometria

    /** Il riquadro dritto che contiene l'oggetto girato. */
    fun ingombro(e: Elemento): Riquadro {
        if (e.rot == 0f) return Riquadro(e.x, e.y, e.x + e.w, e.y + e.h)
        val a = Math.toRadians(e.rot.toDouble())
        val c = abs(cos(a)).toFloat(); val s = abs(sin(a)).toFloat()
        val mw = (e.w * c + e.h * s) / 2f; val mh = (e.w * s + e.h * c) / 2f
        return Riquadro(e.cx - mw, e.cy - mh, e.cx + mw, e.cy + mh)
    }

    /** Il punto (px,py) nel sistema dell'oggetto (0,0 = il suo angolo, prima del giro). */
    fun locale(e: Elemento, px: Float, py: Float): Pair<Float, Float> {
        val a = Math.toRadians(-e.rot.toDouble())
        val dx = px - e.cx; val dy = py - e.cy
        val lx = (dx * cos(a) - dy * sin(a)).toFloat(); val ly = (dx * sin(a) + dy * cos(a)).toFloat()
        return lx + e.w / 2f to ly + e.h / 2f
    }

    /**
     * Quale oggetto sta sotto il dito. [margine] in punti allarga il bersaglio (le cose piccole e le
     * linee sottili si toccano lo stesso, almeno 44 punti). Con bersagli allargati che si
     * sovrappongono (righe di testo fitte, pagina 71: 8 pt di passo) non vince il più in alto ma il
     * più VICINO: prima vinceva la riga sotto e bisognava toccare sopra la riga voluta.
     * Uno che contiene il dito vince (fra questi il più in alto), tranne quando fa da fondo: un
     * oggetto più in alto che sta tutto dentro di lui (la riga sopra la tabella) e il dito è nel
     * suo bersaglio → vince il più vicino di quelli.
     */
    fun tocca(g: Grafica, px: Float, py: Float, margine: Float, pagina: Int = 0): Long? {
        data class Preso(val e: Elemento, val distanza: Float, val livello: Int)
        val presi = ArrayList<Preso>()
        g.elementi.forEachIndexed { i, e ->
            if (e.pagina != pagina || e.fermo) return@forEachIndexed
            val (lx, ly) = locale(e, px, py)
            if (e.tipo == Elemento.DISEGNO) {
                // un disegno si prende sull'inchiostro, non sulla scatola: un cerchio fatto a mano
                // attorno a una foto non deve rubarle il tocco
                val r = max(margine, 22f)
                if (e.tratti.any { Mano.tocca(it, lx / e.w, ly / e.h, r, e.w, e.h) }) presi += Preso(e, 0f, i)
                return@forEachIndexed
            }
            val mx = max(margine, (44f - e.w) / 2f).coerceAtLeast(0f)
            val my = max(margine, (44f - e.h) / 2f).coerceAtLeast(0f)
            if (lx >= -mx && lx <= e.w + mx && ly >= -my && ly <= e.h + my) {
                val dx = max(max(-lx, lx - e.w), 0f); val dy = max(max(-ly, ly - e.h), 0f)
                presi += Preso(e, hypot(dx, dy), i)
            }
        }
        if (presi.isEmpty()) return null
        val dentro = presi.filter { it.distanza == 0f }.maxByOrNull { it.livello }
        if (dentro == null) return presi.minWith(compareBy<Preso> { it.distanza }.thenByDescending { it.livello }).e.id
        val fondo = ingombro(dentro.e)
        val sopra = presi.filter { p ->
            p.livello > dentro.livello && ingombro(p.e).let { it.l >= fondo.l && it.r <= fondo.r && it.t >= fondo.t && it.b <= fondo.b }
        }
        return (sopra.minWithOrNull(compareBy<Preso> { it.distanza }.thenByDescending { it.livello }) ?: dentro).e.id
    }

    /**
     * Le righe guida della pagina [p] (null = nessuna gabbia, o margini che non lasciano spazio). Una madre
     * (p < 0) si guarda come pagina di destra; con una pagina sola non c'è piega: interno = sinistra.
     */
    fun guide(g: Grafica, p: Int): Guide? {
        val m = gabbiaDi(g, p) ?: return null
        val k = mm(1f) / g.formato.ptPerPunto // punti del foglio in un mm
        val sinistra = g.pagine > 1 && p >= 0 && p % 2 == 1
        val l = (if (sinistra) m.esterno else m.interno) * k
        val r = Grafica.LARGO - (if (sinistra) m.interno else m.esterno) * k
        val t = m.alto * k; val b = g.alto - m.basso * k
        if (r - l < 1f || b - t < 1f) return null
        val n = m.colonne.coerceIn(1, Gabbia.COLONNE_MAX)
        // lo spazio fra le colonne non mangia mai le colonne: troppo grande = colonne attaccate
        val sp = (m.spazio * k).let { if ((r - l - (n - 1) * it) / n < 1f) 0f else it }
        val cw = (r - l - (n - 1) * sp) / n
        val col = if (n == 1) emptyList() else List(n) { i -> (l + i * (cw + sp)).let { it to it + cw } }
        return Guide(l, t, r, b, col)
    }

    /** I margini che valgono sulla pagina [p] (o sulla madre, p < 0): quelli della sua madre, se li ha, se no del documento. */
    fun gabbiaDi(g: Grafica, p: Int): Gabbia? {
        val k = if (p < 0) -p else g.madre(p)
        return g.gabbieMadri.getOrNull(k - 1) ?: g.gabbia
    }

    /** La colonna (o il riquadro dei margini, con una colonna) più vicina a [x] sulla pagina [p], da sinistra a destra. */
    fun colonnaVicina(g: Grafica, p: Int, x: Float): Pair<Float, Float>? {
        val q = guide(g, p) ?: return null
        val tutte = q.colonne.ifEmpty { listOf(q.l to q.r) }
        tutte.firstOrNull { (a, b) -> x in a..b }?.let { return it }
        // fra due colonne (o fuori): quella a sinistra, come si legge; prima della prima: la prima
        return tutte.lastOrNull { it.second <= x } ?: tutte.first()
    }

    /** Dove si aggancia sulla pagina di [e]: bordi e centro del foglio, abbondanza, guide, gli altri oggetti. */
    private fun bersagli(g: Grafica, e: Elemento, conGuide: Boolean): Pair<List<Float>, List<Float>> {
        val bx = mutableListOf(0f, Grafica.LARGO / 2f, Grafica.LARGO)
        val by = mutableListOf(0f, g.alto / 2f, g.alto)
        // con l'abbondanza anche il suo bordo: un fondo che deve arrivare fin là ci si attacca
        val ab = g.abbondanza
        if (ab > 0f) { bx += listOf(-ab, Grafica.LARGO + ab); by += listOf(-ab, g.alto + ab) }
        if (conGuide) guide(g, e.pagina)?.let { bx += it.verticali; by += it.orizzontali }
        for (a in g.elementi) {
            if (a.id == e.id || a.pagina != e.pagina) continue
            val r = ingombro(a)
            bx += listOf(r.l, r.cx, r.r); by += listOf(r.t, r.cy, r.b)
        }
        return bx to by
    }

    private fun meglio(miei: List<Float>, bersagli: List<Float>, soglia: Float): Pair<Float, Float>? {
        var m: Pair<Float, Float>? = null
        for (p in miei) for (b in bersagli) {
            val d = b - p
            if (abs(d) <= soglia && (m == null || abs(d) < abs(m.first))) m = d to b
        }
        return m
    }

    /**
     * L'oggetto [e] sta per andare a (x,y): se un suo bordo o il centro passa entro [soglia]
     * punti da un bordo o dal centro del foglio, da una guida (margini, colonne) o da un altro oggetto, ci si attacca.
     */
    fun aggancia(g: Grafica, e: Elemento, x: Float, y: Float, soglia: Float, conGuide: Boolean = true): Aggancio {
        val io = ingombro(e.copy(x = x, y = y))
        val (bx, by) = bersagli(g, e, conGuide)
        val sx = meglio(listOf(io.l, io.cx, io.r), bx, soglia)
        val sy = meglio(listOf(io.t, io.cy, io.b), by, soglia)
        return Aggancio(sx?.first ?: 0f, sy?.first ?: 0f, sx?.second, sy?.second)
    }

    /**
     * Si tira un lato di [e] (dritto) e il lato va a [x]: entro [soglia] da una riga verticale (guida di
     * margine o colonna, bordo del foglio, altro oggetto) ci si attacca. Ritorna dove, o null.
     */
    fun agganciaLato(g: Grafica, e: Elemento, x: Float, soglia: Float, conGuide: Boolean = true): Float? =
        meglio(listOf(x), bersagli(g, e, conGuide).first, soglia)?.second

    /** Il giro si attacca a 0/45/90/… entro [soglia] gradi; sempre fra -180 e 180. */
    fun gradi(r: Float, soglia: Float = 4f): Float {
        var v = ((r % 360f) + 540f) % 360f - 180f
        for (q in listOf(-180f, -135f, -90f, -45f, 0f, 45f, 90f, 135f, 180f)) if (abs(v - q) < soglia) { v = q; break }
        if (v == -180f) v = 180f
        return v
    }

    /**
     * L'oggetto ingrandito di [k] attorno al suo centro. La linea cambia solo la lunghezza; il
     * testo cambia larghezza e lettere insieme (l'altezza la rimisura chi disegna).
     */
    fun scala(e: Elemento, k: Float): Elemento {
        // il testo si ferma quando le lettere arrivano al limite: riquadro e lettere restano insieme
        val kk = if (e.tipo == Elemento.TESTO) k.coerceIn(8f / e.misura, 1200f / e.misura) else k.coerceAtLeast(0.05f)
        val w = max(Elemento.MIN, e.w * kk)
        val vero = w / e.w
        val h = when {
            e.linea -> e.h
            e.tipo == Elemento.TESTO -> e.h * vero
            else -> max(Elemento.MIN, e.h * vero)
        }
        val misura = if (e.tipo == Elemento.TESTO) (e.misura * vero).coerceIn(8f, 1200f) else e.misura
        return e.copy(x = e.cx - w / 2f, y = e.cy - h / 2f, w = w, h = h, misura = misura)
    }

    /** Un oggetto nuovo al centro della [pagina], in cima a tutti. */
    /**
     * Un oggetto nuovo in mezzo a [centro] (il punto del foglio al centro di ciò che si vede: con la pagina
     * ingrandita il mezzo della pagina può stare fuori dallo schermo), o in mezzo alla pagina; dentro il foglio.
     */
    fun aggiungi(g: Grafica, e: Elemento, pagina: Int = 0, centro: Pair<Float, Float>? = null): Grafica {
        val (cx, cy) = centro ?: (Grafica.LARGO / 2f to g.alto / 2f)
        val x = (cx - e.w / 2f).coerceIn(minOf(0f, Grafica.LARGO - e.w), maxOf(0f, Grafica.LARGO - e.w))
        val y = (cy - e.h / 2f).coerceIn(minOf(0f, g.alto - e.h), maxOf(0f, g.alto - e.h))
        val n = e.copy(id = prossimoId(g), x = x, y = y, pagina = dentro(g, pagina))
        return g.copy(elementi = g.elementi + n)
    }

    fun sostituisci(g: Grafica, e: Elemento): Grafica = g.copy(elementi = g.elementi.map { if (it.id == e.id) e else it })

    fun elimina(g: Grafica, id: Long): Grafica = g.copy(elementi = g.elementi.filter { it.id != id })

    /** Una copia poco più in basso a destra, subito sopra l'originale. */
    fun duplica(g: Grafica, id: Long): Pair<Grafica, Long>? {
        val i = g.elementi.indexOfFirst { it.id == id }
        if (i < 0) return null
        val c = g.elementi[i].copy(id = prossimoId(g), x = g.elementi[i].x + 40f, y = g.elementi[i].y + 40f, gruppo = 0L)
        return g.copy(elementi = g.elementi.toMutableList().apply { add(i + 1, c) }) to c.id
    }

    /** Avanti (+1) o dietro (-1) di un posto, fra gli oggetti della stessa pagina. Al bordo non cambia niente. */
    fun livello(g: Grafica, id: Long, verso: Int): Grafica {
        val l = g.elementi.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return g
        var j = i + verso
        while (j in l.indices && l[j].pagina != l[i].pagina) j += verso
        if (j !in l.indices) return g
        val t = l[i]; l[i] = l[j]; l[j] = t
        return g.copy(elementi = l)
    }

    /** È già in cima (+1) o in fondo (-1) alla sua pagina? */
    fun alBordo(g: Grafica, id: Long, verso: Int): Boolean = livello(g, id, verso) == g

    // ---------------------------------------------------------------- pagine

    private fun riordina(g: Grafica, el: List<Elemento>, pagine: Int, madreDi: List<Int> = g.madreDi) =
        g.copy(elementi = el.sortedBy { it.pagina }, pagine = pagine, madreDi = madreDi) // sortedBy è stabile: i livelli restano

    /** Una pagina vera (0..pagine-1) o una madre (-1..-madri). */
    fun dentro(g: Grafica, p: Int): Int = if (p < 0 && g.madri > 0) p.coerceAtLeast(-g.madri) else p.coerceIn(0, g.pagine - 1)

    /** Le madri di tutte le pagine, lunghe quanto le pagine (0 dove non è detto). */
    private fun madriPiene(g: Grafica) = List(g.pagine) { g.madre(it) }

    // ---------------------------------------------------------------- pagine madri

    /**
     * Un oggetto della pagina madre su una pagina vera: nel testo (e nelle celle) «#» diventa [n]; i pezzi
     * misti dopo il segno scalano della differenza, chi lo contiene si allunga.
     */
    fun numera(e: Elemento, n: Int): Elemento {
        fun cambia(t: String, segni: List<Segno>): Pair<String, List<Segno>> {
            if ('#' !in t) return t to segni
            val num = n.toString(); val d = num.length - 1
            var testo = t; var sg = segni; var i = testo.indexOf('#')
            while (i >= 0) {
                testo = testo.substring(0, i) + num + testo.substring(i + 1)
                sg = sg.map { x -> Segno(if (x.da > i) x.da + d else x.da, if (x.a > i) x.a + d else x.a, x.stile, x.colore, x.font, x.misura) }
                i = testo.indexOf('#', i + num.length)
            }
            return testo to sg
        }
        return when (e.tipo) {
            Elemento.TESTO -> cambia(e.testo, e.segni).let { (t, sg) -> if (t == e.testo) e else e.copy(testo = t, segni = sg) }
            Elemento.TABELLA -> if (e.celle.none { '#' in it.testo }) e
                else e.copy(celle = e.celle.map { k -> cambia(k.testo, k.segni).let { (t, sg) -> k.copy(testo = t, segni = sg) } })
            else -> e
        }
    }


    /** Una madre nuova, vuota, col nome [nome]. Ritorna il foglio e il suo numero k (oggetti su pagina -k). */
    fun nuovaMadre(g: Grafica, nome: String): Pair<Grafica, Int> =
        g.copy(nomiMadri = g.nomiMadri + nome.ifBlank { "Master ${g.madri + 1}" }, gabbieMadri = gabbieMadri(g) + null) to g.madri + 1

    /** Le gabbie delle madri lunghe quanto le madri. */
    private fun gabbieMadri(g: Grafica) = List(g.madri) { g.gabbieMadri.getOrNull(it) }

    /** La madre [k] coi suoi margini ([m] = null: quelli del documento). */
    fun gabbiaMadre(g: Grafica, k: Int, m: Gabbia?): Grafica =
        if (k !in 1..g.madri) g else g.copy(gabbieMadri = gabbieMadri(g).mapIndexed { i, x -> if (i == k - 1) m else x })

    /** Le pagine [quali] usano la madre [k] (0 = nessuna). */
    fun usaMadre(g: Grafica, k: Int, quali: Iterable<Int>): Grafica {
        val m = madriPiene(g).toMutableList()
        val kk = if (k in 1..g.madri) k else 0
        for (p in quali) if (p in 0 until g.pagine) m[p] = kk
        return g.copy(madreDi = m)
    }

    fun rinominaMadre(g: Grafica, k: Int, nome: String): Grafica =
        if (k !in 1..g.madri || nome.isBlank()) g else g.copy(nomiMadri = g.nomiMadri.mapIndexed { i, n -> if (i == k - 1) nome.trim() else n })

    /** Via la madre [k] coi suoi oggetti; le pagine che la usavano restano senza, le madri dopo scalano. */
    fun eliminaMadre(g: Grafica, k: Int): Grafica {
        if (k !in 1..g.madri) return g
        val el = g.elementi.filter { it.pagina != -k }.map { if (it.pagina < -k) it.copy(pagina = it.pagina + 1) else it }
        val m = madriPiene(g).map { if (it == k) 0 else if (it > k) it - 1 else it }
        return g.copy(elementi = el.sortedBy { it.pagina }, nomiMadri = g.nomiMadri.filterIndexed { i, _ -> i != k - 1 }, madreDi = m,
            gabbieMadri = gabbieMadri(g).filterIndexed { i, _ -> i != k - 1 })
    }

    /** [n] pagine vuote dopo [dopo], con la madre di [dopo]. Ritorna il foglio e la prima nuova. */
    fun aggiungiPagine(g: Grafica, dopo: Int, n: Int): Pair<Grafica, Int> {
        val quante = n.coerceIn(0, Grafica.PAGINE_MAX - g.pagine)
        if (quante == 0) return g to dopo.coerceIn(0, g.pagine - 1)
        val p = (dopo + 1).coerceIn(0, g.pagine)
        val el = g.elementi.map { if (it.pagina >= p) it.copy(pagina = it.pagina + quante) else it }
        val m = madriPiene(g).toMutableList()
        val sua = if (dopo in 0 until g.pagine) g.madre(dopo) else 0
        repeat(quante) { m.add(p, sua) }
        return riordina(g, el, g.pagine + quante, m) to p
    }

    /** Una pagina vuota subito dopo [dopo] (-1 = in testa). Ritorna il foglio e il numero della nuova. */
    fun aggiungiPagina(g: Grafica, dopo: Int): Pair<Grafica, Int> {
        if (g.pagine >= Grafica.PAGINE_MAX) return g to dopo.coerceIn(0, g.pagine - 1)
        return aggiungiPagine(g, dopo, 1)
    }

    /** La pagina [p] copiata subito dopo di lei, oggetti con id nuovi. Ritorna il foglio e il numero della copia. */
    fun duplicaPagina(g: Grafica, p: Int): Pair<Grafica, Int> {
        if (p !in 0 until g.pagine || g.pagine >= Grafica.PAGINE_MAX) return g to p.coerceIn(0, g.pagine - 1)
        var id = prossimoId(g)
        val copie = g.di(p).map { it.copy(id = id++, pagina = p + 1) }
        val el = g.elementi.map { if (it.pagina > p) it.copy(pagina = it.pagina + 1) else it } + copie
        val m = madriPiene(g).toMutableList().also { it.add(p + 1, g.madre(p)) }
        return riordina(g, el, g.pagine + 1, m) to p + 1
    }

    /** Via la pagina [p] con quello che ha sopra. L'ultima pagina rimasta non si toglie. */
    fun eliminaPagina(g: Grafica, p: Int): Grafica {
        if (g.pagine <= 1 || p !in 0 until g.pagine) return g
        val el = g.elementi.filter { it.pagina != p }.map { if (it.pagina > p) it.copy(pagina = it.pagina - 1) else it }
        return riordina(g, el, g.pagine - 1, madriPiene(g).filterIndexed { i, _ -> i != p })
    }

    /** La pagina [da] va al posto [a]; quelle in mezzo scalano di uno. */
    fun spostaPagina(g: Grafica, da: Int, a: Int): Grafica {
        if (da !in 0 until g.pagine || a !in 0 until g.pagine || da == a) return g
        val m = madriPiene(g).toMutableList().also { val x = it.removeAt(da); it.add(a, x) }
        return riordina(g, g.elementi.map { it.copy(pagina = posto(da, a, it.pagina)) }, g.pagine, m)
    }

    /** Dove finisce la pagina [p] quando la [da] va al posto [a]. */
    fun posto(da: Int, a: Int, p: Int): Int = when {
        p == da -> a
        da < a && p in (da + 1)..a -> p - 1
        da > a && p in a until da -> p + 1
        else -> p
    }

    /**
     * Cambio di formato, la larghezza resta. Le ancore:
     * 1) chi tocca il bordo sopra o sotto resta attaccato a quel bordo (chi tocca tutti e due si allunga, testi esclusi);
     * 2) chi sta tutto dentro un oggetto ancorato e spostato viaggia con lui (il testo sulla fascia resta sulla fascia);
     * 3) gli altri tengono il centro in proporzione.
     */
    fun cambiaFormato(g: Grafica, nuovo: Formato): Grafica {
        if (nuovo == g.formato) return g
        val h0 = g.alto; val h1 = nuovo.alto; val e = 2f
        data class Ancora(val prima: Elemento, val dopo: Elemento)
        val ancore = HashMap<Long, Ancora>()
        for (o in g.elementi) {
            val r = ingombro(o)
            val su = r.t <= e; val giu = r.b >= h0 - e
            if (!su && !giu) continue
            val dopo = when {
                su && giu && o.tipo != Elemento.TESTO -> o.copy(y = o.y / h0 * h1, h = o.h / h0 * h1)
                giu && !su -> o.copy(y = o.y + (h1 - h0))
                else -> o
            }
            ancore[o.id] = Ancora(o, dopo)
        }
        val fuori = g.elementi.map { o ->
            ancore[o.id]?.let { return@map it.dopo }
            val r = ingombro(o)
            val casa = ancore.values.firstOrNull { a ->
                if (a.prima.pagina != o.pagina) return@firstOrNull false
                val dy = a.dopo.y - a.prima.y
                val c = ingombro(a.prima)
                dy != 0f && r.l >= c.l - e && r.r <= c.r + e && r.t >= c.t - e && r.b <= c.b + e
            }
            if (casa != null) o.copy(y = o.y + (casa.dopo.y - casa.prima.y))
            else o.copy(y = o.cy / h0 * h1 - o.h / 2f)
        }
        return g.copy(formato = nuovo, elementi = fuori)
    }

    /**
     * Il disegno [id] (null o sparito = uno nuovo, in cima) con questi [tratti], dati in frazioni
     * del FOGLIO: la scatola si stringe attorno all'inchiostro, dritta. Nessun tratto = il disegno
     * sparisce. Ritorna il foglio e l'id del disegno (null se non c'è più).
     */
    fun disegno(g: Grafica, id: Long?, tratti: List<Tratto>, pagina: Int = 0): Pair<Grafica, Long?> {
        val W = Grafica.LARGO; val H = g.alto
        val vecchio = g.trova(id)?.takeIf { it.tipo == Elemento.DISEGNO }
        if (tratti.isEmpty()) return (if (vecchio != null) elimina(g, vecchio.id) else g) to null
        val b = Mano.ingombro(tratti, W, H)!!
        var l = b[0] * W; var t = b[1] * H; var r = b[2] * W; var bb = b[3] * H
        if (r - l < Elemento.MIN) { val c = (l + r) / 2f; l = c - Elemento.MIN / 2f; r = c + Elemento.MIN / 2f }
        if (bb - t < Elemento.MIN) { val c = (t + bb) / 2f; t = c - Elemento.MIN / 2f; bb = c + Elemento.MIN / 2f }
        val locali = Mano.dentro(tratti, l / W, t / H, r / W, bb / H, W, H)
        val e = (vecchio ?: Elemento(prossimoId(g), Elemento.DISEGNO, 0f, 0f, 1f, 1f, pagina = dentro(g, pagina)))
            .copy(x = l, y = t, w = r - l, h = bb - t, rot = 0f, tratti = locali)
        return (if (vecchio != null) sostituisci(g, e) else g.copy(elementi = g.elementi + e)) to e.id
    }

    /** I tratti di un disegno dritto in frazioni del foglio (il contrario di [disegno]). */
    fun trattiNelFoglio(g: Grafica, e: Elemento): List<Tratto> {
        val W = Grafica.LARGO; val H = g.alto
        return Mano.fuori(e.tratti, e.x / W, e.y / H, (e.x + e.w) / W, (e.y + e.h) / H, W, H)
    }

    /**
     * Una pagina-foto: la foto di una pagina di un PDF aperto ([Archivio.salvaPagina], nome «p…») larga e alta
     * almeno quanto la pagina, ferma al suo posto. Le pagine-foto nascono ferme ([Elemento.fermo]).
     */
    fun paginaFoto(g: Grafica, e: Elemento): Boolean =
        e.tipo == Elemento.FOTO && e.pagina >= 0 && e.uri.substringAfterLast('/').startsWith("p") && e.uri.contains("/files/grafica/media/") &&
            e.x <= 0.5f && e.y <= 0.5f && e.x + e.w >= Grafica.LARGO - 0.5f && e.y + e.h >= g.alto - 0.5f && e.rot == 0f

    /** Ferma (o libera) le pagine-foto della pagina [p]. */
    fun fermaPagina(g: Grafica, p: Int, fermo: Boolean): Grafica =
        g.copy(elementi = g.elementi.map { if (it.pagina == p && (it.fermo || paginaFoto(g, it))) it.copy(fermo = fermo) else it })

    /** Le foto che la grafica usa (sfondo compreso), per non buttare un file ancora in uso. */
    fun foto(g: Grafica): Set<String> =
        (g.elementi.filter { it.tipo == Elemento.FOTO && it.uri.isNotEmpty() }.map { it.uri } + g.fotoSfondo + g.fonteParole + g.fonteListe)
            .filter { it.isNotEmpty() }.toSet()

    // ---------------------------------------------------------------- testo che continua (filo)

    /** I riquadri del filo [k] nell'ordine. */
    fun filoDi(g: Grafica, k: Long): List<Elemento> = if (k == 0L) emptyList() else g.elementi.filter { it.filo == k }.sortedBy { it.filoN }

    /** I segni del pezzo [da]..[a] di un testo, spostati per cominciare da 0. */
    fun segniDi(segni: List<Segno>, da: Int, a: Int): List<Segno> =
        segni.mapNotNull { sg -> val l = maxOf(sg.da, da); val r = minOf(sg.a, a); if (l < r) sg.copy(da = l - da, a = r - da) else null }

    /** Tutto il testo del filo, coi suoi segni, come se fosse in un riquadro solo. */
    fun testoDelFilo(riquadri: List<Elemento>): Pair<String, List<Segno>> {
        val t = StringBuilder(); val sg = ArrayList<Segno>()
        for (e in riquadri) { val o = t.length; t.append(e.testo); sg += e.segni.map { it.copy(da = it.da + o, a = it.a + o) } }
        return t.toString() to sg
    }

    /**
     * Dove va il prossimo riquadro di un filo dopo [e]: la colonna dopo sulla stessa pagina (se ci sono colonne e
     * [e] ne riempie una), se no la prima colonna della pagina dopo. Ritorna (pagina, x, y, w, h).
     */
    fun dopo(g: Grafica, e: Elemento): List<Float> {
        val q = guide(g, e.pagina)
        val col = q?.colonne?.ifEmpty { listOf(q.l to q.r) }
        if (q != null && col != null) {
            val i = col.indexOfFirst { kotlin.math.abs(it.first - e.x) < 2f }
            if (i >= 0 && i + 1 < col.size) return listOf(e.pagina.toFloat(), col[i + 1].first, q.t, col[i + 1].second - col[i + 1].first, q.b - q.t)
            val q2 = guide(g, e.pagina + 1) ?: q
            val c2 = q2.colonne.ifEmpty { listOf(q2.l to q2.r) }
            return listOf((e.pagina + 1).toFloat(), c2[0].first, q2.t, c2[0].second - c2[0].first, q2.b - q2.t)
        }
        return listOf((e.pagina + 1).toFloat(), e.x, e.y, e.w, e.h)
    }

    // ---------------------------------------------------------------- foto nel riquadro

    const val ZOOM_FOTO_MAX = 5f

    /**
     * Dove va una foto [bw]×[bh] nel riquadro di [e] (coordinate del riquadro, 0..w × 0..h): riempie o sta intera,
     * ingrandita di [Elemento.zoomFoto], col fuoco al centro; quando è più grande del riquadro non lascia mai buchi.
     */
    fun rettangoloFoto(e: Elemento, bw: Float, bh: Float): Riquadro {
        if (bw <= 0f || bh <= 0f) return Riquadro(0f, 0f, e.w, e.h)
        val base = if (e.adatta) minOf(e.w / bw, e.h / bh) else maxOf(e.w / bw, e.h / bh)
        val k = base * e.zoomFoto.coerceIn(1f, ZOOM_FOTO_MAX)
        val w = bw * k; val h = bh * k
        fun dove(lato: Float, grande: Float, fuoco: Float): Float {
            val l = lato / 2f - fuoco.coerceIn(0f, 1f) * grande
            return if (grande >= lato) l.coerceIn(lato - grande, 0f) else (lato - grande) / 2f
        }
        val l = dove(e.w, w, e.fuocoX); val t = dove(e.h, h, e.fuocoY)
        return Riquadro(l, t, l + w, t + h)
    }

    /** La foto spostata di ([dx],[dy]) punti dentro il riquadro: il fuoco nuovo, già dentro i limiti (niente punti morti). */
    fun spostaFoto(e: Elemento, bw: Float, bh: Float, dx: Float, dy: Float): Elemento {
        val r = rettangoloFoto(e, bw, bh)
        val w = r.r - r.l; val h = r.b - r.t
        val mossa = e.copy(fuocoX = e.fuocoX - dx / w, fuocoY = e.fuocoY - dy / h)
        val q = rettangoloFoto(mossa, bw, bh)
        return mossa.copy(fuocoX = ((e.w / 2f - q.l) / w), fuocoY = ((e.h / 2f - q.t) / h))
    }

    // ---------------------------------------------------------------- più oggetti insieme

    /** Chi si sceglie toccando [id]: lui, o tutto il suo gruppo. */
    fun insiemeDi(g: Grafica, id: Long): Set<Long> {
        val e = g.trova(id) ?: return emptySet()
        return if (e.gruppo == 0L) setOf(id) else g.elementi.filter { it.gruppo == e.gruppo && it.pagina == e.pagina }.map { it.id }.toSet()
    }

    /** Il riquadro che contiene tutti gli [ids]. */
    fun ingombroDi(g: Grafica, ids: Set<Long>): Riquadro? {
        val r = g.elementi.filter { it.id in ids }.map { ingombro(it) }
        if (r.isEmpty()) return null
        return Riquadro(r.minOf { it.l }, r.minOf { it.t }, r.maxOf { it.r }, r.maxOf { it.b })
    }

    fun spostaTutti(g: Grafica, ids: Set<Long>, dx: Float, dy: Float): Grafica =
        g.copy(elementi = g.elementi.map { if (it.id in ids) it.copy(x = it.x + dx, y = it.y + dy) else it })

    /**
     * Libro aperto: gli oggetti [ids] della pagina [aperta] lasciati oltre la piega passano sulla [compagna]
     * (l'altra pagina accanto, -1 = nessuna), allo stesso posto sullo schermo. [compagnaASinistra] = la compagna sta a
     * sinistra. Conta il centro del loro ingombro: oltre la piega = dall'altra parte.
     */
    fun dopoLaPiega(g: Grafica, ids: Set<Long>, aperta: Int, compagna: Int, compagnaASinistra: Boolean): Grafica {
        if (compagna < 0 || compagna == aperta || ids.isEmpty()) return g
        val qui = g.elementi.filter { it.id in ids && it.pagina == aperta }.map { it.id }.toSet()
        val r = ingombroDi(g, qui) ?: return g
        val oltre = if (compagnaASinistra) r.cx < 0f else r.cx > Grafica.LARGO
        if (!oltre) return g
        val dx = if (compagnaASinistra) Grafica.LARGO else -Grafica.LARGO
        // sopra a quelli che già stanno di là, come un oggetto appena messo
        return g.copy(elementi = g.elementi.filter { it.id !in qui } +
            g.elementi.filter { it.id in qui }.map { it.copy(pagina = compagna, x = it.x + dx) })
    }

    const val A_SINISTRA = 0; const val A_CENTRO = 1; const val A_DESTRA = 2
    const val IN_ALTO = 3; const val A_META = 4; const val IN_BASSO = 5

    /**
     * Gli [ids] allineati fra loro (al riquadro che li contiene tutti), come InDesign; i gruppi si muovono
     * interi. Con un oggetto solo: alla pagina.
     */
    fun allinea(g: Grafica, ids: Set<Long>, come: Int): Grafica {
        val tutto = (if (ids.size == 1) g.trova(ids.first())?.let { Riquadro(0f, 0f, Grafica.LARGO, g.alto) } else ingombroDi(g, ids)) ?: return g
        // i pezzi che si muovono: ogni gruppo è un pezzo solo
        val pezzi = ids.mapNotNull { g.trova(it) }.groupBy { if (it.gruppo != 0L) "g${it.gruppo}" else "e${it.id}" }.values
        var n = g
        for (p in pezzi) {
            val q = p.map { it.id }.toSet()
            val r = ingombroDi(g, q) ?: continue
            val dx = when (come) { A_SINISTRA -> tutto.l - r.l; A_CENTRO -> tutto.cx - r.cx; A_DESTRA -> tutto.r - r.r; else -> 0f }
            val dy = when (come) { IN_ALTO -> tutto.t - r.t; A_META -> tutto.cy - r.cy; IN_BASSO -> tutto.b - r.b; else -> 0f }
            n = spostaTutti(n, q, dx, dy)
        }
        return n
    }

    /** Spazi uguali fra gli [ids] (almeno tre pezzi): il primo e l'ultimo restano dove sono. */
    fun distribuisci(g: Grafica, ids: Set<Long>, orizzontale: Boolean): Grafica {
        val pezzi = ids.mapNotNull { g.trova(it) }.groupBy { if (it.gruppo != 0L) "g${it.gruppo}" else "e${it.id}" }.values
            .map { p -> p.map { it.id }.toSet() }.mapNotNull { q -> ingombroDi(g, q)?.let { q to it } }
            .sortedBy { (_, r) -> if (orizzontale) r.l else r.t }
        if (pezzi.size < 3) return g
        val primo = pezzi.first().second; val ultimo = pezzi.last().second
        val pieno = pezzi.sumOf { (_, r) -> (if (orizzontale) r.r - r.l else r.b - r.t).toDouble() }.toFloat()
        val spazio = ((if (orizzontale) ultimo.r - primo.l else ultimo.b - primo.t) - pieno) / (pezzi.size - 1)
        var n = g
        var dove = if (orizzontale) primo.l else primo.t
        for ((q, r) in pezzi) {
            val d = dove - (if (orizzontale) r.l else r.t)
            n = if (orizzontale) spostaTutti(n, q, d, 0f) else spostaTutti(n, q, 0f, d)
            dove += (if (orizzontale) r.r - r.l else r.b - r.t) + spazio
        }
        return n
    }

    /** Gli [ids] diventano un gruppo (nuovo); ritorna il foglio. Con meno di due: niente. */
    fun raggruppa(g: Grafica, ids: Set<Long>): Grafica {
        if (ids.size < 2) return g
        val k = (g.elementi.maxOfOrNull { it.gruppo } ?: 0L) + 1
        return g.copy(elementi = g.elementi.map { if (it.id in ids) it.copy(gruppo = k) else it })
    }

    fun separa(g: Grafica, ids: Set<Long>): Grafica = g.copy(elementi = g.elementi.map { if (it.id in ids) it.copy(gruppo = 0L) else it })

    fun eliminaTutti(g: Grafica, ids: Set<Long>): Grafica = g.copy(elementi = g.elementi.filter { it.id !in ids })

    /** Una copia di tutti gli [ids] poco più in basso a destra (i gruppi restano gruppi, nuovi). Ritorna foglio e id nuovi. */
    fun duplicaTutti(g: Grafica, ids: Set<Long>): Pair<Grafica, Set<Long>> {
        var id = prossimoId(g)
        val gk = (g.elementi.maxOfOrNull { it.gruppo } ?: 0L)
        val copie = g.elementi.filter { it.id in ids }.map { it.copy(id = id++, x = it.x + 30f, y = it.y + 30f, gruppo = if (it.gruppo != 0L) it.gruppo + gk else 0L) }
        return g.copy(elementi = (g.elementi + copie).sortedBy { it.pagina }) to copie.map { it.id }.toSet()
    }

    // ---------------------------------------------------------------- stili di testo

    /** Il testo [e] vestito dallo stile [s] (numero [k]): carattere, misura, colore, allineamento e B/I/U su tutto. */
    fun conStile(e: Elemento, s: StileTesto, k: Int): Elemento {
        val n = e.testo.length
        val segni = if (s.segno != 0 && n > 0) Ricco.normalizza(n, listOf(Segno(0, n, s.segno and Ricco.TUTTI))) else emptyList()
        return e.copy(font = s.font, misura = s.misura, colore = s.colore, allinea = s.allinea, segni = segni, classe = k)
    }

    /**
     * Lo stile di un testo nuovo (come InDesign: lo stile scelto vale anche per quello che si scrive dopo):
     * l'ultimo stile toccato [scelto] (0 = «None» toccato, null = nessuno ancora), se no quello dell'ultimo testo
     * se ha ancora il suo aspetto. 0 = nessuno.
     */
    fun stileDelNuovo(g: Grafica, scelto: Int?, ultimo: Elemento?): Int {
        if (scelto != null) return scelto.takeIf { it in 1..g.stili.size } ?: 0
        val k = ultimo?.classe ?: 0
        return if (k in 1..g.stili.size && !cambiatoDalloStile(g, ultimo!!)) k else 0
    }

    /** Uno stile nuovo preso dall'aspetto del testo [e] (B/I/U solo se valgono su tutto il testo). */
    fun stileDa(nome: String, e: Elemento): StileTesto {
        val n = e.testo.length
        val bit = listOf(Ricco.GRASSETTO, Ricco.CORSIVO, Ricco.SOTTOLINEATO).filter { n > 0 && Ricco.tutto(n, e.segni, 0, n, it) }.fold(0) { a, b -> a or b }
        return StileTesto(nome.trim(), e.font, e.misura, e.colore, e.allinea, bit)
    }

    /** Il nome che si propone per uno stile nuovo: prima Title, Heading, Body, poi «Style N». */
    fun nomeNuovoStile(g: Grafica): String =
        listOf("Title", "Heading", "Body").firstOrNull { n -> g.stili.none { it.nome.equals(n, true) } } ?: "Style ${g.stili.size + 1}"

    /** Lo stile del testo [e] (null = nessuno). */
    fun stileDi(g: Grafica, e: Elemento): StileTesto? = g.stili.getOrNull(e.classe - 1)

    /** Il testo [e] è legato a uno stile ma ha qualcosa di diverso (si può aggiornare lo stile da lui). */
    fun cambiatoDalloStile(g: Grafica, e: Elemento): Boolean {
        val s = stileDi(g, e) ?: return false
        return conStile(e, s, e.classe).copy(h = e.h) != e
    }

    /**
     * I testi [ids] prendono lo stile [k] (0 = staccati: tengono l'aspetto che hanno). [alto] rimisura
     * l'altezza di un testo (la sa solo chi disegna).
     */
    fun usaStile(g: Grafica, ids: Set<Long>, k: Int, alto: (Elemento) -> Float = { it.h }): Grafica {
        val s = g.stili.getOrNull(k - 1)
        return g.copy(elementi = g.elementi.map { e ->
            if (e.id !in ids || e.tipo != Elemento.TESTO) e
            else if (s == null) e.copy(classe = 0)
            else conStile(e, s, k).let { it.copy(h = alto(it)) }
        })
    }

    /** Uno stile nuovo; ritorna il foglio e il suo numero. */
    fun nuovoStile(g: Grafica, s: StileTesto): Pair<Grafica, Int> = g.copy(stili = g.stili + s) to g.stili.size + 1

    /** Lo stile [k] diventa [s] e tutti i testi (e le celle) legati a lui cambiano, su ogni pagina e sulle madri. */
    fun cambiaStile(g: Grafica, k: Int, s: StileTesto, alto: (Elemento) -> Float = { it.h }): Grafica {
        if (k !in 1..g.stili.size) return g
        val n = g.copy(stili = g.stili.mapIndexed { i, x -> if (i == k - 1) s else x })
        return n.copy(elementi = n.elementi.map { e ->
            when {
                e.tipo == Elemento.TESTO && e.classe == k -> conStile(e, s, k).let { it.copy(h = alto(it)) }
                e.tipo == Elemento.TABELLA && e.celle.any { it.classe == k } -> e.copy(celle = e.celle.map { c -> if (c.classe == k) conStileCella(e, c, s, k) else c })
                else -> e
            }
        })
    }

    // ---------------------------------------------------------------- stili nelle celle
    // Una cella non ha carattere, misura e colore suoi: li dà la tabella, e il resto i segni del testo. Lo stile in
    // una cella = segni su tutto il suo testo (solo dove è diverso dalla tabella) + l'allineamento.

    /** La cella [cel] della tabella [t] con lo stile [s] (numero [k]). */
    fun conStileCella(t: Elemento, cel: Cella, s: StileTesto, k: Int): Cella {
        val n = cel.testo.length
        val a = Aspetto(s.segno and Ricco.TUTTI, if (s.colore == t.colore) 0 else s.colore, if (s.font == t.font) "" else s.font, if (s.misura == t.misura) 0f else s.misura)
        val segni = if (n == 0 || a.normale) emptyList() else Ricco.normalizza(n, listOf(Segno(0, n, a.stile, a.colore, a.font, a.misura)))
        return cel.copy(segni = segni, allinea = s.allinea, classe = k)
    }

    /** Uno stile nuovo preso dalla cella: l'aspetto della prima lettera, B/I/U solo se valgono su tutto. */
    fun stileDaCella(nome: String, t: Elemento, cel: Cella): StileTesto {
        val n = cel.testo.length
        val a = if (n > 0) Ricco.aspetti(n, cel.segni)[0] else Aspetto.NORMALE
        val bit = listOf(Ricco.GRASSETTO, Ricco.CORSIVO, Ricco.SOTTOLINEATO).filter { n > 0 && Ricco.tutto(n, cel.segni, 0, n, it) }.fold(0) { x, y -> x or y }
        return StileTesto(nome.trim(), a.font.ifEmpty { t.font }, if (a.misura > 0f) a.misura else t.misura, if (a.colore != 0) a.colore else t.colore, cel.allinea, bit)
    }

    /** La cella è legata a uno stile ma ha qualcosa di diverso. */
    fun cambiataDalloStile(g: Grafica, t: Elemento, cel: Cella): Boolean {
        val s = g.stili.getOrNull(cel.classe - 1) ?: return false
        return conStileCella(t, cel, s, cel.classe) != cel
    }

    /** La cella (r,c) della tabella [id] prende lo stile [k] (0 = staccata: tiene l'aspetto che ha). */
    fun usaStileCella(g: Grafica, id: Long, r: Int, c: Int, k: Int): Grafica {
        val t = g.trova(id)?.takeIf { it.tipo == Elemento.TABELLA } ?: return g
        val cel = Tabelle.cella(t, r, c) ?: return g
        val s = g.stili.getOrNull(k - 1)
        return sostituisci(g, Tabelle.conCella(t, r, c, if (s == null) cel.copy(classe = 0) else conStileCella(t, cel, s, k)))
    }

    /** Si scrive in una cella vuota legata a uno stile: le prime lettere prendono lo stile. */
    fun scrittaInCella(g: Grafica, t: Elemento, r: Int, c: Int, testo: String, segni: List<Segno>): Cella? {
        val cel = Tabelle.cella(t, r, c) ?: return null
        val n = cel.copy(testo = Tabelle.pulito(testo), segni = segni)
        val s = g.stili.getOrNull(cel.classe - 1)
        return if (s != null && cel.testo.isEmpty() && n.testo.isNotEmpty()) conStileCella(t, n, s, cel.classe) else n
    }

    fun rinominaStile(g: Grafica, k: Int, nome: String): Grafica =
        if (k !in 1..g.stili.size || nome.isBlank()) g else g.copy(stili = g.stili.mapIndexed { i, x -> if (i == k - 1) x.copy(nome = nome.trim()) else x })

    /** Via lo stile [k]: i suoi testi restano come sono, staccati; gli stili dopo scalano. */
    fun eliminaStile(g: Grafica, k: Int): Grafica {
        if (k !in 1..g.stili.size) return g
        return g.copy(stili = g.stili.filterIndexed { i, _ -> i != k - 1 },
            elementi = g.elementi.map { e0 ->
                val e = if (e0.tipo == Elemento.TABELLA && e0.celle.any { it.classe >= k })
                    e0.copy(celle = e0.celle.map { c -> when { c.classe == k -> c.copy(classe = 0); c.classe > k -> c.copy(classe = c.classe - 1); else -> c } }) else e0
                when { e.classe == k -> e.copy(classe = 0); e.classe > k -> e.copy(classe = e.classe - 1); else -> e } })
    }

    // ---------------------------------------------------------------- salvataggio
    // «151grafica 1<TAB>nome<TAB>formato<TAB>sfondo<TAB>fotoSfondo<TAB>vela», poi una riga «E» per oggetto,
    // nell'ordine dei livelli (il primo sta sotto). Righe rotte si saltano, mai un crash.
    // Un disegno ha un campo in più in fondo (25), i tratti: le altre righe restano a 24 campi.
    // Un testo con «Outline»/«Box» su misura ne ha 27: il 25° vuoto, poi secondo colore e spessore.
    // Un testo misto ne ha 28: dopo i 27 i segni (vedi [Ricco.scrivi]). Una tabella ne ha 25: il 25° è lei ([Tabelle.scrivi]).
    // Più pagine: una riga «P<TAB>n» apre la pagina n e gli oggetti che seguono stanno lì. Senza
    // righe «P» (i file di prima) tutto è sulla prima pagina; una pagina vuota ha la sua riga lo stesso.

    private const val TESTA = "151grafica 1"

    fun scrivi(g: Grafica): String = buildString {
        append(listOf(TESTA, Foto.chiudi(g.nome), g.formato.id, g.sfondo, Foto.chiudi(g.fotoSfondo), g.vela).joinToString("\t")).append('\n')
        val perPagina = g.elementi.groupBy { if (it.pagina < 0 && -it.pagina <= g.madri) it.pagina else it.pagina.coerceIn(0, g.pagine - 1) }
        // le madri prima: «M<TAB>k<TAB>nome» apre la madre k; poi «A<TAB>k,k,…» quale madre per ogni pagina
        if (g.sceltaAbbondanza != null) append("B\t").append(g.sceltaAbbondanza).append('\n')
        // «G<TAB>alto<TAB>basso<TAB>interno<TAB>esterno<TAB>colonne<TAB>spazio»: margini e colonne (le app di prima la saltano)
        g.gabbia?.let { gabbia(it) }
        if (g.perStampa != Stampa()) append("X\t").append(if (g.perStampa.segni) 1 else 0).append('\t').append(if (g.perStampa.foglio) 1 else 0)
            .append('\t').append(if (g.perStampa.retro) 1 else 0).append('\n')
        // «S<TAB>nome<TAB>font<TAB>misura<TAB>colore<TAB>allinea<TAB>segno»: gli stili di testo, nell'ordine
        for (st in g.stili) append(listOf("S", Foto.chiudi(st.nome), Foto.chiudi(st.font), st.misura, st.colore, st.allinea, st.segno).joinToString("\t")).append('\n')
        val conMadri = g.madri > 0
        for (k in 1..g.madri) {
            append("M\t").append(k).append('\t').append(Foto.chiudi(g.nomiMadri[k - 1])).append('\n')
            // una «G» subito dopo la «M»: i margini propri di quella madre
            g.gabbieMadri.getOrNull(k - 1)?.let { gabbia(it) }
            for (e in perPagina[-k].orEmpty()) riga(e)
        }
        if (conMadri) append("A\t").append(List(g.pagine) { g.madre(it) }.joinToString(",")).append('\n')
        for (p in 0 until g.pagine) {
            if (g.pagine > 1 || conMadri) append("P\t").append(p).append('\n')
            for (e in perPagina[p].orEmpty()) riga(e)
        }
        // le foto inquadrate a mano: «F<TAB>id<TAB>adatta<TAB>zoom<TAB>fuocoX<TAB>fuocoY»
        for (e in g.elementi) if (e.tipo == Elemento.FOTO && (e.adatta || e.zoomFoto != 1f || e.fuocoX != 0.5f || e.fuocoY != 0.5f))
            append(listOf("F", e.id, if (e.adatta) 1 else 0, e.zoomFoto, e.fuocoX, e.fuocoY).joinToString("\t")).append('\n')
        // i fili del testo, nell'ordine: «T<TAB>filo<TAB>id,id,…»
        for ((k, el) in g.elementi.filter { it.filo != 0L }.groupBy { it.filo }) append("T\t").append(k).append('\t').append(el.sortedBy { it.filoN }.joinToString(",") { it.id.toString() }).append('\n')
        // in fondo i gruppi, «R<TAB>gruppo<TAB>id,id,…», e chi è legato a quale stile: «C<TAB>k<TAB>id,id,…»
        // «D<TAB>file delle parole<TAB>file delle liste»: il libro nato da una lista; «K<TAB>id<TAB>chiave» per oggetto
        if (g.fonteParole.isNotEmpty()) append("D\t").append(Foto.chiudi(g.fonteParole)).append('\t').append(Foto.chiudi(g.fonteListe)).append('\n')
        for (e in g.elementi) if (e.chiave.isNotEmpty()) append("K\t").append(e.id).append('\t').append(Foto.chiudi(e.chiave)).append('\n')
        // «L<TAB>id,id»: gli oggetti fermi; scritta anche vuota se ci sono pagine-foto (così non si rifermano da sole)
        if (g.elementi.any { it.fermo || Grafiche.paginaFoto(g, it) }) append("L\t").append(g.elementi.filter { it.fermo }.joinToString(",") { it.id.toString() }).append('\n')
        for ((k, el) in g.elementi.filter { it.gruppo != 0L }.groupBy { it.gruppo }) if (el.size > 1) append("R\t").append(k).append('\t').append(el.joinToString(",") { it.id.toString() }).append('\n')
        for (k in 1..g.stili.size) {
            val ids = g.elementi.filter { it.tipo == Elemento.TESTO && it.classe == k }.map { it.id }
            if (ids.isNotEmpty()) append("C\t").append(k).append('\t').append(ids.joinToString(",")).append('\n')
        }
    }

    private fun StringBuilder.gabbia(m: Gabbia) {
        append(listOf("G", m.alto, m.basso, m.interno, m.esterno, m.colonne, m.spazio).joinToString("\t")).append('\n')
    }

    private fun StringBuilder.riga(e: Elemento) {
            run {
                append(listOf(
                    "E", e.id, e.tipo, e.x, e.y, e.w, e.h, e.rot, e.opacita,
                    Foto.chiudi(e.testo), Foto.chiudi(e.font), e.misura, e.colore, e.stile, e.allinea,
                    Foto.chiudi(e.uri), e.maschera, e.bordo,
                    Foto.chiudi(e.forma), e.riempi, e.contorno, e.spessore, e.tratto, e.raggio,
                ).joinToString("\t"))
                if (e.tipo == Elemento.DISEGNO) append('\t').append(Mano.scriviTutti(e.tratti))
                else if (e.tipo == Elemento.TABELLA) append('\t').append(Foto.chiudi(Tabelle.scrivi(e)))
                else if (e.tipo == Elemento.TESTO && (e.secondo != 0 || e.spessoreTesto != Testo.SPESSORE_STD || e.segni.isNotEmpty())) {
                    append("\t\t").append(e.secondo).append('\t').append(e.spessoreTesto)
                    if (e.segni.isNotEmpty()) append('\t').append(Ricco.scrivi(e.segni))
                }
                append('\n')
            }
        }

    fun leggi(s: String): Grafica? {
        val righe = s.split('\n')
        val p = righe.firstOrNull()?.trimEnd('\r')?.split('\t') ?: return null
        if (p.size != 6 || p[0] != TESTA) return null
        val testa = try {
            Grafica(Foto.apri(p[1]), Formato.da(p[2]), p[3].toInt(), Foto.apri(p[4]), p[5].toInt().coerceIn(0, Grafica.VELA_MAX))
        } catch (_: Exception) { return null }
        val el = mutableListOf<Elemento>()
        var pagina = 0; var pagine = 1; var scelta: Float? = null; var gabbia: Gabbia? = null; var perStampa = Stampa()
        val gabbieM = mutableMapOf<Int, Gabbia>(); val stili = mutableListOf<StileTesto>(); val classi = mutableMapOf<Long, Int>(); val gruppi = mutableMapOf<Long, Long>()
        var fermi: Set<Long>? = null
        var fonti: Pair<String, String>? = null; val chiavi = HashMap<Long, String>()
        val fili = mutableMapOf<Long, Pair<Long, Int>>()
        val inquadrate = mutableMapOf<Long, Triple<Boolean, Float, Pair<Float, Float>>>()
        val nomi = mutableListOf<String>(); var madreDi = emptyList<Int>()
        for (r in righe.drop(1)) {
            val q = r.trimEnd('\r').split('\t')
            if (q.size == 2 && q[0] == "B") { scelta = q[1].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 10f); continue }
            if (q.size == 7 && q[0] == "G") {
                val v = q.drop(1).map { it.toFloatOrNull() }
                if (v.all { it != null && it.isFinite() }) {
                    val m = Gabbia(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!.toInt(), v[5]!!).giusta()
                    if (pagina < 0) gabbieM[-pagina] = m else gabbia = m
                }
                continue
            }
            if (q.size in 3..4 && q[0] == "X") { perStampa = Stampa(q[1] == "1", q[2] == "1", q.getOrNull(3) == "1"); continue }
            if (q.size == 7 && q[0] == "S") {
                try {
                    val m = q[3].toFloat()
                    if (m.isFinite()) stili += StileTesto(Foto.apri(q[1]).ifBlank { "Style ${stili.size + 1}" }, Foto.apri(q[2]), m.coerceIn(8f, 1200f),
                        q[4].toInt(), q[5].toInt().coerceIn(0, 2), q[6].toInt() and Ricco.TUTTI)
                } catch (_: Exception) { }
                continue
            }
            if (q.size == 6 && q[0] == "F") {
                val id = q[1].toLongOrNull(); val v = q.drop(3).map { it.toFloatOrNull() }
                if (id != null && v.all { it != null && it.isFinite() }) inquadrate[id] = Triple(q[2] == "1", v[0]!!, v[1]!! to v[2]!!)
                continue
            }
            if (q.size == 3 && q[0] == "T") {
                val k = q[1].toLongOrNull()
                if (k != null && k > 0) q[2].split(',').mapNotNull { it.toLongOrNull() }.forEachIndexed { i, id -> fili[id] = k to i }
                continue
            }
            if (q.size == 3 && q[0] == "D") { fonti = Foto.apri(q[1]) to Foto.apri(q[2]); continue }
            if (q.size == 3 && q[0] == "K") { q[1].toLongOrNull()?.let { chiavi[it] = Foto.apri(q[2]) }; continue }
            if (q.size == 2 && q[0] == "L") { fermi = q[1].split(',').mapNotNull { it.toLongOrNull() }.toSet(); continue }
            if (q.size == 3 && q[0] == "R") {
                val k = q[1].toLongOrNull()
                if (k != null && k > 0) q[2].split(',').mapNotNull { it.toLongOrNull() }.forEach { gruppi[it] = k }
                continue
            }
            if (q.size == 3 && q[0] == "C") {
                val k = q[1].toIntOrNull()
                if (k != null) q[2].split(',').mapNotNull { it.toLongOrNull() }.forEach { classi[it] = k }
                continue
            }
            if (q.size == 3 && q[0] == "M") {
                val k = q[1].toIntOrNull()
                if (k != null && k == nomi.size + 1 && k <= 26) { nomi += Foto.apri(q[2]); pagina = -k }
                continue
            }
            if (q.size == 2 && q[0] == "A") { madreDi = q[1].split(',').map { it.toIntOrNull() ?: 0 }; continue }
            if (q.size == 2 && q[0] == "P") {
                val n = q[1].toIntOrNull()
                if (n != null && n in 0 until Grafica.PAGINE_MAX) { pagina = n; pagine = max(pagine, n + 1) }
                continue
            }
            if ((q.size != 24 && q.size != 25 && q.size != 27 && q.size != 28) || q[0] != "E") continue
            try {
                val tipo = q[2].toInt()
                if (tipo !in 0..4) continue
                val tab = if (tipo == Elemento.TABELLA) (Tabelle.leggi(Foto.apri(q.getOrElse(24) { "" })) ?: continue) else null
                val tratti = if (tipo == Elemento.DISEGNO) Mano.leggiTutti(q.getOrElse(24) { "" }) else emptyList()
                if (tipo == Elemento.DISEGNO && tratti.isEmpty()) continue
                val w = q[5].toFloat(); val h = q[6].toFloat()
                if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f) continue
                el += Elemento(
                    id = q[1].toLong(), tipo = tipo, x = q[3].toFloat(), y = q[4].toFloat(), w = w, h = h,
                    rot = q[7].toFloat(), opacita = q[8].toInt().coerceIn(0, 100),
                    testo = Foto.apri(q[9]), font = Foto.apri(q[10]), misura = q[11].toFloat().coerceIn(8f, 1200f),
                    colore = q[12].toInt(), stile = q[13].toInt().coerceIn(0, 3), allinea = q[14].toInt().coerceIn(0, 2),
                    uri = Foto.apri(q[15]), maschera = q[16].toInt().coerceIn(0, 2), bordo = q[17].toInt(),
                    forma = Foto.apri(q[18]), riempi = q[19].toInt(), contorno = q[20].toInt(),
                    spessore = q[21].toFloat().coerceIn(0.3f, 80f), tratto = q[22].toInt().coerceIn(0, 2), raggio = q[23].toFloat(),
                    tratti = tratti,
                    secondo = if (q.size >= 27 && tipo == Elemento.TESTO) q[25].toInt() else 0,
                    spessoreTesto = if (q.size >= 27 && tipo == Elemento.TESTO) Testo.spessoreGiusto(q[26].toFloat()) else Testo.SPESSORE_STD,
                    segni = if (q.size == 28 && tipo == Elemento.TESTO) Ricco.leggi(q[27], Foto.apri(q[9]).length) else emptyList(),
                    colonne = tab?.first ?: emptyList(), righe = tab?.second ?: emptyList(), celle = tab?.third ?: emptyList(),
                    pagina = pagina,
                )
            } catch (_: Exception) { /* riga rotta: si salta */ }
        }
        // due oggetti con lo stesso id confonderebbero la scelta: vince il primo
        val legati = el.distinctBy { it.id }.map { e -> classi[e.id]?.takeIf { e.tipo == Elemento.TESTO && it in 1..stili.size }?.let { e.copy(classe = it) } ?: e }
            .map { e -> gruppi[e.id]?.let { e.copy(gruppo = it) } ?: e }
            .map { e -> fili[e.id]?.takeIf { e.tipo == Elemento.TESTO }?.let { (k, i) -> e.copy(filo = k, filoN = i) } ?: e }
            .map { e -> inquadrate[e.id]?.takeIf { e.tipo == Elemento.FOTO }?.let { (a, z, f) ->
                e.copy(adatta = a, zoomFoto = z.coerceIn(1f, Grafiche.ZOOM_FOTO_MAX), fuocoX = f.first.coerceIn(0f, 1f), fuocoY = f.second.coerceIn(0f, 1f)) } ?: e }
        // i file di prima della riga «L»: le pagine-foto dei PDF aperti nascono ferme
        val conFermi = legati.map { e -> if (fermi?.contains(e.id) ?: Grafiche.paginaFoto(testa.copy(pagine = pagine), e)) e.copy(fermo = true) else e }
            .map { e -> chiavi[e.id]?.let { e.copy(chiave = it) } ?: e }
        return testa.copy(elementi = conFermi.sortedBy { it.pagina }, pagine = pagine, fonteParole = fonti?.first ?: "", fonteListe = fonti?.second ?: "", sceltaAbbondanza = scelta, stili = stili, perStampa = perStampa,
            gabbieMadri = List(nomi.size) { gabbieM[it + 1] },
            nomiMadri = nomi, madreDi = madreDi.take(pagine).map { if (it in 1..nomi.size) it else 0 }, gabbia = gabbia)
    }

    /** Ready-made designs shown under «Start from a template»: none built in yet. */
    val MODELLI: List<Grafica> = emptyList()
}
