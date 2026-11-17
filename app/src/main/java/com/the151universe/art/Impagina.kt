package com.the151universe.art

/**
 * Il PDF per la stampa: segni di taglio attorno alla pagina, e più pagine piccole (carte)
 * su un foglio A4 coi segni di taglio. Tutto in punti di stampa, origine in alto a sinistra. Logica pura.
 */
data class Stampa(
    /** i segni di taglio */
    val segni: Boolean = false,
    /** le pagine (carte) messe su fogli A4 */
    val foglio: Boolean = false,
    /** fronte e retro: le pagine vanno a coppie (0 fronte, 1 retro, 2 fronte…); sui fogli il retro sta sul foglio dopo, a specchio */
    val retro: Boolean = false,
)

/** Una riga sottile da disegnare, in punti. */
data class Linea(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/**
 * Un foglio con le carte: [w]×[h] punti, [posti] = l'angolo in alto a sinistra del TAGLIO di ogni carta;
 * [cw]×[ch] la carta tagliata, [ab] la sua abbondanza (ogni carta ha la sua attorno, non si toccano).
 */
data class Foglio(val w: Float, val h: Float, val posti: List<Pair<Float, Float>>, val cw: Float, val ch: Float, val ab: Float) {
    val perFoglio get() = posti.size
}

object Impagina {
    /** Fra il disegno (abbondanza) e il segno: 1 mm; il segno è lungo 5 mm. */
    val STACCO = mm(1f)
    val SEGNO = mm(5f)
    /** Il bordo bianco del foglio A4 (le stampanti di casa non arrivano al bordo); i segni ci stanno dentro. */
    val BORDO_FOGLIO = mm(10f)

    /** Quanto spazio serve attorno all'abbondanza per i segni di taglio, per lato, in punti. */
    fun fascia(segni: Boolean) = if (segni) STACCO + SEGNO + mm(1f) else 0f

    /**
     * I segni di taglio di una pagina tagliata a ([l],[t])-([r],[b]) con abbondanza [ab]: due per angolo, sulla
     * riga del taglio, fuori dall'abbondanza.
     */
    fun segniPagina(l: Float, t: Float, r: Float, b: Float, ab: Float): List<Linea> {
        val d = ab + STACCO
        return listOf(
            Linea(l, t - d, l, t - d - SEGNO), Linea(l - d, t, l - d - SEGNO, t),
            Linea(r, t - d, r, t - d - SEGNO), Linea(r + d, t, r + d + SEGNO, t),
            Linea(l, b + d, l, b + d + SEGNO), Linea(l - d, b, l - d - SEGNO, b),
            Linea(r, b + d, r, b + d + SEGNO), Linea(r + d, b, r + d + SEGNO, b),
        )
    }

    /**
     * Le carte [cw]×[ch] (abbondanza [ab]) su un A4: dritto o coricato, vince quello che ne tiene di più (pari:
     * dritto). Ogni carta col suo giro d'abbondanza, la griglia al centro. Null = non ne sta neanche una.
     */
    fun foglio(cw: Float, ch: Float, ab: Float): Foglio? {
        fun prova(w: Float, h: Float): Foglio {
            val cellaW = cw + 2 * ab; val cellaH = ch + 2 * ab
            val utileW = w - 2 * BORDO_FOGLIO; val utileH = h - 2 * BORDO_FOGLIO
            val col = (utileW / cellaW).toInt().coerceAtLeast(0); val rig = (utileH / cellaH).toInt().coerceAtLeast(0)
            val x0 = (w - col * cellaW) / 2f + ab; val y0 = (h - rig * cellaH) / 2f + ab
            val posti = (0 until rig).flatMap { r -> (0 until col).map { c -> (x0 + c * cellaW) to (y0 + r * cellaH) } }
            return Foglio(w, h, posti, cw, ch, ab)
        }
        val dritto = prova(595f, 842f); val coricato = prova(842f, 595f)
        val f = if (coricato.perFoglio > dritto.perFoglio) coricato else dritto
        return f.takeIf { it.perFoglio > 0 }
    }

    /**
     * I segni di taglio di un foglio: ogni riga di taglio (sinistra e destra di ogni colonna, sopra e sotto di
     * ogni riga) segnata fuori dalla griglia, nel bordo bianco, mai sopra una carta.
     */
    fun segniFoglio(f: Foglio): List<Linea> {
        if (f.posti.isEmpty()) return emptyList()
        val xs = f.posti.flatMap { listOf(it.first, it.first + f.cw) }.distinct()
        val ys = f.posti.flatMap { listOf(it.second, it.second + f.ch) }.distinct()
        val sopra = f.posti.minOf { it.second } - f.ab; val sotto = f.posti.maxOf { it.second } + f.ch + f.ab
        val sx = f.posti.minOf { it.first } - f.ab; val dx = f.posti.maxOf { it.first } + f.cw + f.ab
        val lungo = minOf(SEGNO, BORDO_FOGLIO - STACCO - mm(1f))
        return xs.flatMap { x -> listOf(Linea(x, sopra - STACCO, x, sopra - STACCO - lungo), Linea(x, sotto + STACCO, x, sotto + STACCO + lungo)) } +
            ys.flatMap { y -> listOf(Linea(sx - STACCO, y, sx - STACCO - lungo, y), Linea(dx + STACCO, y, dx + STACCO + lungo, y)) }
    }

    /** Quanti fogli A4 servono per [pagine] pagine, [per] per foglio; col [retro] ogni foglio di fronti ha il suo foglio di retri. */
    fun fogli(pagine: Int, per: Int, retro: Boolean): Int {
        if (per <= 0 || pagine <= 0) return 0
        return if (retro) 2 * ((pagine + 1) / 2 + per - 1).div(per) else (pagine + per - 1) / per
    }

    /**
     * Cosa va sul foglio [n]: per ogni posto la pagina e l'angolo del suo taglio. Col [retro] i fogli pari hanno i
     * fronti, i dispari i retri delle stesse carte A SPECCHIO (destra ↔ sinistra), così stampando sui due lati
     * («gira sul lato corto» col foglio coricato, «sul lato lungo» col foglio dritto) ogni retro cade dietro il suo fronte.
     */
    fun sulFoglio(f: Foglio, n: Int, pagine: Int, retro: Boolean): List<Pair<Int, Pair<Float, Float>>> {
        if (!retro) return f.posti.mapIndexedNotNull { i, posto -> (n * f.perFoglio + i).takeIf { it < pagine }?.let { it to posto } }
        val lato = n % 2
        return f.posti.mapIndexedNotNull { i, posto ->
            val p = 2 * ((n / 2) * f.perFoglio + i) + lato
            if (p >= pagine) null else p to (if (lato == 0) posto else (f.w - posto.first - f.cw) to posto.second)
        }
    }

    /** Come si gira il foglio stampando sui due lati: coricato → lato corto, dritto → lato lungo. */
    fun giro(f: Foglio) = if (f.w > f.h) "flip on the short edge" else "flip on the long edge"
}
