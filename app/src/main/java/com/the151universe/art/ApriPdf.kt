package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns

/**
 * «Open a PDF». Ogni pagina del PDF
 * diventa una pagina del design con la sua foto a tutta pagina, alla risoluzione di stampa del formato
 * (A5: 1748 px = 300 dpi). Il motore è quello del telefono (PdfRenderer): niente librerie né permessi.
 * Le parole restano nella foto: per ora si sfoglia e si guarda, non si riscrive.
 */
object ApriPdf {
    class Esito(val grafica: Grafica?, val errore: String? = null)

    /** [avanza] (fatte, tutte) a ogni pagina; [ferma] true = smetti (le foto già fatte le pulisce pulisciMedia). */
    fun apri(ctx: Context, u: Uri, avanza: (Int, Int) -> Unit, ferma: () -> Boolean): Esito {
        val fd = try { ctx.contentResolver.openFileDescriptor(u, "r") } catch (_: Exception) { null }
            ?: return Esito(null, "This PDF can't be opened.")
        val r = try { PdfRenderer(fd) } catch (_: SecurityException) { fd.close(); return Esito(null, "This PDF is locked with a password.") }
            catch (_: Exception) { fd.close(); return Esito(null, "This file isn't a PDF the phone can read.") }
        r.use {
            val n = r.pageCount
            if (n == 0) return Esito(null, "This PDF has no pages.")
            if (n > Grafica.PAGINE_MAX) return Esito(null, "This PDF has $n pages: the most is ${Grafica.PAGINE_MAX}.")
            val (f, ab) = r.openPage(0).use { p -> Grafiche.formatoPer(p.width.toFloat(), p.height.toFloat()) }
            val el = ArrayList<Elemento>(n)
            for (i in 0 until n) {
                if (ferma()) return Esito(null)
                avanza(i, n)
                val uri = r.openPage(i).use { p ->
                    // larga quanto la stampa del formato (abbondanza compresa se la pagina ce l'ha)
                    val largo = f.pxW
                    val alto = (largo.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val b = try { Bitmap.createBitmap(largo, alto, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { null }
                        ?: return Esito(null, "The phone ran out of memory at page ${i + 1}.")
                    b.eraseColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val s = Archivio.salvaPagina(ctx, b)
                    b.recycle()
                    s
                } ?: return Esito(null, "The phone has no space left (page ${i + 1}).")
                // la pagina intera; con l'abbondanza dentro, la foto esce dal taglio quanto lei
                val k = mm(ab) / f.ptPerPunto
                el.add(Elemento(i + 1L, Elemento.FOTO, -k, -k, Grafica.LARGO + 2 * k, f.alto + 2 * k, uri = uri, pagina = i, fermo = true))
            }
            avanza(n, n)
            return Esito(Grafica(nome(ctx, u), f, Elemento.BIANCO, elementi = el, pagine = n, sceltaAbbondanza = if (f.stampa) ab else null))
        }
    }


    /** Il nome del file senza «.pdf» (il progetto si chiama come lui). */
    fun nome(ctx: Context, u: Uri): String {
        val n = try {
            ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) { null }
        return (n ?: "PDF").removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "PDF" }
    }
}
