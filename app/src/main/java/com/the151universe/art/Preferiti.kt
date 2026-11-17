package com.the151universe.art

import android.content.Context

/**
 * I font e i colori che chi lo usa ha FISSATO: vengono per primi in ogni riga di ogni editor (foto,
 * video, design, penna) e restano da un progetto all'altro — «un mega editor solo».
 * I colori aggiunti col «+» sono fissati da subito. Tieni premuto = fissa / togli.
 * [versione] cambia a ogni modifica: le righe già costruite sanno che devono rifarsi.
 */
object Preferiti {
    private const val FILE = "preferiti"
    var versione = 0
        private set

    private fun pref(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun font(ctx: Context): List<String> =
        pref(ctx).getString("font", "")!!.split(',').filter { id -> id.isNotBlank() && Caratteri.TUTTI.any { it.first == id } }

    fun colori(ctx: Context): List<Int> =
        pref(ctx).getString("colori", "")!!.split(',').mapNotNull { it.toLongOrNull()?.toInt() }

    /** Fissa o toglie un font; ritorna true se adesso è fissato. */
    fun cambiaFont(ctx: Context, id: String): Boolean {
        val l = font(ctx).toMutableList()
        val si = if (id in l) { l.remove(id); false } else { l.add(0, id); true }
        pref(ctx).edit().putString("font", l.joinToString(",")).apply(); versione++
        return si
    }

    /** Fissa o toglie un colore; ritorna true se adesso è fissato. */
    fun cambiaColore(ctx: Context, c: Int): Boolean {
        val l = colori(ctx).toMutableList()
        val si = if (c in l) { l.remove(c); false } else { l.add(0, c); true }
        salvaColori(ctx, l)
        return si
    }

    /** Un colore nuovo dal «+»: entra fissato, per primo. */
    fun aggiungiColore(ctx: Context, c: Int) {
        val l = colori(ctx).toMutableList()
        l.remove(c); l.add(0, c)
        salvaColori(ctx, l.take(24))
    }

    private fun salvaColori(ctx: Context, l: List<Int>) {
        pref(ctx).edit().putString("colori", l.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() }).apply(); versione++
    }

    /** I font in ordine: prima i fissati (nell'ordine in cui sono stati fissati), poi gli altri. */
    fun ordinaFont(ctx: Context): List<Triple<String, String, String?>> {
        val f = font(ctx)
        return f.mapNotNull { id -> Caratteri.TUTTI.firstOrNull { it.first == id } } + Caratteri.TUTTI.filter { it.first !in f }
    }

    /** I colori in ordine: prima i fissati, poi quelli di [base] non fissati. */
    fun ordinaColori(ctx: Context, base: IntArray): List<Int> {
        val f = colori(ctx)
        return f + base.filter { it !in f }
    }
}
