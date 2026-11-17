package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Le bozze sul telefono: video in files/bozze/<id>.txt, foto in files/foto/<id>.txt
 * (+ <id>.orig se la foto e' arrivata da un'altra app e va tenuta qui); e quello che si sa
 * di un video. La 0.1.0 teneva un montaggio solo in files/montaggio.txt: al primo
 * avvio diventa la prima bozza.
 */
object Archivio {
    data class Voce(val id: String, val bozza: Bozza, val modificata: Long)

    private fun cartella(ctx: Context) = File(ctx.filesDir, "bozze").also { it.mkdirs() }
    private fun file(ctx: Context, id: String) = File(cartella(ctx), "$id.txt")

    /** Il montaggio della 0.1.0 entra fra le bozze; il vecchio file si toglie solo a copia riuscita. */
    fun migra(ctx: Context) {
        val vecchio = File(ctx.filesDir, "montaggio.txt")
        if (!vecchio.exists()) return
        val b = try { Progetto.leggi(vecchio.readText()) } catch (_: Exception) { null }
        if (b == null || b.clip.isEmpty()) { vecchio.delete(); return }
        val id = nuovoId()
        if (salva(ctx, id, b.copy(nome = "Project 1"))) vecchio.delete()
    }

    fun nuovoId(): String = "b" + System.currentTimeMillis().toString(36) + (100..999).random()

    /** Le bozze, la piu' recente in alto. */
    fun elenco(ctx: Context): List<Voce> =
        (cartella(ctx).listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { f ->
                val b = try { Progetto.leggi(f.readText()) } catch (_: Exception) { null } ?: return@mapNotNull null
                Voce(f.name.removeSuffix(".txt"), b, f.lastModified())
            }
            .sortedByDescending { it.modificata }

    fun carica(ctx: Context, id: String): Bozza? =
        try { Progetto.leggi(file(ctx, id).readText()) } catch (_: Exception) { null }

    /** Scrittura atomica: file temporaneo accanto, col PID nel nome, poi rinomina. */
    fun salva(ctx: Context, id: String, b: Bozza): Boolean {
        val dest = file(ctx, id)
        val tmp = File(cartella(ctx), "$id.txt.${android.os.Process.myPid()}.tmp")
        return try {
            tmp.writeText(Progetto.scrivi(b))
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            true
        } catch (_: Exception) {
            tmp.delete(); false
        }
    }

    fun elimina(ctx: Context, id: String) { file(ctx, id).delete() }

    // ------------------------------------------------------------ bozze foto

    data class VoceFoto(val id: String, val bozza: BozzaFoto, val modificata: Long)

    private fun cartellaFoto(ctx: Context) = File(ctx.filesDir, "foto").also { it.mkdirs() }
    private fun fileFoto(ctx: Context, id: String) = File(cartellaFoto(ctx), "$id.txt")
    private fun copiaFoto(ctx: Context, id: String) = File(cartellaFoto(ctx), "$id.orig")

    fun elencoFoto(ctx: Context): List<VoceFoto> =
        (cartellaFoto(ctx).listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { f ->
                val b = try { Foto.leggiBozza(f.readText()) } catch (_: Exception) { null } ?: return@mapNotNull null
                VoceFoto(f.name.removeSuffix(".txt"), b, f.lastModified())
            }
            .sortedByDescending { it.modificata }

    fun caricaFoto(ctx: Context, id: String): BozzaFoto? =
        try { Foto.leggiBozza(fileFoto(ctx, id).readText()) } catch (_: Exception) { null }

    /** Scrittura atomica, come per i video. */
    fun salvaFoto(ctx: Context, id: String, b: BozzaFoto): Boolean {
        val dest = fileFoto(ctx, id)
        val tmp = File(cartellaFoto(ctx), "$id.txt.${android.os.Process.myPid()}.tmp")
        return try {
            tmp.writeText(Foto.scriviBozza(b))
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            true
        } catch (_: Exception) {
            tmp.delete(); false
        }
    }

    /** Via la bozza (e la copia interna, se c'e'). La foto nella galleria resta dov'e'. */
    fun eliminaFoto(ctx: Context, id: String) { fileFoto(ctx, id).delete(); copiaFoto(ctx, id).delete() }

    /**
     * La foto di partenza di una bozza nuova, rileggibile domani: col permesso duraturo se il
     * telefono lo da' (selettore); altrimenti (Condividi da un'altra app) una copia in files/foto,
     * che si guadagna il nome solo intera. null = non si legge.
     */
    fun fotoDa(ctx: Context, u: Uri, id: String): Uri? {
        try {
            ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return u
        } catch (_: Exception) {}
        val dest = copiaFoto(ctx, id)
        val part = File(cartellaFoto(ctx), "$id.orig.part")
        return try {
            val n = ctx.contentResolver.openInputStream(u)?.use { i -> part.outputStream().use { i.copyTo(it) } } ?: return null
            if (n <= 0L || part.length() != n || !part.renameTo(dest)) { part.delete(); null } else Uri.fromFile(dest)
        } catch (_: Exception) {
            part.delete(); null
        }
    }

    // ------------------------------------------------------------ bozze «Design»
    // files/grafica/<id>.txt; le foto messe nei design sono COPIE in files/grafica/media
    // (un design non si rompe se la foto sparisce dalla galleria).

    data class VoceGrafica(val id: String, val grafica: Grafica, val modificata: Long)

    private fun cartellaGrafica(ctx: Context) = File(ctx.filesDir, "grafica").also { it.mkdirs() }
    private fun fileGrafica(ctx: Context, id: String) = File(cartellaGrafica(ctx), "$id.txt")
    private fun media(ctx: Context) = File(cartellaGrafica(ctx), "media").also { it.mkdirs() }

    fun elencoGrafica(ctx: Context): List<VoceGrafica> =
        (cartellaGrafica(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { f ->
                val g = try { Grafiche.leggi(f.readText()) } catch (_: Exception) { null } ?: return@mapNotNull null
                VoceGrafica(f.name.removeSuffix(".txt"), g, f.lastModified())
            }
            .sortedByDescending { it.modificata }

    fun caricaGrafica(ctx: Context, id: String): Grafica? =
        try { Grafiche.leggi(fileGrafica(ctx, id).readText()) } catch (_: Exception) { null }

    /** Scrittura atomica, come per video e foto. */
    fun salvaGrafica(ctx: Context, id: String, g: Grafica): Boolean {
        val dest = fileGrafica(ctx, id)
        val tmp = File(cartellaGrafica(ctx), "$id.txt.${android.os.Process.myPid()}.tmp")
        return try {
            tmp.writeText(Grafiche.scrivi(g))
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            true
        } catch (_: Exception) {
            tmp.delete(); false
        }
    }

    fun eliminaGrafica(ctx: Context, id: String) { fileGrafica(ctx, id).delete() }

    // --- progetti Audio: files/suoni/<id>.txt (i suoni restano dove sono; le voci registrate in files/voci) ---
    data class VoceSuono(val id: String, val suono: ProgettoSuono, val modificata: Long)

    private fun cartellaSuoni(ctx: Context) = File(ctx.filesDir, "suoni").also { it.mkdirs() }
    private fun fileSuono(ctx: Context, id: String) = File(cartellaSuoni(ctx), "$id.txt")

    fun elencoSuoni(ctx: Context): List<VoceSuono> =
        (cartellaSuoni(ctx).listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { f ->
                val s = try { Suono.leggi(f.readText()) } catch (_: Exception) { null } ?: return@mapNotNull null
                VoceSuono(f.name.removeSuffix(".txt"), s, f.lastModified())
            }

    fun caricaSuono(ctx: Context, id: String): ProgettoSuono? =
        try { Suono.leggi(fileSuono(ctx, id).readText()) } catch (_: Exception) { null }

    /** Scrittura atomica: file temporaneo accanto, col PID nel nome, poi rinomina. */
    fun salvaSuono(ctx: Context, id: String, s: ProgettoSuono): Boolean {
        val dest = fileSuono(ctx, id)
        val tmp = File(cartellaSuoni(ctx), "$id.txt.${android.os.Process.myPid()}.tmp")
        return try {
            tmp.writeText(Suono.scrivi(s))
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            true
        } catch (_: Exception) {
            tmp.delete(); false
        }
    }

    fun eliminaSuono(ctx: Context, id: String) { fileSuono(ctx, id).delete() }

    /**
     * Una foto scelta per un design, copiata dentro l'app; si guadagna il nome solo intera.
     * Ritorna l'indirizzo della copia, null = non si legge.
     */
    fun importaFoto(ctx: Context, u: Uri): String? {
        val nome = "m" + System.currentTimeMillis().toString(36) + (1000..9999).random()
        val dest = File(media(ctx), nome)
        val part = File(media(ctx), "$nome.part")
        return try {
            val n = ctx.contentResolver.openInputStream(u)?.use { i -> part.outputStream().use { i.copyTo(it) } } ?: return null
            if (n <= 0L || part.length() != n || !part.renameTo(dest)) { part.delete(); null } else Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            part.delete(); null
        }
    }

    /**
     * Una pagina disegnata (PDF aperto) diventa una foto dell'app: JPEG in `.part`, poi il nome buono solo
     * se è scritto intero. null = non si scrive (spazio finito).
     */
    fun salvaPagina(ctx: Context, b: android.graphics.Bitmap): String? {
        val nome = "p" + System.currentTimeMillis().toString(36) + (1000..9999).random() + ".jpg"
        val dest = File(media(ctx), nome)
        val part = File(media(ctx), "$nome.part")
        return try {
            val ok = part.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            if (!ok || part.length() <= 0L || !part.renameTo(dest)) { part.delete(); null } else Uri.fromFile(dest).toString()
        } catch (_: Exception) {
            part.delete(); null
        }
    }

    /**
     * Via le copie di foto che nessun design usa più (tolte, sostituite, design eliminati).
     * Solo quelle più vecchie di un'ora: un editor ancora aperto può riaverle con Annulla.
     */
    fun pulisciMedia(ctx: Context) {
        val usate = elencoGrafica(ctx).flatMap { Grafiche.foto(it.grafica) }.mapNotNull { Uri.parse(it).lastPathSegment }.toSet()
        val limite = System.currentTimeMillis() - 3_600_000L
        media(ctx).listFiles()?.forEach { f -> if (f.name !in usate && f.lastModified() < limite) f.delete() }
    }

    data class Info(val durataMs: Long, val larghezza: Int, val altezza: Int)

    /** Durata e misura VISTA (rotazione applicata). null = non e' un video leggibile. */
    fun info(ctx: Context, uri: Uri): Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            Info(dur, w, h)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** La durata di un file audio (o del suono di un video). null = non si legge. */
    fun durataAudio(ctx: Context, uri: Uri): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Un fotogramma piccolo al punto [ms]; null se il telefono non ce la fa. */
    fun fotogramma(ctx: Context, uri: Uri, ms: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            piccolo(r, ms, 320)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** Un fotogramma che sta in [lato]×[lato]; Android 8.0 non sa rimpicciolire da solo. */
    fun piccolo(r: MediaMetadataRetriever, ms: Long, lato: Int): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 27) return r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, lato, lato)
        val b = r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val k = lato.toFloat() / maxOf(b.width, b.height)
        if (k >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true).also { if (it !== b) b.recycle() }
    }

    fun nome(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Clip"
    }

    /** Clip pronte dai video scelti; [saltate] = quanti non si leggono. Gira fuori dal thread principale. */
    fun clipDa(ctx: Context, uris: List<Uri>): Pair<List<Clip>, Int> {
        val nuove = mutableListOf<Clip>()
        var saltate = 0
        for (u in uris) {
            // il permesso di rileggerla domani; se il telefono non lo da', vale per oggi
            try { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val i = info(ctx, u)
            if (i == null || i.durataMs <= 0) { saltate++; continue }
            nuove += Clip(u.toString(), nome(ctx, u), i.durataMs)
        }
        return nuove to saltate
    }
}
