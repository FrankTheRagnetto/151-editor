package com.the151universe.art

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

/** Misura temporanea: dove va il tempo sulle parti pure del Save PDF, libro finto di 177 pagine. */
class PdfTempoTest {
    private fun stretto(t: ByteArray): String {
        val o = ByteArrayOutputStream(); DeflaterOutputStream(o).use { it.write(t) }
        return String(o.toByteArray(), Charsets.ISO_8859_1)
    }

    fun libro(pagine: Int = 177): ByteArray {
        val r = java.util.Random(7)
        val b = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val dove = HashMap<Int, Int>()
        fun obj(n: Int, corpo: String) { dove[n] = b.length; b.append("$n 0 obj\n").append(corpo).append("\nendobj\n") }
        val primaPagina = 10
        obj(1, "<</Type /Catalog /Pages 2 0 R>>")
        obj(2, "<</Type /Pages /Count $pagine /Kids [${(0 until pagine).joinToString(" ") { "${primaPagina + 2 * it} 0 R" }}]>>")
        for (f in 0 until 4) {
            val dati = ByteArray(60_000).also { r.nextBytes(it) }
            val s = String(dati, Charsets.ISO_8859_1)
            obj(3 + f, "<</Length1 ${dati.size}\n/Filter /FlateDecode\n/Length ${s.length}>> stream\n$s\nendstream")
        }
        for (p in 0 until pagine) {
            val c = StringBuilder()
            c.append("1 0 0 -1 0 613 cm\nq\n")
            for (riga in 0 until 75) {
                val grigio = r.nextInt(4) != 0
                c.append("q\n").append(if (grigio) ".1373 .1216 .1255 rg\n" else ".8431 .149 .2392 rg\n")
                c.append("BT\n/F${r.nextInt(4)} 13.3333 Tf\n1 0 0 -1 ${45 + r.nextInt(200)}.${r.nextInt(1000)} ${100 + riga * 6}.${r.nextInt(100)} Tm\n[")
                for (gl in 0 until 22) c.append(String.format("<%04X> %d.%03d ", r.nextInt(400), -r.nextInt(30), r.nextInt(1000)))
                c.append("] TJ\nET\nQ\n")
                if (riga % 10 == 0) c.append("q\n.8431 .851 .8549 rg\n12.5 ${riga * 6}.25 300.75 0.5 re\nf\nQ\n")
            }
            c.append("Q\n")
            val d = stretto(c.toString().toByteArray(Charsets.ISO_8859_1))
            obj(primaPagina + 2 * p, "<</Type /Page\n/Parent 2 0 R\n/MediaBox [0 0 437 613]\n/Resources <</Font <</F0 3 0 R /F1 4 0 R /F2 5 0 R /F3 6 0 R>>>>\n/Contents ${primaPagina + 2 * p + 1} 0 R>>")
            obj(primaPagina + 2 * p + 1, "<</Filter /FlateDecode\n/Length ${d.length}>> stream\n$d\nendstream")
        }
        val quanti = (dove.keys.maxOrNull() ?: 0) + 1
        val x = b.length
        b.append("xref\n0 $quanti\n0000000000 65535 f \n")
        for (i in 1 until quanti) b.append(dove[i]?.let { String.format("%010d 00000 n \n", it) } ?: "0000000000 65535 f \n")
        b.append("trailer\n<</Size $quanti /Root 1 0 R>>\nstartxref\n$x\n%%EOF\n")
        return b.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private inline fun ms(f: () -> Unit): Long { val t = System.nanoTime(); f(); return (System.nanoTime() - t) / 1_000_000 }

    @Test fun dove() {
        if (System.getProperty("pdf.tempo") == null && System.getenv("PDF_TEMPO") == null) return
        val pdf = libro()
        val f = Formato.A5
        println("PDF finto: ${pdf.size / 1024} KB, 177 pagine")
        repeat(3) { giro ->
            var nero: ByteArray = pdf
            val tNero = ms { nero = PdfNero.soloNero(pdf) }
            val tScatole = ms { PdfScatole.correggi(nero, f.mediaW, f.mediaH, f.abbondanzaPt) }
            println("giro $giro: soloNero ${tNero} ms · correggi ${tScatole} ms")
            // le parti: il testo dei disegni in chiaro, la sostituzione dei colori, la ricompressione
            val testi = ArrayList<String>()
            val tGonfia = ms {
                val s = String(pdf, Charsets.ISO_8859_1)
                for (m in Regex("/Length (\\d+)>> stream\\n").findAll(s)) {
                    val a = m.range.last + 1; val n = m.groupValues[1].toInt()
                    if (n > 50_000) continue
                    testi += String(java.util.zip.InflaterInputStream(s.substring(a, a + n).toByteArray(Charsets.ISO_8859_1).inputStream()).readBytes(), Charsets.ISO_8859_1)
                }
            }
            var nuovi: List<String> = emptyList()
            val tRegex = ms { nuovi = testi.map { PdfNero.inChiaro(it) } }
            val tStringi = ms { nuovi.forEach { val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION); d.setInput(it.toByteArray(Charsets.ISO_8859_1)); d.finish(); val buf = ByteArray(65536); while (!d.finished()) d.deflate(buf); d.end() } }
            val tVeloce = ms { nuovi.forEach { val d = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION); d.setInput(it.toByteArray(Charsets.ISO_8859_1)); d.finish(); val buf = ByteArray(65536); while (!d.finished()) d.deflate(buf); d.end() } }
            for (liv in listOf(1, 3, 6, 9)) {
                var tot = 0L
                val t = ms { nuovi.forEach { val d = java.util.zip.Deflater(liv); d.setInput(it.toByteArray(Charsets.ISO_8859_1)); d.finish(); val buf = ByteArray(65536); while (!d.finished()) tot += d.deflate(buf); d.end() } }
                println("   livello $liv: $t ms, ${tot / 1024} KB")
            }
            println("   in chiaro ${testi.sumOf { it.length } / 1024} KB: gonfia $tGonfia ms · colori (inChiaro) $tRegex ms · sgonfia BEST $tStringi ms · sgonfia DEFAULT $tVeloce ms")
        }
    }
}
