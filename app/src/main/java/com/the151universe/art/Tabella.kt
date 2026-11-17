package com.the151universe.art

import kotlin.math.max

/**
 * Le tabelle del «Design», senza Android (come la pagina 71 del quaderno: righe intere in cima,
 * poi 3 colonne con le celle grigie a scacchi). Una tabella è un [Elemento] di tipo TABELLA:
 * [Elemento.colonne] e [Elemento.righe] sono le larghezze/altezze in frazioni (somma 1),
 * [Elemento.celle] sono righe × colonne, una dopo l'altra. Una cella con [Cella.unisci] = n copre
 * anche le n-1 alla sua destra: quelle restano nella lista (tornano se si divide) ma non si vedono.
 * Testo, font, misura e colore del testo sono della tabella; linee = [Elemento.contorno] e
 * [Elemento.spessore].
 */
data class Cella(
    val testo: String = "",
    val segni: List<Segno> = emptyList(),
    /** 0 = senza fondo */
    val fondo: Int = 0,
    val unisci: Int = 1,
    val allinea: Int = Elemento.CENTRO,
    /** lo stile di testo della cella (come i testi: 1..stili, 0 = nessuno; vedi [Grafiche.conStileCella]) */
    val classe: Int = 0,
)

object Tabelle {
    /** La riga o colonna più stretta, in punti del foglio. */
    const val MINIMO = 12f

    fun nuova(id: Long, righe: Int, colonne: Int, w: Float, h: Float, misura: Float, colore: Int, linee: Int, spessore: Float) =
        Elemento(id, Elemento.TABELLA, 0f, 0f, w, h, misura = misura, colore = colore, contorno = linee, spessore = spessore,
            font = "liberation", colonne = List(colonne) { 1f / colonne }, righe = List(righe) { 1f / righe },
            celle = List(righe * colonne) { Cella() })

    fun nr(e: Elemento) = e.righe.size
    fun nc(e: Elemento) = e.colonne.size

    fun cella(e: Elemento, r: Int, c: Int): Cella? = e.celle.getOrNull(r * nc(e) + c)

    fun conCella(e: Elemento, r: Int, c: Int, n: Cella): Elemento {
        val i = r * nc(e) + c
        if (i !in e.celle.indices) return e
        return e.copy(celle = e.celle.toMutableList().apply { this[i] = n })
    }

    /** Dove cominciano le colonne (e dove finisce l'ultima), in punti dentro la tabella. */
    fun xs(e: Elemento): FloatArray = bordi(e.colonne, e.w)
    fun ys(e: Elemento): FloatArray = bordi(e.righe, e.h)

    private fun bordi(f: List<Float>, lato: Float): FloatArray {
        val s = f.sum().takeIf { it > 0f } ?: 1f
        val out = FloatArray(f.size + 1)
        for (i in f.indices) out[i + 1] = out[i] + f[i] / s * lato
        out[f.size] = lato
        return out
    }

    /** La cella che si vede in (r,c): se è coperta da una unita a sinistra, quella. */
    fun capo(e: Elemento, r: Int, c: Int): Int {
        var p = 0
        while (p < nc(e)) {
            val u = (cella(e, r, p)?.unisci ?: 1).coerceIn(1, nc(e) - p)
            if (c < p + u) return p
            p += u
        }
        return c
    }

    /** È nascosta sotto una cella unita alla sua sinistra? */
    fun coperta(e: Elemento, r: Int, c: Int): Boolean = capo(e, r, c) != c

    /** Quante colonne copre davvero la cella (r,c) (mai oltre il bordo). */
    fun larga(e: Elemento, r: Int, c: Int): Int = (cella(e, r, c)?.unisci ?: 1).coerceIn(1, nc(e) - c)

    /** La cella (riga, colonna) che si vede sotto il punto lx,ly (dentro la tabella, prima del giro). */
    fun dove(e: Elemento, lx: Float, ly: Float): Pair<Int, Int>? {
        if (lx < 0f || ly < 0f || lx > e.w || ly > e.h) return null
        val x = xs(e); val y = ys(e)
        val r = (0 until nr(e)).firstOrNull { ly <= y[it + 1] } ?: (nr(e) - 1)
        val c = (0 until nc(e)).firstOrNull { lx <= x[it + 1] } ?: (nc(e) - 1)
        return r to capo(e, r, c)
    }

    /** Unisce (r,c) con la cella che si vede alla sua destra. */
    fun unisci(e: Elemento, r: Int, c: Int): Elemento {
        val u = larga(e, r, c)
        if (c + u >= nc(e)) return e
        val destra = larga(e, r, c + u)
        return conCella(e, r, c, cella(e, r, c)!!.copy(unisci = u + destra))
    }

    fun dividi(e: Elemento, r: Int, c: Int): Elemento {
        val k = cella(e, r, c) ?: return e
        return if (k.unisci <= 1) e else conCella(e, r, c, k.copy(unisci = 1))
    }

    /** Una riga nuova dopo [dopo] (-1 = in cima), alta come quella, la tabella cresce di tanto. */
    fun aggiungiRiga(e: Elemento, dopo: Int): Elemento {
        val c = nc(e); val p = (dopo + 1).coerceIn(0, nr(e))
        val y = ys(e)
        val modello = dopo.coerceIn(0, nr(e) - 1)
        val alta = y[modello + 1] - y[modello]
        val altezze = (0 until nr(e)).map { y[it + 1] - y[it] }.toMutableList().apply { add(p, alta) }
        val h = e.h + alta
        val celle = e.celle.toMutableList()
        celle.addAll(p * c, List(c) { k -> Cella(fondo = cella(e, modello, k)?.fondo ?: 0) })
        return e.copy(h = h, righe = altezze.map { it / h }, celle = celle)
    }

    fun togliRiga(e: Elemento, r: Int): Elemento {
        if (nr(e) <= 1 || r !in 0 until nr(e)) return e
        val y = ys(e); val c = nc(e)
        val altezze = (0 until nr(e)).map { y[it + 1] - y[it] }.toMutableList().apply { removeAt(r) }
        val h = max(Elemento.MIN, e.h - (y[r + 1] - y[r]))
        val celle = e.celle.toMutableList().apply { subList(r * c, r * c + c).clear() }
        val s = altezze.sum()
        return e.copy(h = h, righe = altezze.map { it / s }, celle = celle)
    }

    /** Una colonna nuova dopo [dopo]: la tabella resta larga uguale, la colonna si divide in due. */
    fun aggiungiColonna(e: Elemento, dopo: Int): Elemento {
        val c = nc(e); val q = dopo.coerceIn(0, c - 1)
        val fr = e.colonne.toMutableList()
        val meta = fr[q] / 2f
        fr[q] = meta; fr.add(q + 1, meta)
        val celle = ArrayList<Cella>()
        for (r in 0 until nr(e)) for (k in 0 until c) {
            val x = cella(e, r, k)!!
            // una cella che si vede e attraversa la colonna q si allarga: copre anche la nuova
            val allarga = !coperta(e, r, k) && k <= q && k + larga(e, r, k) - 1 >= q && larga(e, r, k) > 1
            celle += if (allarga) x.copy(unisci = larga(e, r, k) + 1) else x
            if (k == q) celle += Cella(fondo = x.fondo)
        }
        return e.copy(colonne = fr, celle = celle)
    }

    fun togliColonna(e: Elemento, q: Int): Elemento {
        val c = nc(e)
        if (c <= 1 || q !in 0 until c) return e
        val fr = e.colonne.toMutableList().apply { removeAt(q) }
        val s = fr.sum()
        val celle = ArrayList<Cella>()
        for (r in 0 until nr(e)) for (k in 0 until c) {
            if (k == q) continue
            val x = cella(e, r, k)!!
            val attraversa = !coperta(e, r, k) && k < q && k + larga(e, r, k) - 1 >= q
            celle += if (attraversa) x.copy(unisci = (x.unisci - 1).coerceAtLeast(1)) else x
        }
        return e.copy(colonne = fr.map { it / s }, celle = celle)
    }

    /** Il bordo fra la colonna [i] e la [i]+1 va a [x] (punti dentro la tabella); le altre restano. */
    fun spostaColonna(e: Elemento, i: Int, x: Float): Elemento {
        if (i !in 0 until nc(e) - 1) return e
        val b = xs(e)
        val nx = x.coerceIn(b[i] + MINIMO, b[i + 2] - MINIMO)
        val larghe = (0 until nc(e)).map { b[it + 1] - b[it] }.toMutableList()
        larghe[i] = nx - b[i]; larghe[i + 1] = b[i + 2] - nx
        return e.copy(colonne = larghe.map { it / e.w })
    }

    /** Il bordo sotto la riga [i] va a [y]: cambia solo l'altezza di quella riga, la tabella cresce o cala. */
    fun spostaRiga(e: Elemento, i: Int, y: Float): Elemento {
        if (i !in 0 until nr(e)) return e
        val b = ys(e)
        val alte = (0 until nr(e)).map { b[it + 1] - b[it] }.toMutableList()
        alte[i] = (y - b[i]).coerceAtLeast(MINIMO)
        val h = alte.sum()
        return e.copy(h = h, righe = alte.map { it / h })
    }

    // ---------------------------------------------------------------- salvataggio
    // Un campo solo nella riga dell'oggetto: colonne RS righe RS cella RS cella …, ogni cella
    // testo US segni US fondo US unisci US allinea [US classe, solo se c'è]. RS/US non possono stare in un testo (si tolgono).

    private const val RS = '\u001E'
    private const val US = '\u001F'

    fun pulito(t: String) = t.replace(RS.toString(), "").replace(US.toString(), "")

    fun scrivi(e: Elemento): String = buildString {
        append(e.colonne.joinToString(",")); append(RS); append(e.righe.joinToString(","))
        for (k in e.celle) {
            append(RS)
            append((listOf(pulito(k.testo), Ricco.scrivi(k.segni), k.fondo, k.unisci, k.allinea) + (if (k.classe != 0) listOf(k.classe) else emptyList())).joinToString(US.toString()))
        }
    }

    /** null = rotta (la riga si salta). */
    fun leggi(s: String): Triple<List<Float>, List<Float>, List<Cella>>? {
        val p = s.split(RS)
        if (p.size < 3) return null
        val col = p[0].split(',').map { it.toFloatOrNull() ?: return null }
        val rig = p[1].split(',').map { it.toFloatOrNull() ?: return null }
        if (col.isEmpty() || rig.isEmpty() || col.any { !it.isFinite() || it <= 0f } || rig.any { !it.isFinite() || it <= 0f }) return null
        val celle = p.drop(2).map { c ->
            val q = c.split(US)
            if (q.size !in 5..6) return null
            Cella(q[0], Ricco.leggi(q[1], q[0].length), q[2].toIntOrNull() ?: return null,
                (q[3].toIntOrNull() ?: return null).coerceIn(1, col.size), (q[4].toIntOrNull() ?: return null).coerceIn(0, 2),
                q.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
        if (celle.size != col.size * rig.size) return null
        return Triple(col, rig, celle)
    }
}
