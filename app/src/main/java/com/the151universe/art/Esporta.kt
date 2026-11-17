package com.the151universe.art

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.OverlayEffect
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Calendar

/**
 * L'esportazione: le clip in fila, tagliate, con velocita', volume e dissolvenze; la musica
 * in una seconda fila (con buchi di silenzio dove non c'e'); i testi disegnati sopra con la
 * stessa ricetta dell'anteprima. Video: .mp4 nella misura della prima clip, in Movies/151 Editor.
 * [soloAudio]: lo stesso suono in un .m4a, in Music/151 Editor.
 * Il file nasce in cache col nome provvisorio e entra in galleria solo se finito.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class Esporta(
    private val ctx: Context,
    private val bozza: Bozza,
    private val larghezza: Int,
    private val altezza: Int,
    private val soloAudio: Boolean,
    /** le clip senza suono: nel «solo audio» diventano silenzio della stessa durata */
    private val mute: Set<Int> = emptySet(),
    /** un progetto Audio: solo i suoi suoni, una fila per corsia (allora [soloAudio] e niente clip); il file prende il suo nome */
    private val suono: ProgettoSuono? = null,
    /** il formato del file audio (solo con [suono]); M4A esce dal Transformer, gli altri da [Presa] + [Codifica] */
    private val formato: FormatoSuono = FormatoSuono.M4A,
    private val avanzamento: (Int) -> Unit,
    fineGrezza: (Esito) -> Unit,
) {
    // l'esito arriva una volta sola, anche se annullare e errore si incrociano
    private var chiuso = false
    private val fine: (Esito) -> Unit = { e -> if (!chiuso) { chiuso = true; fineGrezza(e) } }

    sealed class Esito {
        data class Fatto(val nome: String, val uri: Uri) : Esito()
        data class Errore(val perche: String) : Esito()
        data object Fermato : Esito()
    }

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var fermato = false
    private val provvisorio = File(ctx.cacheDir, if (soloAudio) "esportazione.part.m4a" else "esportazione.part.mp4")
    private val conPresa = suono != null && formato != FormatoSuono.M4A
    private val pcm = File(ctx.cacheDir, "esportazione.part.pcm")
    private val codificato = File(ctx.cacheDir, "esportazione.part.${formato.est}")
    private val presa: Presa? = if (conPresa) Presa(pcm) else null

    private fun taglio(uri: String, da: Long, a: Long) = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(da).setEndPositionMs(a).build())
        .build()

    /** La composizione intera: anche la prova sul PC la costruisce da qui. */
    fun composizione(): Composition {
        suono?.let { return composizioneSuoni(it) }
        val clip = bozza.clip
        val pezzi = clip.mapIndexed { i, c ->
            val b = EditedMediaItem.Builder(taglio(c.uri, c.inizioMs, c.fineMs))
                .setEffects(Effects(listOf(Guadagno { t -> Progetto.guadagnoClip(bozza, i, t) }), listOf()))
            // il tono resta quello vero (come nell'anteprima): niente voce da criceto a 2×
            if (c.velocita != 1f) b.setSpeed(SpeedParameters(object : SpeedProvider {
                override fun getSpeed(timeUs: Long) = c.velocita
                override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
            }, true))
            if (soloAudio) b.setRemoveVideo(true)
            b.build()
        }
        // la fila dichiara audio E video: una clip muta in mezzo diventa silenzio, non un errore
        val fila = if (!soloAudio) EditedMediaItemSequence.withAudioAndVideoFrom(pezzi) else {
            // senza video, una clip muta non avrebbe tracce: al suo posto un silenzio lungo uguale
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            pezzi.forEachIndexed { i, p -> if (i in mute) b.addGap(clip[i].vistaMs * 1000) else b.addItem(p) }
            b.build()
        }
        val sequenze = mutableListOf(fila)
        musica()?.let { sequenze += it }
        val c = Composition.Builder(sequenze)
        if (!soloAudio) {
            val (w, h) = Progetto.misuraUscita(larghezza, altezza)
            val effetti = mutableListOf<androidx.media3.common.Effect>(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
            if (bozza.scritte.isNotEmpty()) effetti += OverlayEffect(listOf(Scritte(ctx, bozza.scritte, w, h)))
            // le transizioni «verso il nero»: dopo i testi, così sfumano anche loro (come nell'anteprima)
            if (bozza.clip.any { it.entra || it.esce }) effetti += Nero(bozza.clip)
            c.setEffects(Effects(listOf(), effetti))
            // i Samsung girano anche in HDR: il video finito e' normale (SDR), cosi' si vede ovunque
            c.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        return c.build()
    }

    /**
     * Il progetto Audio: le file di [Suono.file], una per corsia, lunghe uguali, mescolate dal
     * Transformer; ogni pezzo col suo volume e le sue dissolvenze (la stessa curva dell'anteprima).
     */
    private fun composizioneSuoni(s: ProgettoSuono): Composition {
        val sequenze = Suono.file(s.pezzi).map { fila ->
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            for (passo in fila) when (passo) {
                is Suono.Passo.Silenzio -> b.addGap(passo.ms * 1000)
                is Suono.Passo.Pezzo -> { val m = passo.m
                    val e = EditedMediaItem.Builder(taglio(m.uri, m.inizioMs, m.fineMs))
                        .setRemoveVideo(true)
                        .setEffects(Effects(listOf(Guadagno { t -> Suono.guadagno(s, m, t) }), listOf()))
                    // la velocità col tono vero, come le clip del video (e come l'anteprima)
                    if (m.velocita != 1f) e.setSpeed(SpeedParameters(object : SpeedProvider {
                        override fun getSpeed(timeUs: Long) = m.velocita
                        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
                    }, true))
                    b.addItem(e.build()) }
            }
            b.build()
        }
        val c = Composition.Builder(sequenze)
        // il suono mescolato, preso prima dell'encoder: da lì nascono MP3, WAV e FLAC
        presa?.let { c.setEffects(Effects(listOf(it), listOf())) }
        return c.build()
    }

    /** La fila della musica: silenzio, pezzo, silenzio, pezzo… mai oltre la fine del video. */
    private fun musica(): EditedMediaItemSequence? {
        val tot = Progetto.totaleMs(bozza.clip)
        val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var dove = 0L
        var qualcosa = false
        for (m in bozza.musica.sortedBy { it.posMs }) {
            if (m.posMs >= tot) break
            val fine = minOf(m.fineMs, m.inizioMs + (tot - m.posMs))
            if (fine - m.inizioMs < 50) continue
            if (m.posMs > dove) b.addGap((m.posMs - dove) * 1000)
            val pezzo = m.copy(fineMs = fine)
            b.addItem(EditedMediaItem.Builder(taglio(m.uri, m.inizioMs, fine))
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(Guadagno { t -> Progetto.guadagnoMusica(pezzo, t) }), listOf()))
                .build())
            dove = m.posMs + (fine - m.inizioMs)
            qualcosa = true
        }
        return if (qualcosa) b.build() else null
    }

    fun parti() {
        provvisorio.delete(); pcm.delete(); codificato.delete()
        val composition = composizione()

        val t = Transformer.Builder(ctx)
            .also { if (!soloAudio) it.setVideoMimeType(MimeTypes.VIDEO_H264) }
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    Thread { finisci() }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    presa?.chiudi()
                    provvisorio.delete(); pcm.delete(); codificato.delete()
                    fine(if (fermato) Esito.Fermato else Esito.Errore(exportException.errorCodeName))
                }
            })
            .build()
        transformer = t
        t.start(composition, provvisorio.absolutePath)
        main.post(object : Runnable {
            val ph = ProgressHolder()
            override fun run() {
                val tt = transformer ?: return
                // con un formato da codificare dopo, il mescolare è la prima parte (fino al 60%)
                if (tt.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) avanzamento(if (conPresa) ph.progress * 60 / 100 else ph.progress)
                main.postDelayed(this, 300)
            }
        })
    }

    fun ferma() {
        fermato = true
        transformer?.cancel()
        transformer = null
        provvisorio.delete()
        if (presa == null) fine(Esito.Fermato)
        // con la codifica in corso il filo la vede ferma e pulisce lui; senza, si pulisce qui
        else main.post { if (!chiuso) { presa.chiudi(); pcm.delete(); codificato.delete(); fine(Esito.Fermato) } }
    }

    /** Dalla cache alla galleria; gira fuori dal thread principale. */
    private fun finisci() {
        val esito = try {
            if (fermato) Esito.Fermato
            else if (!provvisorio.exists() || provvisorio.length() == 0L) Esito.Errore("empty file")
            else {
                val sorgente = presa?.let { p ->
                    p.chiudi()
                    p.errore?.let { throw IllegalStateException(it) }
                    check(p.hz > 0 && pcm.length() > 0) { "no sound captured" }
                    Codifica.scrivi(formato, pcm, p.hz, p.canali, codificato,
                        avanti = { q -> main.post { if (!chiuso) avanzamento(60 + q * 40 / 100) } }, fermato = { fermato })
                    if (fermato) return@let null
                    check(codificato.length() > 0) { "empty ${formato.nome}" }
                    codificato
                } ?: provvisorio
                if (fermato) throw Fermo()
                val k = Calendar.getInstance()
                val nome = Progetto.nomeFile(
                    k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH),
                    k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), k.get(Calendar.SECOND),
                ).let { if (soloAudio) it.removeSuffix(".mp4") + "." + (if (suono != null) formato.est else "m4a") else it }
                    .let { n -> suono?.let { Suono.nomeFile(it.nome, n) } ?: n }
                Esito.Fatto(nome, inGalleria(nome, sorgente))
            }
        } catch (_: Fermo) {
            Esito.Fermato
        } catch (e: Exception) {
            Esito.Errore(e.message ?: e.javaClass.simpleName)
        } finally {
            provvisorio.delete(); pcm.delete(); codificato.delete()
        }
        main.post { fine(esito) }
    }

    private class Fermo : Exception()

    /** Il mime del file che va in galleria. */
    val mime: String get() = if (!soloAudio) "video/mp4" else if (suono != null) formato.mime else "audio/mp4"

    private fun inGalleria(nome: String, file: File): Uri {
        val cr = ctx.contentResolver
        // le colonne DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING, DATA sono le stesse per video e audio
        val cartella = if (soloAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nome)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$cartella/151 Editor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(cartella), "151 Editor")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, nome).absolutePath)
            }
        }
        val dove = if (soloAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = cr.insert(dove, v)
            ?: throw IllegalStateException("gallery refused the file")
        try {
            val scritti = cr.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("gallery refused the file")
            if (scritti != file.length()) throw IllegalStateException("copy incomplete")
            if (Build.VERSION.SDK_INT >= 29) {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }
}

/**
 * I testi e i disegni sopra il video, per l'export: una tela trasparente grande quanto il video, rifatta
 * solo quando cambia quali si vedono. Stessa ricetta dell'anteprima ([ManoDisegno], [Disegno.testi]).
 */
@OptIn(UnstableApi::class)
class Scritte(private val ctx: Context, private val scritte: List<Scritta>, private val w: Int, private val h: Int) :
    androidx.media3.effect.BitmapOverlay() {
    private var chiave: List<Long>? = null
    private var tela: android.graphics.Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): android.graphics.Bitmap {
        // nell'ordine dei livelli: prima quelli dietro, per ultima la riga 0
        val visibili = Progetto.scritteDaDisegnare(scritte, presentationTimeUs / 1000)
        val k = visibili.map { it.testo.id }
        val t = tela
        if (t != null && k == chiave) return t
        val nuova = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(nuova)
        for (sc in visibili) {
            ManoDisegno.disegna(cv, sc.tratti, w.toFloat(), h.toFloat())
            Disegno.testi(ctx, cv, listOf(sc.testo), w.toFloat(), h.toFloat())
        }
        tela = nuova; chiave = k
        return nuova
    }
}

/**
 * Il nero delle transizioni nel file: a ogni fotogramma il colore si moltiplica per (1 - nero),
 * con lo stesso conto dell'anteprima ([Progetto.nero]). Il tempo è quello del video finito.
 */
@OptIn(UnstableApi::class)
class Nero(private val clip: List<Clip>) : androidx.media3.effect.RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val k = 1f - Progetto.nero(clip, presentationTimeUs / 1000)
        // colonne: R, G, B, A (matrice 4×4 per colonne, come vuole OpenGL)
        return floatArrayOf(k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 1f)
    }
}
