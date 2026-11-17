package com.the151universe.art

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Un design diventa un file NUOVO: immagine PNG in Pictures/151 Editor o PDF in
 * Documents/151 Editor. Il file entra in galleria solo intero; il design resta com'è.
 */
object SalvaGrafica {
    /** Le foto del design alla misura che serve davvero nel file (non di più: la memoria finisce). */
    /** Gli oggetti che si vedono sulla pagina [p]: i suoi e quelli della sua pagina madre. */
    private fun conMadre(g: Grafica, p: Int): List<Elemento> =
        g.madre(p).let { k -> if (k > 0) g.di(-k) + g.di(p) else g.di(p) }

    private fun fotoPer(ctx: Context, g: Grafica, pixelPerPunto: Float): Map<String, Bitmap> {
        val serve = HashMap<String, Int>()
        fun chiedi(uri: String, lato: Float) {
            if (uri.isEmpty()) return
            val l = (lato * pixelPerPunto * 1.1f).roundToInt().coerceIn(256, 3000)
            serve[uri] = max(serve[uri] ?: 0, l)
        }
        chiedi(g.fotoSfondo, max(Grafica.LARGO, g.alto) + 2 * g.abbondanza)
        for (e in g.elementi) if (e.tipo == Elemento.FOTO) chiedi(e.uri, max(e.w, e.h) * e.zoomFoto.coerceIn(1f, Grafiche.ZOOM_FOTO_MAX))
        val out = HashMap<String, Bitmap>()
        for ((u, l) in serve) SalvaFoto.anteprima(ctx, Uri.parse(u), l)?.let { out[u] = it }
        return out
    }

    private fun nome(estensione: String): String {
        val k = Calendar.getInstance()
        return Foto.nomeFile(k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH),
            k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), k.get(Calendar.SECOND), estensione)
    }

    /** La [pagina] in PNG alla misura del formato (A4 e A5 a 300 dpi, tagliata). Ritorna indirizzo e nome del file. */
    fun immagine(ctx: Context, g: Grafica, pagina: Int = 0): Pair<Uri, String> {
        val k = g.formato.pxW / Grafica.LARGO
        val foto = fotoPer(ctx, g.copy(elementi = conMadre(g, pagina)), k)
        val b = Bitmap.createBitmap(g.formato.pxW, g.formato.pxH, Bitmap.Config.ARGB_8888)
        try {
            val c = android.graphics.Canvas(b)
            c.scale(k, k)
            GraficaDisegno.disegna(ctx, c, g, { foto[it] }, editor = false, pagina = pagina)
            return SalvaFoto.inGalleria(ctx, b, comePng = true, progetto = g.nome)
        } finally {
            b.recycle(); foto.values.forEach { it.recycle() }
        }
    }

    private val segno = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 0.3f; color = android.graphics.Color.BLACK
    }

    private fun disegnaSegni(c: android.graphics.Canvas, linee: List<Linea>) {
        for (l in linee) c.drawLine(l.x1, l.y1, l.x2, l.y2, segno)
    }

    /**
     * Il PDF di tutte le pagine: i testi restano testo (nitidi a ogni stampa). Con l'abbondanza
     * (A5) la pagina è grande taglio + 3 mm per lato, alla misura ESATTA, con TrimBox e BleedBox:
     * Android sa fare solo pagine a punti interi, quindi si disegna su quella appena più grande,
     * col contenuto spinto in giù dello scarto, e [PdfScatole] riscrive le misure vere.
     * [Grafica.perStampa]: coi segni di taglio la pagina ha una fascia in più attorno, coi segni; col foglio le
     * pagine (carte) vanno su fogli A4 ([Impagina.foglio]).
     */
    fun pdf(ctx: Context, g: Grafica): Pair<Uri, String> {
        val f = g.formato
        if (f.stampa && g.perStampa.foglio) Impagina.foglio(f.pdfW, f.pdfH, g.abbondanzaPt)?.let { return pdfSuFogli(ctx, g, it) }
        val k = f.ptPerPunto
        if (PdfFoto.adatto(g) { conMadre(g, it) }) pdfDiFoto(ctx, g)?.let { return it }
        val fascia = if (f.stampa) Impagina.fascia(g.perStampa.segni) else 0f
        val mediaW = g.mediaW + 2 * fascia; val mediaH = g.mediaH + 2 * fascia
        val intW = kotlin.math.ceil(mediaW - 0.0005f).toInt(); val intH = kotlin.math.ceil(mediaH - 0.0005f).toInt()
        val esatta = intW.toFloat() != mediaW || intH.toFloat() != mediaH || g.abbondanzaMm > 0f || fascia > 0f
        val doc = PdfDocument()
        // le foto in un PDF si vedono anche stampate: 300 dpi. Una pagina alla volta: tutte insieme un
        // libro di 177 pagine-foto (PDF aperto) chiedeva ~3 GB
        var foto: Map<String, Bitmap> = emptyMap()
        try {
            for (p in 0 until g.pagine) {
                foto = fotoPer(ctx, g.copy(elementi = conMadre(g, p)), k * 300f / 72f)
                val pagina = doc.startPage(PdfDocument.PageInfo.Builder(intW, intH, p + 1).create())
                val c = pagina.canvas
                c.translate(0f, intH - mediaH)
                c.save()
                c.translate(fascia + g.abbondanzaPt, fascia + g.abbondanzaPt)
                // coi segni: niente esce dall'abbondanza (la fascia dei segni resta bianca)
                if (fascia > 0f) c.clipRect(-g.abbondanzaPt, -g.abbondanzaPt, f.pdfW + g.abbondanzaPt, f.pdfH + g.abbondanzaPt)
                c.scale(k, k)
                val qui = foto
                GraficaDisegno.disegna(ctx, c, g, { qui[it] }, editor = false, pagina = p, conAbbondanza = true)
                c.restore()
                if (fascia > 0f) {
                    val l = fascia + g.abbondanzaPt; val t = l
                    disegnaSegni(c, Impagina.segniPagina(l, t, l + f.pdfW, t + f.pdfH, g.abbondanzaPt))
                }
                doc.finishPage(pagina)
                // niente recycle qui: il PDF può tenere i pixel fino a writeTo; senza riferimenti li libera lui
                foto = emptyMap()
            }
            val grezzo = java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
            // per la stampa: grigi e nero solo con l'inchiostro nero (come InDesign), poi le misure vere
            val bytes = if (g.formato.stampa) PdfScatole.correggi(PdfNero.soloNero(grezzo), mediaW, mediaH, g.abbondanzaPt, fascia)
                else if (esatta) PdfScatole.correggi(grezzo, mediaW, mediaH, g.abbondanzaPt) else grezzo
            return scriviPdf(ctx, bytes, g.nome)
        } finally {
            doc.close(); foto.values.forEach { it.recycle() }
        }
    }

    /** I byte di un JPEG dritto (niente giro scritto nell'EXIF), null = non si legge o va girato. */
    private fun jpegDritto(ctx: Context, uri: String): ByteArray? = try {
        val u = Uri.parse(uri)
        val b = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
        if (b == null || PdfFoto.misure(b) == null) null
        else {
            val o = android.media.ExifInterface(java.io.ByteArrayInputStream(b))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            if (o == android.media.ExifInterface.ORIENTATION_NORMAL || o == android.media.ExifInterface.ORIENTATION_UNDEFINED) b else null
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    /**
     * Un libro di sole foto ([PdfFoto.adatto]): ogni JPEG entra nel PDF così com'è, una pagina alla volta, prima in
     * un file della cache e poi nella cartella. null = una foto non va bene (PNG, girata, illeggibile): si passa al
     * PDF di Android.
     */
    private fun pdfDiFoto(ctx: Context, g: Grafica): Pair<Uri, String>? {
        val f = g.formato; val k = f.ptPerPunto; val ab = g.abbondanzaPt
        val tmp = File(ctx.cacheDir, "libro-${android.os.Process.myPid()}.pdf.part")
        try {
            val ok = tmp.outputStream().buffered(1 shl 16).use { out ->
                PdfFoto.scrivi(out, g.pagine) { p ->
                    val posti = conMadre(g, p).filter { it.uri.isNotEmpty() }.map { e ->
                        val j = jpegDritto(ctx, e.uri)
                        val m = j?.let { PdfFoto.misure(it) }
                        val r = Grafiche.rettangoloFoto(e, (m?.first ?: 1).toFloat(), (m?.second ?: 1).toFloat())
                        PdfFoto.Posto({ j },
                            Riquadro(ab + e.x * k, ab + e.y * k, ab + (e.x + e.w) * k, ab + (e.y + e.h) * k),
                            Riquadro(ab + (e.x + r.l) * k, ab + (e.y + r.t) * k, ab + (e.x + r.r) * k, ab + (e.y + r.b) * k))
                    }
                    val stampa = f.stampa || ab > 0f
                    PdfFoto.Pagina(g.mediaW, g.mediaH, g.sfondo,
                        trim = if (stampa) Riquadro(ab, ab, ab + f.pdfW, ab + f.pdfH) else null,
                        bleed = if (stampa) Riquadro(0f, 0f, g.mediaW, g.mediaH) else null, foto = posti)
                }
            }
            if (!ok || tmp.length() <= 0L) return null
            return scriviPdf(ctx, g.nome) { out -> tmp.inputStream().use { it.copyTo(out, 1 shl 16) } }
        } finally {
            tmp.delete()
        }
    }

    /** Disegna sul [c] (in punti di stampa) il foglio numero [n] del [fg]: le sue carte (col retro: fronti o retri a specchio) e, se scelti, i segni. */
    fun disegnaFoglio(ctx: Context, c: android.graphics.Canvas, g: Grafica, fg: Foglio, n: Int, foto: (Int) -> ((String) -> Bitmap?)) {
        val k = g.formato.ptPerPunto
        for ((p, posto) in Impagina.sulFoglio(fg, n, g.pagine, g.perStampa.retro)) {
            c.save()
            c.translate(posto.first, posto.second)
            // ogni carta resta nella sua abbondanza: non entra in quella accanto
            c.clipRect(-fg.ab, -fg.ab, fg.cw + fg.ab, fg.ch + fg.ab)
            c.scale(k, k)
            GraficaDisegno.disegna(ctx, c, g, foto(p), editor = false, pagina = p, conAbbondanza = true)
            c.restore()
        }
        if (g.perStampa.segni) disegnaSegni(c, Impagina.segniFoglio(fg))
    }

    /** Le carte su fogli A4, [Foglio.perFoglio] per foglio, i segni di taglio nel bordo bianco. */
    private fun pdfSuFogli(ctx: Context, g: Grafica, fg: Foglio): Pair<Uri, String> {
        val k = g.formato.ptPerPunto
        val fogli = Impagina.fogli(g.pagine, fg.perFoglio, g.perStampa.retro)
        val doc = PdfDocument()
        // come per le pagine: niente recycle, il PDF tiene i pixel fino a writeTo; senza riferimenti li libera lui
        try {
            for (n in 0 until fogli) {
                val pagina = doc.startPage(PdfDocument.PageInfo.Builder(fg.w.toInt(), fg.h.toInt(), n + 1).create())
                disegnaFoglio(ctx, pagina.canvas, g, fg, n) { p ->
                    val m = fotoPer(ctx, g.copy(elementi = conMadre(g, p)), k * 300f / 72f)
                    ({ u: String -> m[u] })
                }
                doc.finishPage(pagina)
            }
            val grezzo = java.io.ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
            // soloNero cambia la lunghezza dei disegni: senza l'indice rifatto il PDF ha gli indirizzi sbagliati
            return scriviPdf(ctx, PdfScatole.indice(PdfNero.soloNero(grezzo)), g.nome)
        } finally {
            doc.close()
        }
    }

    private fun scriviPdf(ctx: Context, bytes: ByteArray, progetto: String): Pair<Uri, String> = scriviPdf(ctx, progetto) { it.write(bytes) }

    /** Il PDF scritto da [scrivi] dritto nel file (un libro di foto non passa tutto dalla memoria). */
    private fun scriviPdf(ctx: Context, progetto: String, scrivi: (java.io.OutputStream) -> Unit): Pair<Uri, String> =
        scriviDocumento(ctx, progetto, "pdf", "application/pdf", scrivi)

    private fun scriviDocumento(ctx: Context, progetto: String, estensione: String, tipo: String, scrivi: (java.io.OutputStream) -> Unit): Pair<Uri, String> {
        val nome = Suono.nomeFile(progetto, nome(estensione))
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
                put(MediaStore.MediaColumns.MIME_TYPE, tipo)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/151 Editor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Files.getContentUri("external"), v)
                ?: throw IllegalStateException("the phone refused the file")
            try {
                cr.openOutputStream(uri)?.use { scrivi(it) } ?: throw IllegalStateException("the phone refused the file")
                val n = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                if (n <= 0L) throw IllegalStateException("empty file")
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri to nome
            } catch (e: Exception) {
                try { cr.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
        }
        // Android 8–9: la cartella pubblica, col permesso già chiesto da chi chiama
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "151 Editor")
        dir.mkdirs()
        val dest = File(dir, nome)
        val part = File(dir, "$nome.part")
        try {
            part.outputStream().use { scrivi(it) }
            if (part.length() <= 0L || !part.renameTo(dest)) throw IllegalStateException("the file could not be written")
        } catch (e: Exception) {
            part.delete(); throw e
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(tipo), null)
        return Uri.fromFile(dest) to nome
    }
}
