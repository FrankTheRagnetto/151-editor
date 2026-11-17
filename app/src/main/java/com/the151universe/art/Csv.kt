package com.the151universe.art

/**
 * Un file CSV (quello che si apre in LibreOffice): righe di campi separati da virgole, «"» attorno ai campi con
 * virgole, a capo o virgolette. Ogni riga ricorda il suo testo di partenza: riscrivendo il file, le righe non
 * toccate restano byte per byte come erano (un file corretto sul telefono cambia solo dove è stato corretto).
 * Logica pura.
 */
object Csv {
    /** Una riga: i campi letti e il testo esatto da cui vengono (a capo escluso). */
    class Riga(val campi: List<String>, val grezza: String)

    /** Il file letto: le righe (anche la prima, l'intestazione), il BOM e l'a capo che usava. */
    class File(val righe: List<Riga>, val bom: Boolean, val acapo: String) {
        val testa get() = righe.firstOrNull()?.campi?.map { it.trim() } ?: emptyList()
        /** La colonna [nomi] (il primo nome che c'è, senza badare a maiuscole); -1 = non c'è. */
        fun colonna(vararg nomi: String): Int {
            val t = testa.map { it.lowercase() }
            for (n in nomi) { val i = t.indexOf(n.lowercase()); if (i >= 0) return i }
            return -1
        }
    }

    fun leggi(testo: String): File {
        val bom = testo.startsWith("﻿")
        val s = if (bom) testo.substring(1) else testo
        val acapo = if (s.contains("\r\n")) "\r\n" else "\n"
        val righe = ArrayList<Riga>()
        var i = 0
        while (i < s.length) {
            val inizio = i
            val campi = ArrayList<String>()
            val campo = StringBuilder()
            var virgolette = false
            var fine = false
            while (i < s.length && !fine) {
                val c = s[i]
                if (virgolette) {
                    if (c == '"') {
                        if (i + 1 < s.length && s[i + 1] == '"') { campo.append('"'); i += 2; continue }
                        virgolette = false
                    } else campo.append(c)
                    i++
                } else when (c) {
                    '"' -> { virgolette = true; i++ }
                    ',' -> { campi += campo.toString(); campo.setLength(0); i++ }
                    '\r' -> { i++ }
                    '\n' -> { fine = true; i++ }
                    else -> { campo.append(c); i++ }
                }
            }
            campi += campo.toString()
            val grezza = s.substring(inizio, i).removeSuffix("\n").removeSuffix("\r")
            if (grezza.isNotEmpty() || i < s.length) righe += Riga(campi, grezza)
        }
        return File(righe, bom, acapo)
    }

    /** Un campo scritto: fra virgolette solo se serve. */
    fun campo(x: String): String =
        if (x.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + x.replace("\"", "\"\"") + "\"" else x

    /** Il file di nuovo: le righe con [nuove] (indice → campi) riscritte, le altre come erano. */
    fun scrivi(f: File, nuove: Map<Int, List<String>>): String {
        val b = StringBuilder()
        if (f.bom) b.append('﻿')
        f.righe.forEachIndexed { i, r ->
            b.append(nuove[i]?.joinToString(",") { campo(it) } ?: r.grezza).append(f.acapo)
        }
        return b.toString()
    }
}
