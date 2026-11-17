package com.the151universe.art

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Il nero della tipografia. Il PDF di Android scrive ogni colore in RGB: il nero del quaderno
 * (#231F20) esce «.1373 .1216 .1255 rg» e in tipografia diventa un misto dei 4 inchiostri, e il
 * testo di 5–6 pt rischia di sfocarsi se i colori non combaciano. InDesign lo scrive «0 0 0 1 k»:
 * solo l'inchiostro nero (misurato sulla pagina 71 il 05/11, anche il grigio della tabella
 * «0 0 0 0.17 k»). Qui, senza Android: nei disegni della pagina ogni grigio (i tre canali quasi
 * uguali) diventa solo nero, con la stessa scala di InDesign (100% K = #231F20, carta = 0%).
 * I colori veri (rosso, blu…) e le foto restano come sono. Una forma che non si conosce = il PDF
 * resta com'era, mai un file storto.
 */
object PdfNero {
    /** La luce del nero pieno di InDesign (#231F20 = 35,31,32): sotto, il nero resta 100%. */
    private const val NERO = (35 + 31 + 32) / 3f / 255f
    /** Quanto possono differire i tre canali per contare come grigio. */
    private const val SCARTO = 0.035f

    /** Le teste dei flussi, e ciò che le esclude (font, foto): compilate una volta, non a ogni flusso. */
    private val testa = Regex("<<([^<>]*)>>\\s*stream\\r?\\n")
    private val conTipo = Regex("/(Subtype|Type|Length1|Length2|DecodeParms)\\b")
    private val lunghezza = Regex("/Length\\s+(\\d+)(?!\\s+\\d+\\s+R)")
    private val lunghezzaVecchia = Regex("/Length\\s+\\d+")

    /**
     * Quanto si stringono i disegni riscritti. Era il massimo (9): sul libro di 177 pagine 690 ms
     * sul PC su 1000 del giro intero, per un file più piccolo del 2%; il 3 ci mette 63 ms (misurato, PdfTempoTest).
     */
    private const val STRETTA = 3

    private fun n(v: Float) = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    /** La K di un grigio, o null se non è un grigio. */
    fun k(r: Float, g: Float, b: Float): Float? {
        if (maxOf(r, g, b) - minOf(r, g, b) > SCARTO) return null
        val luce = (r + g + b) / 3f
        return ((1f - luce) / (1f - NERO)).coerceIn(0f, 1f)
    }

    /**
     * I disegni di una pagina, scritti: «r g b rg» dei grigi → «0 0 0 k k» (RG → K). Fa ciò che faceva la regex «(?<![\\w.])(num)\\s+(num)\\s+(num)\\s+(rg|RG)(?!\\w)» con num = «-?\\d*\\.?\\d+», ma
     * a mano: si cerca ogni «rg»/«RG» e si leggono all'indietro i tre numeri. La regex provava una partita a ogni
     * numero della pagina (le posizioni delle lettere sono migliaia): 30% del Save PDF del libro.
     * Stesso risultato byte per byte (PdfNeroTest.loScannerFaComeLaRegex).
     */
    fun inChiaro(c: String): String {
        var out: StringBuilder? = null
        var copiato = 0
        var i = 0
        val lung = c.length
        while (i < lung - 1) {
            val a = c[i]; val b = c[i + 1]
            val op = (a == 'r' && b == 'g') || (a == 'R' && b == 'G')
            if (!op || i == 0 || !spazio(c[i - 1]) || (i + 2 < lung && parola(c[i + 2]))) { i++; continue }
            // all'indietro: spazi, terzo numero, spazi, secondo, spazi, primo
            var fine3 = i; while (fine3 > 0 && spazio(c[fine3 - 1])) fine3--
            val ini3 = inizioPezzo(c, fine3)
            if (ini3 == fine3 || !numero(c, ini3, fine3) || ini3 == 0 || !spazio(c[ini3 - 1])) { i++; continue }
            var fine2 = ini3; while (fine2 > 0 && spazio(c[fine2 - 1])) fine2--
            val ini2 = inizioPezzo(c, fine2)
            if (ini2 == fine2 || !numero(c, ini2, fine2) || ini2 == 0 || !spazio(c[ini2 - 1])) { i++; continue }
            var fine1 = ini2; while (fine1 > 0 && spazio(c[fine1 - 1])) fine1--
            val pezzo1 = inizioPezzo(c, fine1)
            // il primo può essere la coda di un pezzo («)-.5»): il più lungo che è un numero e non segue lettera o punto
            var ini1 = -1
            for (s in pezzo1 until fine1) {
                if (s > 0 && (parola(c[s - 1]) || c[s - 1] == '.')) continue
                if (s < copiato) continue
                if (numero(c, s, fine1)) { ini1 = s; break }
            }
            if (ini1 < 0) { i++; continue }
            val kk = k(c.substring(ini1, fine1).toFloat(), c.substring(ini2, fine2).toFloat(), c.substring(ini3, fine3).toFloat())
            if (kk == null) { i += 2; continue }
            val o = out ?: StringBuilder(c.length).also { out = it }
            o.append(c, copiato, ini1).append("0 0 0 ").append(n(kk)).append(if (a == 'r') " k" else " K")
            copiato = i + 2
            i += 2
        }
        val o = out ?: return c
        return o.append(c, copiato, lung).toString()
    }

    private fun spazio(ch: Char) = ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t' || ch == '\u000B' || ch == '\u000C'
    private fun parola(ch: Char) = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'

    /** Dove comincia il pezzo (senza spazi) che finisce in [fine]. */
    private fun inizioPezzo(c: String, fine: Int): Int { var s = fine; while (s > 0 && !spazio(c[s - 1])) s--; return s }

    /** c[da until a] è tutto «-?\\d*\\.?\\d+». */
    private fun numero(c: String, da: Int, a: Int): Boolean {
        var i = da
        if (i < a && c[i] == '-') i++
        var cifre = 0
        while (i < a && c[i] in '0'..'9') { i++; cifre++ }
        if (i < a && c[i] == '.') {
            i++
            var dopo = 0
            while (i < a && c[i] in '0'..'9') { i++; dopo++ }
            return i == a && dopo > 0
        }
        return i == a && cifre > 0
    }

    fun soloNero(pdf: ByteArray): ByteArray {
        val s = String(pdf, Charsets.ISO_8859_1)
        val out = StringBuilder(s.length + 1024)
        var da = 0
        var cambiati = 0
        for (m in testa.findAll(s)) {
            if (m.range.first < da) continue
            val diz = m.groupValues[1]
            // solo i disegni delle pagine: compressi, senza tipo (i font e le foto ne hanno uno)
            if (!diz.contains("/FlateDecode") || conTipo.containsMatchIn(diz)) continue
            val lungo = lunghezza.find(diz)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val a = m.range.last + 1
            if (a + lungo > s.length) continue
            val dati = s.substring(a, a + lungo).toByteArray(Charsets.ISO_8859_1)
            val chiaro = try { gonfia(dati) } catch (_: Exception) { continue }
            val t = String(chiaro, Charsets.ISO_8859_1)
            val nuovo = inChiaro(t)
            if (nuovo == t) continue
            val stretto = String(sgonfia(nuovo.toByteArray(Charsets.ISO_8859_1)), Charsets.ISO_8859_1)
            val dizNuovo = diz.replaceFirst(lunghezzaVecchia, "/Length ${stretto.length}")
            out.append(s, da, m.range.first)
            out.append("<<").append(dizNuovo).append(">>").append(s, m.range.first + 2 + diz.length + 2, a)
            out.append(stretto)
            da = a + lungo
            cambiati++
        }
        if (cambiati == 0) return pdf
        out.append(s, da, s.length)
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun gonfia(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val o = ByteArrayOutputStream(b.size * 4)
        val buf = ByteArray(16384)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            o.write(buf, 0, n)
        }
        inf.end()
        if (o.size() == 0) throw IllegalStateException("empty")
        return o.toByteArray()
    }

    private fun sgonfia(b: ByteArray): ByteArray {
        val d = Deflater(STRETTA)
        d.setInput(b); d.finish()
        val o = ByteArrayOutputStream(b.size / 2 + 64)
        val buf = ByteArray(16384)
        while (!d.finished()) { val n = d.deflate(buf); o.write(buf, 0, n) }
        d.end()
        return o.toByteArray()
    }
}
