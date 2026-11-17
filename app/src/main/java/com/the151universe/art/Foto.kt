package com.the151universe.art

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * L'editor foto, senza Android: cosa ha cambiato chi lo usa (geometria, luce, filtro, testi),
 * i conti del ritaglio e del raddrizzare, il colore pixel per pixel, il salvataggio dello stato.
 * Tutto qui dentro e' puro, cosi' si prova sul PC (app/src/test).
 *
 * Il «quadro» e' la foto dopo i giri e lo specchio (W×H). Ritaglio e testi stanno nel quadro,
 * in coordinate da 0 a 1: cosi' l'anteprima piccola e il file grande coincidono.
 */

/** Il ritaglio, in frazioni del quadro (sinistra, alto, destra, basso). */
data class Ritaglio(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val largo get() = r - l
    val alto get() = b - t
}

/** Le sei regolazioni della luce, ognuna da -100 a 100 (0 = come la foto). */
data class Luce(
    val luminosita: Int = 0,
    val contrasto: Int = 0,
    val saturazione: Int = 0,
    val calore: Int = 0,
    val ombre: Int = 0,
    val luci: Int = 0,
) {
    val neutra get() = this == Luce()
}

/**
 * Un testo sopra la foto. [x],[y] = centro, in frazioni del quadro; [misura] = altezza
 * delle lettere in frazioni del lato corto del quadro; [stile] = uno di [Testo.SEMPLICE]…[Testo.RIQUADRO].
 */
data class Testo(
    val id: Long,
    val testo: String,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val misura: Float = 0.09f,
    val colore: Int = 0xFFFFFFFF.toInt(),
    val font: String = Caratteri.PRIMO,
    val stile: Int = OMBRA,
    /** il secondo colore: il bordo di «Outline», il fondo di «Box». 0 = automatico (bianco o nero, il contrario del testo) */
    val secondo: Int = 0,
    /** lo spessore del bordo di «Outline», in frazioni dell'altezza delle lettere */
    val spessore: Float = SPESSORE_STD,
) {
    companion object {
        const val SEMPLICE = 0
        const val OMBRA = 1
        const val CONTORNO = 2
        const val RIQUADRO = 3
        const val MISURA_MIN = 0.02f
        const val MISURA_MAX = 0.6f
        const val SPESSORE_STD = 0.12f
        const val SPESSORE_MIN = 0.03f
        const val SPESSORE_MAX = 0.3f

        /** Bianco sulle cose scure, nero sulle chiare. */
        fun contrario(c: Int): Int {
            val y = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
            return if (y > 150) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        /** Il colore del bordo di «Outline»: quello scelto, o il contrario del testo. */
        fun coloreBordo(colore: Int, secondo: Int) = if (secondo != 0) secondo else contrario(colore)

        /**
         * «Box»: (fondo, lettere). Automatico = come sempre, il fondo prende il colore e le lettere il
         * contrario; col fondo scelto le lettere restano del colore del testo.
         */
        fun riquadro(colore: Int, secondo: Int): Pair<Int, Int> =
            if (secondo != 0) secondo to colore else colore to contrario(colore)

        fun spessoreGiusto(v: Float) = if (v.isFinite()) v.coerceIn(SPESSORE_MIN, SPESSORE_MAX) else SPESSORE_STD
    }
}

/** Tutto quello che chi lo usa ha cambiato. L'originale non si tocca mai: si salva sempre una copia. */
data class Modifiche(
    /** quarti di giro in senso orario, 0..3, fatti DOPO lo specchio */
    val giri: Int = 0,
    val specchio: Boolean = false,
    /** raddrizza, in gradi (-45..45); la foto si ingrandisce quanto basta per non lasciare angoli vuoti */
    val raddrizza: Float = 0f,
    val ritaglio: Ritaglio = Ritaglio(),
    /** la forma scelta per il ritaglio: un id di [Foto.RAPPORTI] */
    val rapporto: String = "free",
    val luce: Luce = Luce(),
    val filtro: String = "",
    /** forza del filtro, 0..100 */
    val forza: Int = 100,
    val testi: List<Testo> = emptyList(),
    /** il disegno a mano libera, nel quadro come i testi (sopra la foto, sotto i testi) */
    val tratti: List<Tratto> = emptyList(),
)

/** Un filtro = poche operazioni semplici, fatte in quest'ordine. */
data class Filtro(
    val id: String,
    val nome: String,
    val saturazione: Float = 1f,
    val contrasto: Float = 1f,
    val luce: Float = 0f,
    val r: Float = 1f,
    val g: Float = 1f,
    val b: Float = 1f,
    /** quanto seppia, 0..1 */
    val seppia: Float = 0f,
    /** quanto si alzano i neri (aspetto «sbiadito»), 0..1 */
    val sbiadito: Float = 0f,
)

/** Una foto fra le bozze: il nome, la foto di partenza (mai toccata), le modifiche. [png] = si salva PNG. */
data class BozzaFoto(val nome: String, val uri: String, val m: Modifiche, val png: Boolean = false)

enum class Maniglia { SPOSTA, NO, NE, SO, SE, N, S, O, E }

object Foto {
    const val RADDRIZZA_MAX = 45f
    /** Il ritaglio piu' piccolo: questa frazione del lato corto del quadro. */
    const val RITAGLIO_MIN = 0.08f
    /** Oltre questi pixel la foto si salva rimpicciolita (la memoria del telefono non e' infinita). */
    const val PIXEL_MAX = 24_000_000L

    /** id, nome sul bottone. «orig» = la forma della foto; «free» = a mano libera. */
    val RAPPORTI = listOf(
        "free" to "Free", "orig" to "Original", "1:1" to "1:1", "4:5" to "4:5",
        "3:4" to "3:4", "9:16" to "9:16", "16:9" to "16:9", "3:2" to "3:2",
    )

    val FILTRI = listOf(
        Filtro("", "Original"),
        Filtro("vivid", "Vivid", saturazione = 1.35f, contrasto = 1.1f),
        Filtro("warm", "Warm", r = 1.08f, g = 1.02f, b = 0.86f, saturazione = 1.05f),
        Filtro("cool", "Cool", r = 0.9f, g = 0.99f, b = 1.1f),
        Filtro("bw", "B&W", saturazione = 0f),
        Filtro("noir", "Noir", saturazione = 0f, contrasto = 1.45f, luce = -0.03f),
        Filtro("sepia", "Sepia", seppia = 1f),
        Filtro("fade", "Fade", contrasto = 0.85f, saturazione = 0.8f, sbiadito = 0.1f),
        Filtro("vintage", "Vintage", seppia = 0.35f, sbiadito = 0.08f, r = 1.05f, b = 0.92f, contrasto = 0.95f),
        Filtro("film", "Film", r = 1.04f, b = 0.94f, saturazione = 0.9f, contrasto = 1.08f, sbiadito = 0.04f),
        Filtro("drama", "Drama", contrasto = 1.35f, saturazione = 0.85f, luce = -0.03f),
        Filtro("pop", "Pop", saturazione = 1.6f, contrasto = 1.15f, luce = 0.02f),
    )

    fun filtro(id: String): Filtro? = FILTRI.firstOrNull { it.id == id && id.isNotEmpty() }

    // ---------------------------------------------------------------- geometria

    /** Il quadro: larghezza e altezza della foto dopo [giri] quarti di giro. */
    fun quadro(w: Int, h: Int, giri: Int): Pair<Int, Int> = if (giri % 2 == 0) w to h else h to w

    /** Quanto ingrandire la foto girata di [gradi] perche' copra ancora tutto il quadro W×H. */
    fun zoomRaddrizza(w: Float, h: Float, gradi: Float): Float {
        if (w <= 0f || h <= 0f) return 1f
        val a = Math.toRadians(abs(gradi).toDouble())
        val c = cos(a); val s = sin(a)
        return max(c + (h / w) * s, c + (w / h) * s).toFloat()
    }

    /** La forma (larghezza/altezza) di un rapporto; «free» o sconosciuto = null. */
    fun forma(rapporto: String, w: Int, h: Int): Float? = when (rapporto) {
        "free" -> null
        "orig" -> if (w > 0 && h > 0) w.toFloat() / h else null
        else -> {
            val p = rapporto.split(':')
            val a = p.getOrNull(0)?.toFloatOrNull(); val b = p.getOrNull(1)?.toFloatOrNull()
            if (a != null && b != null && a > 0 && b > 0) a / b else null
        }
    }

    /** Il ritaglio piu' grande con quella forma, al centro del quadro. Forma null = tutto. */
    fun ritaglioCentrato(w: Int, h: Int, forma: Float?): Ritaglio {
        if (forma == null || w <= 0 || h <= 0) return Ritaglio()
        var cw = w.toFloat(); var ch = cw / forma
        if (ch > h) { ch = h.toFloat(); cw = ch * forma }
        val l = (w - cw) / 2f / w; val t = (h - ch) / 2f / h
        return Ritaglio(l, t, l + cw / w, t + ch / h)
    }

    /**
     * Il ritaglio [r] dopo che il dito ha trascinato la [m] di ([dx],[dy]) in frazioni del quadro.
     * Resta dentro il quadro, mai sotto [RITAGLIO_MIN]; con una [forma] gli angoli la tengono
     * e i lati non si muovono (si muovono solo gli angoli, come in ogni editor).
     */
    fun trascina(r: Ritaglio, m: Maniglia, dx: Float, dy: Float, w: Int, h: Int, forma: Float?): Ritaglio {
        if (w <= 0 || h <= 0) return r
        val W = w.toFloat(); val H = h.toFloat()
        var l = r.l * W; var t = r.t * H; var rr = r.r * W; var b = r.b * H
        val mx = dx * W; val my = dy * H
        val min = RITAGLIO_MIN * min(W, H)
        when (m) {
            Maniglia.SPOSTA -> {
                val sx = mx.coerceIn(-l, W - rr); val sy = my.coerceIn(-t, H - b)
                l += sx; rr += sx; t += sy; b += sy
            }
            Maniglia.N, Maniglia.S, Maniglia.O, Maniglia.E -> {
                if (forma != null) return r
                when (m) {
                    Maniglia.N -> t = (t + my).coerceIn(0f, b - min)
                    Maniglia.S -> b = (b + my).coerceIn(t + min, H)
                    Maniglia.O -> l = (l + mx).coerceIn(0f, rr - min)
                    else -> rr = (rr + mx).coerceIn(l + min, W)
                }
            }
            else -> {
                val sinistra = m == Maniglia.NO || m == Maniglia.SO
                val sopra = m == Maniglia.NO || m == Maniglia.NE
                // l'angolo opposto resta fermo
                val ax = if (sinistra) rr else l
                val ay = if (sopra) b else t
                val spazioX = if (sinistra) ax else W - ax
                val spazioY = if (sopra) ay else H - ay
                var nw = (if (sinistra) ax - (l + mx) else (rr + mx) - ax)
                var nh = (if (sopra) ay - (t + my) else (b + my) - ay)
                if (forma == null) {
                    nw = nw.coerceIn(min(min, spazioX), spazioX)
                    nh = nh.coerceIn(min(min, spazioY), spazioY)
                } else {
                    // vince il dito che si e' mosso di piu', poi la forma decide l'altro lato
                    nw = max(nw, nh * forma)
                    val minW = max(min, min * forma)
                    nw = nw.coerceAtMost(min(spazioX, spazioY * forma)).coerceAtLeast(min(minW, min(spazioX, spazioY * forma)))
                    nh = nw / forma
                }
                if (sinistra) { l = ax - nw; rr = ax } else { l = ax; rr = ax + nw }
                if (sopra) { t = ay - nh; b = ay } else { t = ay; b = ay + nh }
            }
        }
        return Ritaglio(l / W, t / H, rr / W, b / H)
    }

    /** Un quarto di giro in senso orario: ritaglio e testi girano con la foto. */
    fun gira(m: Modifiche, w: Int, h: Int): Modifiche {
        val r = m.ritaglio
        val girato = Ritaglio(1f - r.b, r.l, 1f - r.t, r.r)
        val (qw, qh) = quadro(w, h, m.giri + 1)
        val f = forma(m.rapporto, qw, qh)
        // con una forma fissa il ritaglio girato non la rispetterebbe piu': si rifa' al centro
        val nuovo = if (f == null) girato else ritaglioCentrato(qw, qh, f)
        return m.copy(
            giri = (m.giri + 1) % 4,
            ritaglio = nuovo,
            testi = m.testi.map { it.copy(x = 1f - it.y, y = it.x) },
            tratti = Mano.gira(m.tratti),
        )
    }

    /** Specchio orizzontale di quello che si vede: anche raddrizza, ritaglio e testi si specchiano. */
    fun specchia(m: Modifiche): Modifiche {
        val r = m.ritaglio
        return m.copy(
            specchio = !m.specchio,
            giri = (4 - m.giri) % 4,
            raddrizza = -m.raddrizza,
            ritaglio = Ritaglio(1f - r.r, r.t, 1f - r.l, r.b),
            testi = m.testi.map { it.copy(x = 1f - it.x) },
            tratti = Mano.specchia(m.tratti),
        )
    }

    /** Sceglie una forma: il ritaglio diventa il piu' grande possibile con quella forma, al centro. */
    fun conRapporto(m: Modifiche, rapporto: String, w: Int, h: Int): Modifiche {
        val (qw, qh) = quadro(w, h, m.giri)
        val f = forma(rapporto, qw, qh)
        return m.copy(rapporto = rapporto, ritaglio = if (f == null) m.ritaglio else ritaglioCentrato(qw, qh, f))
    }

    /** Orientamento EXIF (1..8) → (giri orari, specchio prima dei giri). Sconosciuto = dritta. */
    fun exif(o: Int): Pair<Int, Boolean> = when (o) {
        2 -> 0 to true
        3 -> 2 to false
        4 -> 2 to true
        5 -> 3 to true
        6 -> 1 to false
        7 -> 1 to true
        8 -> 3 to false
        else -> 0 to false
    }

    /**
     * Quanto rimpicciolire (potenza di 2, come vuole il decodificatore) perche' la foto
     * resti sotto [maxPixel]. 1 = intera.
     */
    fun campione(w: Int, h: Int, maxPixel: Long): Int {
        var s = 1
        while (w.toLong() / s * (h.toLong() / s) > maxPixel) s *= 2
        return s
    }

    /** Il campione per un'anteprima: il lato lungo resta almeno [lato]. */
    fun campioneAnteprima(w: Int, h: Int, lato: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= lato) s *= 2
        return s
    }

    /** «151 Editor 2026-10-11 16.42.07.jpg» */
    fun nomeFile(anno: Int, mese: Int, giorno: Int, ora: Int, minuto: Int, secondo: Int, estensione: String): String =
        "151 Editor %04d-%02d-%02d %02d.%02d.%02d.%s".format(anno, mese, giorno, ora, minuto, secondo, estensione)

    // ---------------------------------------------------------------- colore

    /** Niente da colorare: luce neutra e nessun filtro (o filtro a forza 0). */
    fun coloreNeutro(m: Modifiche): Boolean = m.luce.neutra && (filtro(m.filtro) == null || m.forza <= 0)

    /**
     * Colora i pixel [px] (ARGB) da [da] per [quanti]: prima la luce, poi il filtro alla sua forza.
     * L'alfa resta com'e'. Stessa funzione per l'anteprima e per il file salvato.
     */
    fun colora(px: IntArray, m: Modifiche, da: Int = 0, quanti: Int = px.size - da) {
        if (coloreNeutro(m)) return
        val lu = m.luce
        val lum = lu.luminosita / 100f * 0.22f
        val con = 1f + lu.contrasto / 100f * 0.6f
        val sat = 1f + lu.saturazione / 100f
        val cal = lu.calore / 100f * 0.09f
        val omb = lu.ombre / 100f * 0.35f
        val alt = lu.luci / 100f * 0.35f
        val luceAccesa = !lu.neutra
        val f = filtro(m.filtro)
        val forza = if (f == null) 0f else (m.forza.coerceIn(0, 100) / 100f)
        for (i in da until da + quanti) {
            val p = px[i]
            var r = ((p shr 16) and 0xFF) / 255f
            var g = ((p shr 8) and 0xFF) / 255f
            var b = (p and 0xFF) / 255f
            if (luceAccesa) {
                r += lum; g += lum; b += lum
                r = (r - 0.5f) * con + 0.5f; g = (g - 0.5f) * con + 0.5f; b = (b - 0.5f) * con + 0.5f
                r += cal; g += cal * 0.15f; b -= cal
                var y = 0.299f * r + 0.587f * g + 0.114f * b
                r = y + (r - y) * sat; g = y + (g - y) * sat; b = y + (b - y) * sat
                if (omb != 0f || alt != 0f) {
                    y = (0.299f * r + 0.587f * g + 0.114f * b).coerceIn(0f, 1f)
                    val ws = (1f - y / 0.6f).coerceAtLeast(0f).let { it * it }
                    val wh = ((y - 0.4f) / 0.6f).coerceAtLeast(0f).let { it * it }
                    val d = omb * ws + alt * wh
                    r += d; g += d; b += d
                }
            }
            if (f != null && forza > 0f) {
                r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
                val r0 = r; val g0 = g; val b0 = b
                if (f.seppia > 0f) {
                    val sr = 0.393f * r + 0.769f * g + 0.189f * b
                    val sg = 0.349f * r + 0.686f * g + 0.168f * b
                    val sb = 0.272f * r + 0.534f * g + 0.131f * b
                    r += (sr - r) * f.seppia; g += (sg - g) * f.seppia; b += (sb - b) * f.seppia
                }
                r *= f.r; g *= f.g; b *= f.b
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                r = y + (r - y) * f.saturazione; g = y + (g - y) * f.saturazione; b = y + (b - y) * f.saturazione
                r = (r - 0.5f) * f.contrasto + 0.5f + f.luce
                g = (g - 0.5f) * f.contrasto + 0.5f + f.luce
                b = (b - 0.5f) * f.contrasto + 0.5f + f.luce
                if (f.sbiadito > 0f) {
                    r = f.sbiadito + r * (1f - f.sbiadito); g = f.sbiadito + g * (1f - f.sbiadito); b = f.sbiadito + b * (1f - f.sbiadito)
                }
                r = r0 + (r - r0) * forza; g = g0 + (g - g0) * forza; b = b0 + (b - b0) * forza
            }
            val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            px[i] = (p and 0xFF000000.toInt()) or (ri shl 16) or (gi shl 8) or bi
        }
    }

    // ---------------------------------------------------------------- stato su disco
    // Per quando Android chiude l'app mentre chi lo usa e' altrove: una riga per voce, campi a TAB.

    internal fun chiudi(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    internal fun apri(s: String): String {
        val o = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> o.append('\t'); 'n' -> o.append('\n'); else -> o.append(s[i + 1]) }
                i += 2
            } else { o.append(c); i++ }
        }
        return o.toString()
    }

    fun scrivi(m: Modifiche): String = buildString {
        val r = m.ritaglio; val l = m.luce
        append("151foto 1\n")
        append(listOf(m.giri, if (m.specchio) 1 else 0, m.raddrizza, r.l, r.t, r.r, r.b, chiudi(m.rapporto)).joinToString("\t")).append('\n')
        append(listOf(l.luminosita, l.contrasto, l.saturazione, l.calore, l.ombre, l.luci, chiudi(m.filtro), m.forza).joinToString("\t")).append('\n')
        for (t in m.testi) {
            // 10° e 11° campo (secondo colore, spessore): le versioni di prima leggono i primi 9 e li ignorano
            append(listOf("T", t.id, t.x, t.y, t.misura, t.colore, chiudi(t.font), t.stile, chiudi(t.testo), t.secondo, t.spessore)
                .joinToString("\t")).append('\n')
        }
        // una riga «D» per tratto: le versioni di prima la saltano (leggono solo le «T»)
        for (t in m.tratti) append("D\t").append(Mano.scrivi(t)).append('\n')
    }

    /** Una bozza foto su disco: prima riga «151fotobozza 1<TAB>nome<TAB>uri<TAB>png», poi le modifiche. */
    fun scriviBozza(b: BozzaFoto): String =
        "151fotobozza 1\t" + chiudi(b.nome) + "\t" + chiudi(b.uri) + "\t" + (if (b.png) 1 else 0) + "\n" + scrivi(b.m)

    /** Legge cio' che [scriviBozza] ha scritto; rotta = null. Modifiche illeggibili = la foto com'e'. */
    fun leggiBozza(s: String): BozzaFoto? {
        val a = s.indexOf('\n')
        if (a < 0) return null
        val p = s.substring(0, a).split('\t')
        if (p.size != 4 || p[0] != "151fotobozza 1" || p[2].isBlank()) return null
        return BozzaFoto(apri(p[1]), apri(p[2]), leggi(s.substring(a + 1)) ?: Modifiche(), p[3] == "1")
    }

    /** Legge cio' che [scrivi] ha scritto; qualunque cosa rotta = null (si riparte dalla foto com'e'). */
    fun leggi(s: String): Modifiche? = try {
        val righe = s.split('\n')
        if (righe.firstOrNull() != "151foto 1") null else {
            val a = righe[1].split('\t'); val b = righe[2].split('\t')
            val testi = righe.drop(3).filter { it.startsWith("T\t") }.map { r ->
                val p = r.split('\t')
                Testo(p[1].toLong(), apri(p[8]), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toInt(), apri(p[6]), p[7].toInt(),
                    secondo = p.getOrNull(9)?.trimEnd('\r')?.toIntOrNull() ?: 0,
                    spessore = Testo.spessoreGiusto(p.getOrNull(10)?.trimEnd('\r')?.toFloatOrNull() ?: Testo.SPESSORE_STD))
            }
            val tratti = righe.drop(3).filter { it.startsWith("D\t") }.mapNotNull { Mano.leggi(it.substring(2).trimEnd('\r')) }
            Modifiche(
                giri = a[0].toInt().mod(4), specchio = a[1] == "1", raddrizza = a[2].toFloat(),
                ritaglio = Ritaglio(a[3].toFloat(), a[4].toFloat(), a[5].toFloat(), a[6].toFloat()),
                rapporto = apri(a[7]),
                luce = Luce(b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt(), b[4].toInt(), b[5].toInt()),
                filtro = apri(b[6]), forza = b[7].toInt(), testi = testi, tratti = tratti,
            )
        }
    } catch (_: Exception) { null }
}
