package com.the151universe.art

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Leggere la foto (dritta, come la mostra la galleria) e salvarne una COPIA modificata
 * in Pictures/151 Editor. L'originale non si apre mai in scrittura.
 */
object SalvaFoto {
    /** Il lato lungo dell'anteprima: abbastanza per lo schermo, leggera da ricolorare mentre il dito scorre. */
    const val ANTEPRIMA = 1600

    private fun misura(ctx: Context, uri: Uri): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        return o.outWidth to o.outHeight
    }

    private fun orientamento(ctx: Context, uri: Uri): Pair<Int, Boolean> = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            Foto.exif(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
        } ?: (0 to false)
    } catch (_: Exception) { 0 to false }

    private fun decodifica(ctx: Context, uri: Uri, campione: Int, mutabile: Boolean): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inSampleSize = campione
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = mutabile
        }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    /** Il tipo del file: PNG resta PNG (trasparenza), tutto il resto diventa JPEG. */
    fun png(ctx: Context, uri: Uri): Boolean = ctx.contentResolver.getType(uri) == "image/png"

    /** L'anteprima, dritta, col lato lungo al massimo [ANTEPRIMA]. null = la foto non si legge. */
    fun anteprima(ctx: Context, uri: Uri, lato: Int = ANTEPRIMA): Bitmap? {
        val (w, h) = misura(ctx, uri)
        if (w <= 0 || h <= 0) return null
        val grezza = decodifica(ctx, uri, Foto.campioneAnteprima(w, h, lato), false) ?: return null
        val lungo = max(grezza.width, grezza.height)
        val piccola = if (lungo > lato) {
            val f = lato.toFloat() / lungo
            Bitmap.createScaledBitmap(grezza, (grezza.width * f).roundToInt().coerceAtLeast(1), (grezza.height * f).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== grezza) grezza.recycle() }
        } else grezza
        val (g, s) = orientamento(ctx, uri)
        val dritta = Disegno.orienta(piccola, g, s)
        if (dritta !== piccola) piccola.recycle()
        return dritta
    }

    /**
     * La foto modificata a piena misura (fino a [Foto.PIXEL_MAX]) — la stessa ricetta
     * dell'anteprima: orienta, colora, raddrizza, ritaglia, testi.
     */
    fun componi(ctx: Context, uri: Uri, m: Modifiche): Bitmap {
        val (w, h) = misura(ctx, uri)
        if (w <= 0 || h <= 0) throw IllegalStateException("the photo can't be read")
        val (eg, es) = orientamento(ctx, uri)
        val identita = eg == 0 && !es && m.giri == 0 && !m.specchio
        val grezza = decodifica(ctx, uri, Foto.campione(w, h, Foto.PIXEL_MAX), identita)
            ?: throw IllegalStateException("the photo can't be read")
        // orientamento della galleria + giri e specchio di chi lo usa, in una passata sola
        val quadro: Bitmap = if (identita) grezza else {
            val m1 = Disegno.matrice(grezza.width, grezza.height, eg, es)
            val (dw, dh) = Foto.quadro(grezza.width, grezza.height, eg)
            m1.postConcat(Disegno.matrice(dw, dh, m.giri, m.specchio))
            val (qw, qh) = Foto.quadro(dw, dh, m.giri)
            Bitmap.createBitmap(qw, qh, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(grezza, m1, Paint(Paint.FILTER_BITMAP_FLAG))
                grezza.recycle()
            }
        }
        // colore a strisce: niente seconda copia intera in memoria
        if (!Foto.coloreNeutro(m)) {
            val righe = 128
            val buf = IntArray(quadro.width * righe)
            var y = 0
            while (y < quadro.height) {
                val n = minOf(righe, quadro.height - y)
                quadro.getPixels(buf, 0, quadro.width, 0, y, quadro.width, n)
                Foto.colora(buf, m, 0, quadro.width * n)
                quadro.setPixels(buf, 0, quadro.width, 0, y, quadro.width, n)
                y += n
            }
        }
        return ricetta(ctx, quadro, m).also { quadro.recycle() }
    }

    /**
     * L'ultimo passo della ricetta, uguale per file, schermo del salvataggio e miniature:
     * dal [quadro] (girato e colorato) a raddrizza + ritaglio + disegno + testi.
     */
    fun ricetta(ctx: Context, quadro: Bitmap, m: Modifiche): Bitmap {
        val W = quadro.width; val H = quadro.height
        val r = m.ritaglio
        val l = (r.l * W).roundToInt().coerceIn(0, W - 1)
        val t = (r.t * H).roundToInt().coerceIn(0, H - 1)
        val cw = ((r.r * W).roundToInt() - l).coerceIn(1, W - l)
        val ch = ((r.b * H).roundToInt() - t).coerceIn(1, H - t)
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate(-l.toFloat(), -t.toFloat())
        Disegno.foto(c, quadro, m.raddrizza)
        ManoDisegno.disegna(c, m.tratti, W.toFloat(), H.toFloat())
        Disegno.testi(ctx, c, m.testi, W.toFloat(), H.toFloat())
        return out
    }

    /** La foto modificata in piccolo, per l'elenco delle bozze. null = non si legge. */
    fun miniatura(ctx: Context, uri: Uri, m: Modifiche, lato: Int): Bitmap? = try {
        val dritta = anteprima(ctx, uri, lato)
        if (dritta == null) null else {
            val q = Disegno.orienta(dritta, m.giri, m.specchio).let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
            if (!Foto.coloreNeutro(m)) {
                val px = IntArray(q.width * q.height)
                q.getPixels(px, 0, q.width, 0, 0, q.width, q.height)
                Foto.colora(px, m)
                q.setPixels(px, 0, q.width, 0, 0, q.width, q.height)
            }
            ricetta(ctx, q, m)
        }
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }

    private class Contatore(out: OutputStream) : FilterOutputStream(out) {
        var scritti = 0L
        override fun write(b: Int) { out.write(b); scritti++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); scritti += len }
    }

    /** Salva [b] in galleria come file nuovo; entra in galleria solo intero. Ritorna il suo indirizzo e il nome. */
    fun inGalleria(ctx: Context, b: Bitmap, comePng: Boolean, progetto: String = ""): Pair<Uri, String> {
        val k = Calendar.getInstance()
        val nome = Suono.nomeFile(progetto, Foto.nomeFile(
            k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH),
            k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), k.get(Calendar.SECOND),
            if (comePng) "png" else "jpg",
        ))
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nome)
            put(MediaStore.Images.Media.MIME_TYPE, if (comePng) "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/151 Editor")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "151 Editor")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, nome).absolutePath)
            }
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            ?: throw IllegalStateException("the gallery refused the file")
        try {
            val scritti = cr.openOutputStream(uri)?.use { os ->
                val conta = Contatore(os)
                val ok = b.compress(if (comePng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, conta)
                conta.flush()
                if (!ok) throw IllegalStateException("the photo could not be written")
                conta.scritti
            } ?: throw IllegalStateException("the gallery refused the file")
            if (scritti <= 0L) throw IllegalStateException("empty file")
            if (Build.VERSION.SDK_INT >= 29) {
                cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            return uri to nome
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}
