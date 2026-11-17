package com.the151universe.art

/**
 * Il testo misto, senza Android: dentro UN testo alcuni pezzi in grassetto, corsivo o
 * sottolineati (il quaderno: «verb**ing**», «*article + noun:*»), e dal 05/11 anche con un colore,
 * un font o una misura loro («jumped» rossa e in grassetto in una riga piccola, chi lo usa).
 * Un [Segno] dice da dove a dove (a escluso) e cosa cambia; colore 0, font "" e misura 0 =
 * «come il testo». Si tiene sempre la forma più corta: niente segni vuoti, niente doppioni,
 * i vicini con lo stesso aspetto uniti (così Annulla/Rifai confronta il contenuto).
 */
data class Segno(val da: Int, val a: Int, val stile: Int, val colore: Int = 0, val font: String = "", val misura: Float = 0f)

/** L'aspetto di una lettera: [Segno] senza da/a. Tutto ai valori di base = lettera normale. */
data class Aspetto(val stile: Int = 0, val colore: Int = 0, val font: String = "", val misura: Float = 0f) {
    val normale get() = this == NORMALE

    companion object { val NORMALE = Aspetto() }
}

object Ricco {
    const val GRASSETTO = 1
    const val CORSIVO = 2
    const val SOTTOLINEATO = 4
    const val TUTTI = GRASSETTO or CORSIVO or SOTTOLINEATO

    /** L'aspetto di ogni lettera. */
    fun aspetti(lunghezza: Int, segni: List<Segno>): Array<Aspetto> {
        val s = Array(lunghezza) { Aspetto.NORMALE }
        for (g in segni) for (i in g.da.coerceAtLeast(0) until g.a.coerceAtMost(lunghezza)) {
            val o = s[i]
            s[i] = Aspetto(
                o.stile or (g.stile and TUTTI),
                if (g.colore != 0) g.colore else o.colore,
                if (g.font.isNotEmpty()) g.font else o.font,
                if (g.misura > 0f) g.misura else o.misura,
            )
        }
        return s
    }

    /** Il contrario di [aspetti]: i pezzi di fila con lo stesso aspetto. */
    fun daAspetti(s: Array<Aspetto>): List<Segno> {
        val out = ArrayList<Segno>()
        var i = 0
        while (i < s.size) {
            if (s[i].normale) { i++; continue }
            var j = i
            while (j < s.size && s[j] == s[i]) j++
            out += Segno(i, j, s[i].stile, s[i].colore, s[i].font, s[i].misura); i = j
        }
        return out
    }

    /** Solo grassetto/corsivo/sottolineato di ogni lettera (0 = normale): per la casella delle celle. */
    fun perLettera(lunghezza: Int, segni: List<Segno>): IntArray {
        val a = aspetti(lunghezza, segni)
        return IntArray(lunghezza) { a[it].stile }
    }

    /** Il contrario di [perLettera] (solo B/I/U). */
    fun daLettere(s: IntArray): List<Segno> = daAspetti(Array(s.size) { Aspetto(s[it] and TUTTI) })

    fun normalizza(lunghezza: Int, segni: List<Segno>): List<Segno> = daAspetti(aspetti(lunghezza, segni))

    /** Tutto il pezzo [da]..[a] ha già lo stile [bit]? */
    fun tutto(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, bit: Int): Boolean {
        if (a <= da) return false
        val s = aspetti(lunghezza, segni)
        return (da.coerceAtLeast(0) until a.coerceAtMost(lunghezza)).all { s[it].stile and bit != 0 }
    }

    /** Ogni lettera da [da] ad [a] passa per [f]; il resto non si tocca. */
    fun metti(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, f: (Aspetto) -> Aspetto): List<Segno> {
        val s = aspetti(lunghezza, segni)
        for (i in da.coerceAtLeast(0) until a.coerceAtMost(lunghezza)) s[i] = f(s[i])
        return daAspetti(s)
    }

    /** Il pulsante B / I / U sulla scelta: se tutta la scelta lo ha già, si toglie; se no si mette. */
    fun cambia(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, bit: Int): List<Segno> {
        val togli = tutto(lunghezza, segni, da, a, bit)
        return metti(lunghezza, segni, da, a) { it.copy(stile = if (togli) it.stile and bit.inv() else it.stile or bit) }
    }

    /** Colore (0 = quello del testo), font ("" = quello del testo), misura (0 = quella del testo) sul pezzo. */
    fun colore(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, c: Int) = metti(lunghezza, segni, da, a) { it.copy(colore = c) }
    fun font(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, f: String) = metti(lunghezza, segni, da, a) { it.copy(font = f) }
    fun misura(lunghezza: Int, segni: List<Segno>, da: Int, a: Int, m: Float) = metti(lunghezza, segni, da, a) { it.copy(misura = m) }

    /** «Clear style»: il pezzo torna come il testo. */
    fun pulisci(lunghezza: Int, segni: List<Segno>, da: Int, a: Int) = metti(lunghezza, segni, da, a) { Aspetto.NORMALE }

    /** Tutto il testo prende [f] dell'oggetto: quel campo sparisce dai pezzi (es. colore della riga intera). */
    fun senza(lunghezza: Int, segni: List<Segno>, f: (Aspetto) -> Aspetto) = metti(lunghezza, segni, 0, lunghezza, f)

    /**
     * Il testo cambia da [vecchio] a [nuovo] (la tastiera scrive, cancella, corregge): i segni seguono.
     * Il pezzo cambiato si trova dal prefisso e dal suffisso comuni; le lettere nuove prendono
     * l'aspetto di quella prima (all'inizio, di quella dopo): si scrive dentro «jumped» e resta rosso.
     */
    fun dopoScrittura(vecchio: String, nuovo: String, segni: List<Segno>): List<Segno> {
        if (vecchio == nuovo) return normalizza(nuovo.length, segni)
        val s = aspetti(vecchio.length, segni)
        var p = 0
        val max = minOf(vecchio.length, nuovo.length)
        while (p < max && vecchio[p] == nuovo[p]) p++
        var q = 0
        while (q < max - p && vecchio[vecchio.length - 1 - q] == nuovo[nuovo.length - 1 - q]) q++
        val via = vecchio.length - p - q
        val nuove = nuovo.length - p - q
        // una parola sostituita (correzione della tastiera) resta com'era; una lettera aggiunta è come quella prima
        val eredita = when {
            via > 0 -> s[p]
            p > 0 -> s[p - 1]
            p < vecchio.length -> s[p]
            else -> Aspetto.NORMALE
        }
        val out = ArrayList<Aspetto>(nuovo.length)
        for (i in 0 until p) out += s[i]
        repeat(nuove) { out += eredita }
        for (i in vecchio.length - q until vecchio.length) out += s[i]
        return daAspetti(out.toTypedArray())
    }

    /** La parola sotto [i] (lettere, cifre, apostrofi): «jumped.» → «jumped». Vuota se [i] è su uno spazio. */
    fun parola(t: String, i: Int): IntRange {
        fun dentro(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'
        if (t.isEmpty()) return 0 until 0
        var k = i.coerceIn(0, t.length - 1)
        if (!dentro(t[k]) && k > 0 && dentro(t[k - 1])) k--
        if (!dentro(t[k])) return k until k + 1
        var a = k; var b = k + 1
        while (a > 0 && dentro(t[a - 1])) a--
        while (b < t.length && dentro(t[b])) b++
        return a until b
    }

    // «da,a,stile;…» — con colore, font o misura: «da,a,stile,colore,font,misura».
    // Una riga rotta si salta, mai un crash. Gli id dei font sono lettere: niente virgole.
    fun scrivi(segni: List<Segno>): String = segni.joinToString(";") {
        if (it.colore == 0 && it.font.isEmpty() && it.misura == 0f) "${it.da},${it.a},${it.stile}"
        else "${it.da},${it.a},${it.stile},${it.colore},${it.font.filter { c -> c.isLetterOrDigit() }},${it.misura}"
    }

    fun leggi(t: String, lunghezza: Int): List<Segno> {
        if (t.isBlank()) return emptyList()
        val l = t.split(';').mapNotNull { p ->
            val q = p.split(',')
            if (q.size != 3 && q.size != 6) return@mapNotNull null
            val da = q[0].toIntOrNull(); val a = q[1].toIntOrNull(); val s = q[2].toIntOrNull()
            if (da == null || a == null || s == null) return@mapNotNull null
            if (q.size == 3) return@mapNotNull Segno(da, a, s)
            val c = q[3].toIntOrNull() ?: return@mapNotNull null
            val m = q[5].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return@mapNotNull null
            Segno(da, a, s, c, q[4], m)
        }
        return normalizza(lunghezza, l)
    }
}
