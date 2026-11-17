package com.the151universe.art

import java.util.Locale

/**
 * Il PDF di Android ([android.graphics.pdf.PdfDocument]) conosce solo pagine a punti INTERI e
 * nessuna scatola di stampa. La tipografia vuole la misura esatta (A5 + 3 mm = 436,535 × 612,283)
 * con TrimBox (dove si taglia) e BleedBox (fin dove arriva il disegno). Qui, senza Android:
 * ogni «/MediaBox [0 0 w h]» diventa la misura vera con le due scatole, e la tabella degli
 * indirizzi (xref) si riscrive, perché le righe più lunghe spostano tutti gli oggetti.
 *
 * Chi disegna sposta il contenuto in giù di (h intero − h vero): la pagina vera parte da 0,0 in
 * basso (vedi [SalvaGrafica.pdf]). Una forma di PDF che non si conosce = errore, mai un file storto.
 */
object PdfScatole {
    private fun n(v: Float) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

    /** I tratti di dati compressi (fra «stream» e «endstream»): lì dentro non si cerca niente. */
    private fun flussi(s: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        val inizio = Regex(">>\\s*stream\\r?\\n")
        var da = 0
        while (true) {
            val m = inizio.find(s, da) ?: break
            val a = m.range.last + 1
            val b = s.indexOf("endstream", a)
            if (b < 0) throw IllegalStateException("PDF: stream without end")
            out += a until b
            da = b + 9
        }
        return out
    }

    private fun fuori(pos: Int, f: List<IntRange>) = f.none { pos in it }

    /** [fascia] = lo spazio dei segni di taglio fuori dall'abbondanza, per lato (0 = niente segni). */
    fun correggi(pdf: ByteArray, mediaW: Float, mediaH: Float, abbondanza: Float, fascia: Float = 0f): ByteArray {
        val s = String(pdf, Charsets.ISO_8859_1)
        if (!s.startsWith("%PDF-")) throw IllegalStateException("PDF: not a PDF")
        val f = flussi(s)
        if (Regex("/Type\\s*/(XRef|ObjStm)").findAll(s).any { fuori(it.range.first, f) })
            throw IllegalStateException("PDF: unknown layout (compressed index)")

        val scatola = Regex("/MediaBox\\s*\\[\\s*0\\s+0\\s+[0-9.]+\\s+[0-9.]+\\s*\\]")
        val w = n(mediaW); val h = n(mediaH)
        val a = fascia + abbondanza
        val t = "${n(a)} ${n(a)} ${n(mediaW - a)} ${n(mediaH - a)}"
        val bl = "${n(fascia)} ${n(fascia)} ${n(mediaW - fascia)} ${n(mediaH - fascia)}"
        val nuova = if (fascia == 0f) "/MediaBox [0 0 $w $h] /BleedBox [0 0 $w $h] /TrimBox [$t]" else "/MediaBox [0 0 $w $h] /BleedBox [$bl] /TrimBox [$t]"
        val b = StringBuilder(s.length + 4096)
        var da = 0; var quante = 0
        for (m in scatola.findAll(s)) {
            if (!fuori(m.range.first, f)) continue
            b.append(s, da, m.range.first).append(nuova)
            da = m.range.last + 1; quante++
        }
        if (quante == 0) throw IllegalStateException("PDF: no page size found")
        b.append(s, da, s.length)
        return xref(b.toString()).toByteArray(Charsets.ISO_8859_1)
    }

    /** Solo l'indice rifatto (le carte su A4: [PdfNero.soloNero] cambia la lunghezza dei disegni, 15/11). */
    fun indice(pdf: ByteArray): ByteArray = xref(String(pdf, Charsets.ISO_8859_1)).toByteArray(Charsets.ISO_8859_1)

    /** La tabella degli indirizzi rifatta da capo: ogni «N 0 obj» al suo posto vero. */
    private fun xref(s: String): String {
        val f = flussi(s)
        val dove = HashMap<Int, Int>()
        for (m in Regex("(?m)^(\\d+) 0 obj\\b").findAll(s)) {
            if (fuori(m.range.first, f)) dove[m.groupValues[1].toInt()] = m.range.first
        }
        if (dove.isEmpty()) throw IllegalStateException("PDF: no objects")
        val x = Regex("(?m)^xref\\s").findAll(s).lastOrNull { fuori(it.range.first, f) }
            ?: throw IllegalStateException("PDF: no index")
        val tr = Regex("trailer\\s*(<<.*?>>)\\s*startxref", RegexOption.DOT_MATCHES_ALL).find(s, x.range.first)
            ?: throw IllegalStateException("PDF: no trailer")
        val quanti = (dove.keys.maxOrNull() ?: 0) + 1
        val out = StringBuilder(s.length + quanti * 20 + 64)
        out.append(s, 0, x.range.first)
        val inizio = out.length
        out.append("xref\n0 ").append(quanti).append('\n')
        out.append("0000000000 65535 f \n")
        for (i in 1 until quanti) {
            val o = dove[i]
            if (o == null) out.append("0000000000 65535 f \n")
            else out.append(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        }
        val trailer = tr.groupValues[1].replace(Regex("/Size\\s+\\d+"), "/Size $quanti")
        out.append("trailer\n").append(trailer).append("\nstartxref\n").append(inizio).append("\n%%EOF\n")
        return out.toString()
    }
}
