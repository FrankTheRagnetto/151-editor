package com.the151universe.art

import java.io.OutputStream

/**
 * Il PDF di un libro di foto ( dopo: «PDF di un libro di foto (OOM: 283 MB in memoria)»). Android tiene i
 * pixel di ogni pagina fino alla fine; qui ogni JPEG entra nel file così com'è (DCTDecode), letto e scritto una
 * pagina alla volta: niente pixel in memoria, stessa qualità della foto. Solo per pagine fatte di sole foto
 * dritte e quadrate ([adatto]); il resto passa dal PDF di Android. Logica pura.
 */
object PdfFoto {
    /** Una foto sulla pagina: [clip] il riquadro, [dove] la foto intera (può uscire dal riquadro), in punti dall'alto a sinistra. */
    class Posto(val jpeg: () -> ByteArray?, val clip: Riquadro, val dove: Riquadro)

    /** Una pagina: [w]×[h] punti, il fondo [fondo] (ARGB), le scatole di stampa, le foto dal basso verso l'alto. */
    class Pagina(val w: Float, val h: Float, val fondo: Int, val trim: Riquadro?, val bleed: Riquadro?, val foto: List<Posto>)

    /** Misure e colori di un JPEG: null = non è un JPEG che il PDF sa mostrare (solo grigio e RGB, niente CMYK). */
    fun misure(j: ByteArray): Triple<Int, Int, Int>? {
        if (j.size < 4 || j[0] != 0xFF.toByte() || j[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < j.size) {
            if (j[i] != 0xFF.toByte()) return null
            val m = j[i + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            val len = ((j[i + 2].toInt() and 0xFF) shl 8) or (j[i + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((j[i + 5].toInt() and 0xFF) shl 8) or (j[i + 6].toInt() and 0xFF)
                val w = ((j[i + 7].toInt() and 0xFF) shl 8) or (j[i + 8].toInt() and 0xFF)
                val n = j[i + 9].toInt() and 0xFF
                return if (w > 0 && h > 0 && (n == 1 || n == 3)) Triple(w, h, n) else null
            }
            i += 2 + len
        }
        return null
    }

    private fun n(f: Float): String {
        val r = Math.round(f * 1000.0) / 1000.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun scatola(r: Riquadro, h: Float) = "[${n(r.l)} ${n(h - r.b)} ${n(r.r)} ${n(h - r.t)}]"

    /**
     * Scrive il PDF in [out], pagina per pagina ([pagine] si leggono una alla volta). Ritorna false se una foto non
     * si legge (chi chiama passa all'altra strada).
     */
    fun scrivi(out: OutputStream, quante: Int, pagina: (Int) -> Pagina): Boolean {
        var pos = 0L
        val dove = ArrayList<Long>()          // indice = numero dell'oggetto - 1
        fun scrivi(b: ByteArray) { out.write(b); pos += b.size }
        fun scrivi(s: String) = scrivi(s.toByteArray(Charsets.ISO_8859_1))
        fun oggetto(num: Int) { while (dove.size < num) dove.add(0L); dove[num - 1] = pos; scrivi("$num 0 obj\n") }
        scrivi("%PDF-1.4\n%âãÏÓ\n")
        // 1 catalogo, 2 pagine; poi per ogni pagina: pagina, contenuto, foto…
        var prossimo = 3
        val figlie = ArrayList<Int>()
        for (p in 0 until quante) {
            val pg = pagina(p)
            val numPagina = prossimo++; val numContenuto = prossimo++
            val immagini = ArrayList<Pair<Int, ByteArray>>()
            val disegno = StringBuilder()
            if (pg.fondo != 0) {
                val r = ((pg.fondo shr 16) and 0xFF) / 255f; val gg = ((pg.fondo shr 8) and 0xFF) / 255f; val b = (pg.fondo and 0xFF) / 255f
                disegno.append("${n(r)} ${n(gg)} ${n(b)} rg 0 0 ${n(pg.w)} ${n(pg.h)} re f\n")
            }
            for (f in pg.foto) {
                val j = f.jpeg() ?: return false
                misure(j) ?: return false
                val num = prossimo++
                immagini += num to j
                val c = f.clip; val d = f.dove
                disegno.append("q ${n(c.l)} ${n(pg.h - c.b)} ${n(c.r - c.l)} ${n(c.b - c.t)} re W n ")
                disegno.append("${n(d.r - d.l)} 0 0 ${n(d.b - d.t)} ${n(d.l)} ${n(pg.h - d.b)} cm /F$num Do Q\n")
            }
            val risorse = if (immagini.isEmpty()) "" else "/Resources << /XObject << " + immagini.joinToString(" ") { "/F${it.first} ${it.first} 0 R" } + " >> >> "
            val scatole = (pg.bleed?.let { "/BleedBox ${scatola(it, pg.h)} " } ?: "") + (pg.trim?.let { "/TrimBox ${scatola(it, pg.h)} " } ?: "")
            oggetto(numPagina)
            scrivi("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pg.w)} ${n(pg.h)}] $scatole$risorse/Contents $numContenuto 0 R >>\nendobj\n")
            val corpo = disegno.toString().toByteArray(Charsets.ISO_8859_1)
            oggetto(numContenuto)
            scrivi("<< /Length ${corpo.size} >>\nstream\n"); scrivi(corpo); scrivi("\nendstream\nendobj\n")
            for ((num, j) in immagini) {
                val (w, h, comp) = misure(j)!!
                oggetto(num)
                scrivi("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /${if (comp == 1) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 /Filter /DCTDecode /Length ${j.size} >>\nstream\n")
                scrivi(j); scrivi("\nendstream\nendobj\n")
            }
            figlie += numPagina
        }
        oggetto(1); scrivi("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        oggetto(2); scrivi("<< /Type /Pages /Kids [${figlie.joinToString(" ") { "$it 0 R" }}] /Count ${figlie.size} >>\nendobj\n")
        val xref = pos
        val tab = StringBuilder("xref\n0 ${dove.size + 1}\n0000000000 65535 f \n")
        for (d in dove) tab.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", d))
        scrivi(tab.toString())
        scrivi("trailer\n<< /Size ${dove.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
        return true
    }

    /** La grafica si può fare così: ogni oggetto di ogni pagina (madri comprese) è una foto dritta, quadrata, piena. */
    fun adatto(g: Grafica, oggetti: (Int) -> List<Elemento>): Boolean {
        if (g.fotoSfondo.isNotEmpty() || g.perStampa.segni || g.perStampa.foglio) return false
        var foto = 0
        for (p in 0 until g.pagine) for (e in oggetti(p)) {
            if (e.tipo != Elemento.FOTO || e.rot != 0f || e.opacita != 100 || e.maschera != Elemento.QUADRATA || e.bordo != 0) return false
            if (e.uri.isNotEmpty()) foto++
        }
        return foto > 0
    }
}
