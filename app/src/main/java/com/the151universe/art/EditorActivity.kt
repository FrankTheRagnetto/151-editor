package com.the151universe.art

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * L'editor video: orizzontale, a tutto schermo. A sinistra
 * indietro/annulla/rifai; al centro il video, sotto tempo, Play ed Export; a destra
 * l'ispettore che mostra SOLO la cosa scelta (niente / clip / musica / testo); sotto la
 * timeline a tre corsie. Ogni gesto passa dalla [Storia] e si salva subito nella bozza.
 *
 * L'anteprima: un lettore per le clip (tagliate, alla loro velocita'), uno per la musica
 * tenuto al passo dall'orologio; il volume lo fa un [Guadagno] dentro ciascuno, cosi'
 * si sente anche sopra il 100% e le dissolvenze si sentono come nell'export.
 */
@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity(), Timeline.Ascoltatore, ScritteVista.Ascoltatore {
    companion object {
        const val ID = "id"
        /** Quanto si vede un bottone spento: lo stesso 38% di res/color/attrezzo.xml. */
        const val SPENTO = 0.38f
        /** Quanto dura un testo nuovo. */
        const val TESTO_MS = 3000L
        /** un fotogramma a 30 al secondo: il passo dei bottoni ‹ › */
        const val PASSO_MS = 33L
        private const val CERCA_PIANO_MS = 400L
        private const val ATTESA_CERCA_MS = 300L
        /** Quanto in anticipo si calcola il volume dell'anteprima: circa il buffer del suono. */
        const val AVANTI_MS = 150L
    }

    private lateinit var id: String
    private lateinit var storia: Storia<Bozza>
    /** quello che si vede: uguale a storia.adesso, salvo mentre un dito sta ancora muovendo qualcosa */
    private var vivo = Bozza("", emptyList())
    private val clip get() = vivo.clip
    private var scelta = Timeline.Scelta.NESSUNA

    private lateinit var player: ExoPlayer
    private lateinit var musica: ExoPlayer
    private val guadagnoClip = Guadagno()
    private val guadagnoMusica = Guadagno()
    private var musicaCaricata: String? = null
    private lateinit var timeline: Timeline
    private lateinit var scritteVista: ScritteVista
    private lateinit var opzioniStile: OpzioniStile
    private lateinit var lavagna: LavagnaVista
    private lateinit var nero: View
    /** la penna è accesa: l'ispettore mostra i suoi bottoni */
    private var disegnando = false
    /** il disegno che la penna sta facendo (id del suo testo vuoto); null = il primo tratto ne fa uno alla riga */
    private var disegnoId: Long? = null
    private lateinit var tempo: TextView
    private lateinit var suona: ImageButton
    private val main = Handler(Looper.getMainLooper())
    private val lavoro = Executors.newSingleThreadExecutor()
    private var esportazione: Esporta? = null
    private var anteprimaTaglio = false
    private var misuraVideo: Pair<Int, Int>? = null
    private val dp by lazy { resources.displayMetrics.density }

    private val scegliVideo = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lavoro.execute {
            val (nuove, saltate) = Archivio.clipDa(this, uris)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (nuove.isEmpty()) { avviso("These videos can't be read."); return@runOnUiThread }
                val dove = if (clip.isEmpty()) 0 else attuale() + 1
                val c = clip.toMutableList().also { it.addAll(dove, nuove) }
                fai(vivo.copy(clip = c), Timeline.Scelta.NESSUNA, Progetto.inizioGlobale(c, dove))
                if (saltate > 0) avviso("$saltate video could not be read and were skipped.")
            }
        }
    }

    /** La musica: qualsiasi file audio del telefono. [sostituisci] = indice da cambiare, -1 = nuova. */
    private var sostituisci = -1
    private val scegliMusica = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        val qui = timeline.t
        val dove = sostituisci
        lavoro.execute {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val dur = Archivio.durataAudio(this, u)
            val nome = Archivio.nome(this, u).substringBeforeLast('.')
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (dur == null) { avviso("This file has no sound 151 Editor can read."); return@runOnUiThread }
                val nuova = Musica(u.toString(), nome, dur)
                val vecchia = vivo.musica.getOrNull(dove)
                if (vecchia != null) {
                    // stessa posizione e regolazioni, al massimo lunga quanto prima
                    val m = vivo.musica.toMutableList()
                    m[dove] = vecchia.copy(uri = nuova.uri, nome = nome, durataMs = dur, inizioMs = 0,
                        fineMs = minOf(dur, vecchia.lunghezzaMs))
                    fai(vivo.copy(musica = m), Timeline.Scelta(Timeline.Tipo.MUSICA, dove), qui)
                    return@runOnUiThread
                }
                val m = Progetto.mettiMusica(vivo.musica, nuova, qui)
                if (m == null) { avviso("There's already music at the line. Move the line to an empty spot."); return@runOnUiThread }
                val i = m.indexOfFirst { it.posMs == qui.coerceAtLeast(0) && it.uri == nuova.uri }
                fai(vivo.copy(musica = m), Timeline.Scelta(Timeline.Tipo.MUSICA, i), qui)
            }
        }
    }

    private var esportaAudio = false
    private val permessoVecchio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) esporta(esportaAudio) else avviso("151 Editor needs storage access to save on this phone.")
    }

    private val orologio = object : Runnable {
        override fun run() {
            if (!anteprimaTaglio && player.isPlaying) timeline.posizione(adesso())
            sincronizza()
            scriviTempo()
            main.postDelayed(this, 33)
        }
    }

    /** Un lettore col nostro [Guadagno] in fondo alla catena del suono. */
    private fun lettore(g: Guadagno): ExoPlayer {
        val fabbrica = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder(context)
                    // buffer corto (~150 ms): il volume che cambia si sente quasi subito
                    .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(100_000).setTargetPcmBufferDurationUs(150_000)
                        .setMaxPcmBufferDurationUs(200_000).build())
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(g))
                    .build()
        }
        return ExoPlayer.Builder(this, fabbrica).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        // a tutto schermo: l'altezza va al video e alla timeline; le barre tornano con un gesto dal bordo
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        id = intent.getStringExtra(ID) ?: return finish()
        val b = Archivio.carica(this, id) ?: return finish()
        storia = Storia(b); vivo = b

        tempo = findViewById(R.id.tempo)
        suona = findViewById(R.id.suona)
        timeline = findViewById(R.id.timeline)
        timeline.ascoltatore = this
        scritteVista = findViewById(R.id.scritte_vista)
        scritteVista.ascoltatore = this
        lavagna = findViewById(R.id.lavagna)
        nero = findViewById(R.id.nero_vista)
        lavagna.cornice = { scritteVista.cornice() }
        lavagna.ascoltatore = LavagnaVista.Ascoltatore { t, fine -> inchiostro(t, fine) }
        findViewById<ScrollView>(R.id.p_mano).addView(PannelloMano(this, lavagna) { finisciDisegno() }.vista)

        findViewById<TextView>(R.id.nome_progetto).text = vivo.nome.ifBlank { "Video" }
        findViewById<View>(R.id.indietro).setOnClickListener { finish() }
        // il tasto indietro del telefono: prima «Done» (penna, cosa scelta), poi esce
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    numero != null -> chiudiTastierino(false)
                    disegnando -> finisciDisegno()
                    scelta.tipo != Timeline.Tipo.NIENTE -> fatto()
                    else -> finish()
                }
            }
        })
        findViewById<View>(R.id.annulla).setOnClickListener { if (storia.annulla()) dopoStoria() }
        findViewById<View>(R.id.rifai).setOnClickListener { if (storia.rifai()) dopoStoria() }
        findViewById<View>(R.id.esporta).setOnClickListener { sceltaEsporta() }
        preparaIspettore()

        player = lettore(guadagnoClip)
        findViewById<PlayerView>(R.id.lettore).player = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                suona.setImageResource(if (isPlaying) R.drawable.ic_pausa else R.drawable.ic_play)
                suona.contentDescription = if (isPlaying) "Pause" else "Play"
                if (!isPlaying) musica.pause()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !anteprimaTaglio) {
                    player.pause()
                    timeline.posizione(Progetto.totaleMs(clip))
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { velocitaGiusta() }
            // il fotogramma del salto è sullo schermo: può partire il prossimo (scorrere col dito)
            override fun onRenderedFirstFrame() { if (cercando) { main.removeCallbacks(sbloccaCerca); sbloccaCerca.run() } }
        })
        musica = lettore(guadagnoMusica)
        suona.setOnClickListener { suonaOFerma() }
        ripeti(findViewById(R.id.passo_indietro)) { passo(-1) }
        ripeti(findViewById(R.id.passo_avanti)) { passo(+1) }

        timeline.imposta(vivo, scelta)
        ricostruisci(0)
        misuraDelVideo()
        aggiornaIspettore()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(orologio)
        main.post(orologio)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(orologio)
        player.pause(); musica.pause()
    }

    override fun onDestroy() {
        main.removeCallbacks(orologio)
        esportazione?.ferma()
        timeline.chiudi()
        if (::player.isInitialized) player.release()
        if (::musica.isInitialized) musica.release()
        lavoro.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- stato

    /** La clip su cui lavorano gli attrezzi: quella scelta, se no quella sotto la riga. */
    private fun attuale(): Int =
        if (scelta.tipo == Timeline.Tipo.CLIP && scelta.i in clip.indices) scelta.i
        else Progetto.dove(clip, timeline.t)?.first ?: 0

    /** Un gesto finito: entra nella storia, si salva, tutto si rifa'. */
    private fun fai(nuova: Bozza, s: Timeline.Scelta, riga: Long) {
        val clipCambiate = nuova.clip != storia.adesso.clip
        storia.fai(nuova)
        vivo = storia.adesso
        Archivio.salva(this, id, vivo)
        scelta = s
        timeline.imposta(vivo, scelta)
        if (clipCambiate) { ricostruisci(riga); misuraDelVideo() } else velocitaGiusta()
        aggiornaIspettore(); aggiornaBottoni()
    }

    /** Un gesto ancora in corso (cursore, trascinamento): si vede e si sente, ma non entra nella storia. */
    private fun anteprima(nuova: Bozza) {
        vivo = nuova
        timeline.imposta(vivo, scelta)
        aggiornaIspettore()
    }

    private fun dopoStoria() {
        val clipCambiate = storia.adesso.clip != vivo.clip
        // annulla/rifai non tolgono la scelta: un testo si ritrova dal suo id, clip e musica dal posto
        val idTesto = if (scelta.tipo == Timeline.Tipo.TESTO) vivo.scritte.getOrNull(scelta.i)?.testo?.id else null
        vivo = storia.adesso
        Archivio.salva(this, id, vivo)
        val riga = timeline.t
        scelta = when (scelta.tipo) {
            Timeline.Tipo.CLIP -> if (scelta.i in vivo.clip.indices) scelta else Timeline.Scelta.NESSUNA
            Timeline.Tipo.MUSICA -> if (scelta.i in vivo.musica.indices) scelta else Timeline.Scelta.NESSUNA
            Timeline.Tipo.TESTO -> vivo.scritte.indexOfFirst { it.testo.id == idTesto }
                .let { if (it >= 0) Timeline.Scelta(Timeline.Tipo.TESTO, it) else Timeline.Scelta.NESSUNA }
            else -> Timeline.Scelta.NESSUNA
        }
        timeline.imposta(vivo, scelta)
        if (clipCambiate) { ricostruisci(riga); misuraDelVideo() }
        aggiornaIspettore(); aggiornaBottoni()
        if (clip.isEmpty()) avviso("Nothing left: add a clip from the left")
    }

    /** «Done»: la cosa scelta si lascia andare. */
    private fun fatto() {
        scelta = Timeline.Scelta.NESSUNA; timeline.scegli(scelta); aggiornaIspettore()
    }

    private fun ricostruisci(riga: Long) {
        anteprimaTaglio = false
        player.pause()
        player.setSeekParameters(SeekParameters.EXACT)
        aggiornaBottoni()
        if (clip.isEmpty()) { player.clearMediaItems(); timeline.posizione(0); return }
        val items = clip.map { c ->
            MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.inizioMs).setEndPositionMs(c.fineMs).build()
            ).build()
        }
        val tot = Progetto.totaleMs(clip)
        val r = riga.coerceIn(0, tot)
        val (i, nelFile) = Progetto.dove(clip, r) ?: (0 to 0L)
        player.setMediaItems(items, i, nelFile - clip[i].inizioMs)
        player.prepare()
        velocitaGiusta()
        timeline.posizione(r)
    }

    /** La misura del video finito (quella della prima clip): serve ai testi e alla testa del progetto. */
    private fun misuraDelVideo() {
        val primo = clip.firstOrNull() ?: return
        lavoro.execute {
            val info = Archivio.info(this, Uri.parse(primo.uri)) ?: return@execute
            val m = Progetto.misuraUscita(info.larghezza, info.altezza)
            runOnUiThread {
                misuraVideo = m
                scritteVista.misura(m.first, m.second)
                timeline.formato(m.first, m.second)
                aggiornaIspettore()
            }
        }
    }

    private fun aggiornaBottoni() {
        findViewById<View>(R.id.esporta).isEnabled = clip.isNotEmpty()
        findViewById<View>(R.id.annulla).apply { isEnabled = storia.puoiAnnullare; alpha = if (isEnabled) 1f else SPENTO }
        findViewById<View>(R.id.rifai).apply { isEnabled = storia.puoiRifare; alpha = if (isEnabled) 1f else SPENTO }
    }

    // ---------------------------------------------------------------- tempo e suono

    private fun indiceLettore() = player.currentMediaItemIndex.coerceIn(0, (clip.size - 1).coerceAtLeast(0))

    /** Dove siamo nel video finito, secondo il lettore (la velocita' accorcia il tempo). */
    private fun adesso(): Long {
        if (clip.isEmpty()) return 0
        val i = indiceLettore()
        return Progetto.inizioGlobale(clip, i) + (player.currentPosition.coerceAtLeast(0) / clip[i].velocita).roundToInt()
    }

    private fun vaiA(globale: Long) {
        val (i, nelFile) = Progetto.dove(clip, globale) ?: return
        player.seekTo(i, nelFile - clip[i].inizioMs)
        velocitaGiusta()
    }

    private fun velocitaGiusta() {
        if (clip.isEmpty() || anteprimaTaglio) return
        val v = clip[indiceLettore()].velocita
        if (player.playbackParameters.speed != v) player.playbackParameters = PlaybackParameters(v)
    }

    /**
     * Ogni 33 ms: il volume della clip (volume, dissolvenze), la musica
     * al passo col video, i testi del momento. Le stesse formule dell'export ([Progetto]).
     */
    private fun sincronizza() {
        val t = if (player.isPlaying) adesso() else timeline.t
        scritteVista.mostra(vivo.scritte, t)
        nero.alpha = Progetto.nero(clip, t)
        if (clip.isEmpty() || anteprimaTaglio) { musica.pause(); return }
        // il suono preparato adesso si sente fra ~AVANTI ms: il volume si calcola per quel momento
        val ta = if (player.isPlaying) (t + AVANTI_MS).coerceAtMost(Progetto.totaleMs(clip) - 1) else t
        val i = Progetto.dove(clip, ta)?.first ?: indiceLettore()
        guadagnoClip.adesso = Progetto.guadagnoClip(vivo, i, ta - Progetto.inizioGlobale(clip, i))
        val m = vivo.musica.firstOrNull { t >= it.posMs && t < it.fineGlobaleMs }
        if (m == null || !player.isPlaying) { if (musica.playWhenReady) musica.pause(); return }
        if (musicaCaricata != m.uri) {
            musica.setMediaItem(MediaItem.fromUri(m.uri)); musica.prepare(); musicaCaricata = m.uri
        }
        guadagnoMusica.adesso = Progetto.guadagnoMusica(m, (ta - m.posMs).coerceAtMost(m.lunghezzaMs))
        val voluto = m.inizioMs + (t - m.posMs)
        // parte una volta; dopo si corregge solo se e' pronta e scivolata (mai a ogni giro: resterebbe a caricare)
        if (!musica.playWhenReady) { musica.seekTo(voluto); musica.play() }
        else if (musica.playbackState == Player.STATE_READY && abs(musica.currentPosition - voluto) > 250) musica.seekTo(voluto)
    }

    private var tempoScritto = ""

    private fun scriviTempo() {
        val nuovo = "${Progetto.tempo(timeline.t)} / ${Progetto.tempo(Progetto.totaleMs(clip))}"
        if (nuovo != tempoScritto) { tempoScritto = nuovo; tempo.text = nuovo }
    }

    private fun suonaOFerma() {
        if (clip.isEmpty()) return
        if (anteprimaTaglio) ricostruisci(timeline.t)
        if (player.isPlaying) { player.pause(); return }
        if (timeline.t >= Progetto.totaleMs(clip) - 50) { timeline.posizione(0); vaiA(0) }
        player.setSeekParameters(SeekParameters.EXACT)
        velocitaGiusta()
        player.play()
    }

    private fun avviso(t: String) {
        if (t.isNotEmpty()) Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_SHORT).show()
    }

    /** Dopo un Delete: l'avviso ha dentro «Undo». */
    private fun avvisoAnnulla(t: String) {
        Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_LONG)
            .setAction("Undo") { if (storia.annulla()) dopoStoria() }.show()
    }

    // ---------------------------------------------------------------- la timeline parla

    override fun scorri(t: Long, dalDito: Boolean) {
        if (anteprimaTaglio) ricostruisci(t)
        if (player.isPlaying) player.pause()
        cerca(t)
    }

    override fun fermo(t: Long) {
        main.removeCallbacks(sbloccaCerca); cercando = false; voluto = null
        player.setSeekParameters(SeekParameters.EXACT)
        vaiA(t)
        aggiornaIspettore()
    }

    // --- scorrere col dito: l'immagine segue il dito ---
    // Un salto alla volta: il prossimo parte quando il fotogramma di questo è sullo schermo
    // (onRenderedFirstFrame) o dopo [ATTESA_CERCA_MS]. Prima ogni movimento cancellava il salto
    // di prima e l'immagine arrivava solo a dito fermo.
    private var cercando = false
    private var voluto: Long? = null
    private var ultimoCercato = 0L
    private val sbloccaCerca = Runnable { cercando = false; voluto?.let { cerca(it) } }

    private fun cerca(t: Long) {
        if (cercando) { voluto = t; return }
        voluto = null
        // piano = il fotogramma esatto (per i tagli fini); veloce = il fotogramma chiave più vicino
        val piano = abs(t - ultimoCercato) < CERCA_PIANO_MS
        player.setSeekParameters(if (piano) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
        ultimoCercato = t
        cercando = true
        vaiA(t)
        main.removeCallbacks(sbloccaCerca); main.postDelayed(sbloccaCerca, ATTESA_CERCA_MS)
    }

    /** Un fotogramma avanti (+1) o indietro (-1): per i tagli fini. */
    private fun passo(verso: Int) {
        if (clip.isEmpty()) return
        if (anteprimaTaglio) ricostruisci(timeline.t)
        player.pause()
        val n = (timeline.t + verso * PASSO_MS).coerceIn(0, (Progetto.totaleMs(clip) - 1).coerceAtLeast(0))
        timeline.posizione(n)
        player.setSeekParameters(SeekParameters.EXACT)
        vaiA(n)
        scriviTempo(); aggiornaIspettore()
    }

    /** Tocco = un passo; tenuto premuto = passi di fila. */
    private fun ripeti(v: View, via: () -> Unit) {
        val giro = object : Runnable { override fun run() { via(); main.postDelayed(this, 70) } }
        v.setOnTouchListener { b, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { b.isPressed = true; via(); main.postDelayed(giro, 380) }
                android.view.MotionEvent.ACTION_UP -> { b.isPressed = false; main.removeCallbacks(giro); b.performClick() }
                android.view.MotionEvent.ACTION_CANCEL -> { b.isPressed = false; main.removeCallbacks(giro) }
            }
            true
        }
        v.setOnClickListener { }
    }

    override fun scegli(s: Timeline.Scelta) {
        scelta = s
        disegnando = false; numero = null
        aggiornaIspettore()
    }

    override fun taglio(i: Int, inizio: Long, fine: Long, sinistro: Boolean, finito: Boolean) {
        val c = clip.getOrNull(i) ?: return
        if (finito && inizio == c.inizioMs && fine == c.fineMs) {
            if (anteprimaTaglio) ricostruisci(timeline.t)
            return
        }
        if (!finito) {
            if (!anteprimaTaglio) {
                anteprimaTaglio = true
                player.pause()
                player.playbackParameters = PlaybackParameters(1f)
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                player.setMediaItem(MediaItem.fromUri(c.uri))
                player.prepare()
            }
            player.seekTo(if (sinistro) inizio else (fine - 1).coerceAtLeast(0))
            return
        }
        val nuove = clip.toMutableList().also { it[i] = Progetto.taglia(c, inizio, fine) }
        val riga = if (sinistro) Progetto.inizioGlobale(nuove, i)
        else Progetto.inizioGlobale(nuove, i) + nuove[i].vistaMs - 1
        fai(vivo.copy(clip = nuove), Timeline.Scelta(Timeline.Tipo.CLIP, i), riga)
    }

    override fun sposta(da: Int, a: Int) {
        val nuove = Progetto.sposta(clip, da, a)
        fai(vivo.copy(clip = nuove), Timeline.Scelta(Timeline.Tipo.CLIP, a), Progetto.inizioGlobale(nuove, a))
    }

    override fun bordo(tipo: Timeline.Tipo, i: Int, sinistro: Boolean, delta: Long, finito: Boolean) {
        val base = storia.adesso
        val nuova = when (tipo) {
            Timeline.Tipo.MUSICA -> base.copy(musica = Progetto.bordoMusica(base.musica, i, sinistro, delta))
            Timeline.Tipo.TESTO -> base.copy(scritte = Progetto.bordoScritta(base.scritte, i, sinistro, delta))
            else -> return
        }
        if (finito) fai(nuova, scelta, timeline.t) else anteprima(nuova)
    }

    override fun trascina(tipo: Timeline.Tipo, i: Int, delta: Long, finito: Boolean) {
        val base = storia.adesso
        val (nuova, nuovoI) = when (tipo) {
            Timeline.Tipo.MUSICA -> {
                val m = Progetto.spostaMusica(base.musica, i, base.musica[i].posMs + delta)
                base.copy(musica = m) to i // spostare non scavalca mai i vicini: l'ordine resta
            }
            Timeline.Tipo.TESTO -> {
                val s = Progetto.spostaScritta(base.scritte, i, base.scritte[i].inizioMs + delta)
                base.copy(scritte = s) to i
            }
            else -> return
        }
        scelta = Timeline.Scelta(tipo, nuovoI)
        if (finito) fai(nuova, scelta, timeline.t) else anteprima(nuova)
    }

    override fun aggiungiDisegno() = disegna(null)

    /** Il tondo fra due clip: si sceglie, e il pannello mostra la transizione. */
    override fun transizione(k: Int) {
        if (clip.isEmpty()) return
        player.pause()
        disegnando = false; numero = null
        scelta = Timeline.Scelta(Timeline.Tipo.TRANSIZIONE, k)
        timeline.scegli(scelta)
        aggiornaIspettore()
    }

    /** Un testo trascinato su o giù nella corsia: il suo livello. */
    override fun riga(i: Int, r: Int) {
        val id = vivo.scritte.getOrNull(i)?.testo?.id ?: return
        val n = Progetto.cambiaRiga(storia.adesso.scritte, i, r)
        fai(vivo.copy(scritte = n), Timeline.Scelta(Timeline.Tipo.TESTO, n.indexOfFirst { it.testo.id == id }), timeline.t)
    }

    override fun registra() = registraVoce()

    // ---------------------------------------------------------------- registrare la voce
    // Dalla riga bianca, col video che scorre MUTO (si parla guardandolo). Il file sta nell'app
    // (files/voci) ed entra nella corsia dell'audio come una musica qualsiasi.

    private val permessoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) registraVoce() else avviso("151 Editor needs the microphone to record your voice.")
    }

    private fun registraVoce() {
        if (clip.isEmpty()) { avviso("Add a video first"); return }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permessoMicrofono.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        val qui = timeline.t
        if (vivo.musica.any { qui >= it.posMs && qui < it.fineGlobaleMs }) {
            avviso("There's already audio at the line. Move the line to an empty spot."); return
        }
        val file = java.io.File(java.io.File(filesDir, "voci").apply { mkdirs() }, "voce-${System.currentTimeMillis()}.m4a")
        val rec = try {
            @Suppress("DEPRECATION")
            (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000); setAudioSamplingRate(44_100); setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
        } catch (_: Exception) { file.delete(); avviso("The microphone didn't start. Try again."); return }
        val volVideo = player.volume; val volMusica = musica.volume
        player.volume = 0f; musica.volume = 0f
        vaiA(qui); player.play()
        val partito = android.os.SystemClock.elapsedRealtime()
        val orologioVoce = TextView(this).apply {
            textSize = 34f; gravity = android.view.Gravity.CENTER; fontFeatureSettings = "tnum"
            setTextColor(ContextCompat.getColor(context, R.color.accento))
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val tic = object : Runnable {
            override fun run() { orologioVoce.text = "● " + Progetto.tempo(android.os.SystemClock.elapsedRealtime() - partito); main.postDelayed(this, 100) }
        }
        tic.run()
        var finito = false
        fun fine(tieni: Boolean) {
            if (finito) return
            finito = true
            main.removeCallbacks(tic)
            var buono = tieni
            try { rec.stop() } catch (_: Exception) { buono = false } // troppo corta: niente file valido
            rec.release()
            player.pause(); player.volume = volVideo; musica.volume = volMusica
            if (!buono) { file.delete(); return }
            val u = Uri.fromFile(file)
            lavoro.execute {
                val dur = Archivio.durataAudio(this, u)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (dur == null) { file.delete(); avviso("The recording can't be read."); return@runOnUiThread }
                    val n = vivo.musica.count { it.nome.startsWith("Voice") } + 1
                    val m = Progetto.mettiMusica(vivo.musica, Musica(u.toString(), "Voice $n", dur), qui)
                    if (m == null) { file.delete(); avviso("There's already audio at the line."); return@runOnUiThread }
                    fai(vivo.copy(musica = m), Timeline.Scelta(Timeline.Tipo.MUSICA, m.indexOfFirst { it.uri == u.toString() }), qui)
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Recording your voice")
            .setView(orologioVoce)
            .setCancelable(false)
            .setPositiveButton("Stop") { _, _ -> fine(true) }
            .setNegativeButton("Cancel") { _, _ -> fine(false) }
            .show()
    }

    override fun aggiungi(tipo: Timeline.Tipo) {
        when (tipo) {
            Timeline.Tipo.CLIP -> scegliVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            Timeline.Tipo.TESTO -> scrivi(-1)
            Timeline.Tipo.MUSICA -> { sostituisci = -1; scegliMusica.launch(arrayOf("audio/*")) }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- i testi sul video parlano

    override fun scritta(i: Int) {
        scelta = if (i < 0) Timeline.Scelta.NESSUNA else Timeline.Scelta(Timeline.Tipo.TESTO, i)
        timeline.scegli(scelta)
        aggiornaIspettore()
    }

    override fun scrittaSposta(i: Int, x: Float, y: Float, fine: Boolean) {
        val s = storia.adesso.scritte.toMutableList()
        val v = s.getOrNull(i) ?: return
        s[i] = v.copy(testo = v.testo.copy(x = x, y = y))
        val nuova = storia.adesso.copy(scritte = s)
        if (fine) fai(nuova, scelta, timeline.t) else anteprima(nuova)
    }

    override fun scrittaMisura(i: Int, misura: Float, fine: Boolean) {
        val s = storia.adesso.scritte.toMutableList()
        val v = s.getOrNull(i) ?: return
        s[i] = v.copy(testo = v.testo.copy(misura = misura))
        val nuova = storia.adesso.copy(scritte = s)
        if (fine) fai(nuova, scelta, timeline.t) else anteprima(nuova)
    }

    override fun scrittaRiscrivi(i: Int) { scritta(i); scrivi(i) }

    // ---------------------------------------------------------------- l'ispettore

    private fun preparaIspettore() {
        // aggiungere: i «+» delle corsie, a destra della timeline (Timeline.aggiungi / aggiungiDisegno)
        for (x in listOf(R.id.chiudi_clip, R.id.chiudi_musica, R.id.chiudi_testo, R.id.chiudi_transizione)) findViewById<View>(x).setOnClickListener { fatto() }
        preparaTransizione()
        preparaTastierino()
        findViewById<View>(R.id.duplica_testo).setOnClickListener { duplicaScritta() }

        // clip: volume e velocità con lo stesso cursore (− e + dentro, il normale al centro)
        cursoreVolume(R.id.volume_clip) { v -> conClip { it.copy(volume = v) } }
        findViewById<View>(R.id.muto_clip).setOnClickListener { cambiaClip { it.copy(volume = if (it.volume == 0) 100 else 0) } }
        findViewById<View>(R.id.valore_clip).setOnClickListener {
            val c = clip.getOrNull(scelta.i) ?: return@setOnClickListener
            tastierino("Volume (%)", c.volume.toString(), "0 – ${Progetto.VOLUME_MAX}") { n -> cambiaClip { it.copy(volume = n.roundToInt().coerceIn(0, Progetto.VOLUME_MAX)) } }
        }
        // la velocità: il cursore lavora in «posizioni» (-1 · 0 = 1× · +1), con calamite sulle velocità tonde
        findViewById<CursoreVista>(R.id.velocita_clip).apply {
            da = -1f; a = 1f; normale = 0f
            calamite = Progetto.CALAMITE_VELOCITA.map { Progetto.posizione(it) }.toFloatArray(); soglia = 0.035f
            passo = { p, verso -> Progetto.posizione(Progetto.passoVelocita(Progetto.daPosizione(p), verso)) }
            ascoltatore = CursoreVista.Ascoltatore { p, fine -> conClip { it.copy(velocita = Progetto.vicina(Progetto.daPosizione(p))) }?.let { if (fine) fai(it, scelta, timeline.t) else anteprima(it) } }
        }
        findViewById<View>(R.id.valore_velocita).setOnClickListener {
            val c = clip.getOrNull(scelta.i) ?: return@setOnClickListener
            tastierino("Speed (×)", Progetto.velocitaScritta(c.velocita).removeSuffix("×"), "0.1 – 30") { n -> cambiaClip { it.copy(velocita = Progetto.vicina(n)) } }
        }

        findViewById<View>(R.id.dividi).setOnClickListener { dividi() }
        findViewById<View>(R.id.duplica).setOnClickListener { duplica() }
        findViewById<View>(R.id.elimina).setOnClickListener { elimina() }

        // musica
        cursoreVolume(R.id.volume_musica) { v -> conMusica { it.copy(volume = v) } }
        findViewById<View>(R.id.muto_musica).setOnClickListener { cambiaMusica { it.copy(volume = if (it.volume == 0) 100 else 0) } }
        findViewById<View>(R.id.valore_musica).setOnClickListener {
            val m = vivo.musica.getOrNull(scelta.i) ?: return@setOnClickListener
            tastierino("Volume (%)", m.volume.toString(), "0 – ${Progetto.VOLUME_MAX}") { n -> cambiaMusica { it.copy(volume = n.roundToInt().coerceIn(0, Progetto.VOLUME_MAX)) } }
        }
        findViewById<View>(R.id.entra_musica).setOnClickListener { cambiaMusica { it.copy(entra = !it.entra) } }
        findViewById<View>(R.id.esce_musica).setOnClickListener { cambiaMusica { it.copy(esce = !it.esce) } }
        findViewById<View>(R.id.cambia_musica).setOnClickListener {
            sostituisci = scelta.i; scegliMusica.launch(arrayOf("audio/*"))
        }
        findViewById<View>(R.id.dividi_musica).setOnClickListener {
            val qui = timeline.t
            val sotto = vivo.musica.indexOfFirst { qui > it.posMs && qui < it.fineGlobaleMs }
            val m = Progetto.dividiMusica(vivo.musica, qui)
            if (m == null) { avviso("Put the line on the music, a bit away from its edges, to split it"); return@setOnClickListener }
            fai(vivo.copy(musica = m), Timeline.Scelta(Timeline.Tipo.MUSICA, sotto + 1), qui)
        }
        findViewById<View>(R.id.elimina_musica).setOnClickListener {
            val i = scelta.i
            if (i !in vivo.musica.indices) return@setOnClickListener
            fai(vivo.copy(musica = vivo.musica.filterIndexed { k, _ -> k != i }), Timeline.Scelta.NESSUNA, timeline.t)
            avvisoAnnulla("Music deleted")
        }

        // testo
        // font e colori: le righe comuni (Ui.righeFont / righeColori), fatte quando si apre il pannello
        val stili = findViewById<LinearLayout>(R.id.stili)
        for ((s, nome) in Ui.STILI) stili.addView(Ui.pillola(this, nome, 40) { cambiaTesto { it.copy(stile = s) } })
        // sotto gli stili: colore e spessore di «Outline», colore di «Box»
        opzioniStile = OpzioniStile(this) { sec, sp, fine ->
            val i = scelta.i
            if (scelta.tipo != Timeline.Tipo.TESTO || i !in storia.adesso.scritte.indices) return@OpzioniStile
            val b = storia.adesso.copy(scritte = storia.adesso.scritte.toMutableList().also {
                it[i] = it[i].copy(testo = it[i].testo.copy(secondo = sec ?: it[i].testo.secondo, spessore = sp ?: it[i].testo.spessore))
            })
            if (fine) fai(b, scelta, timeline.t) else anteprima(b)
            scritteVista.mostra(vivo.scritte, timeline.t)
        }
        (stili.parent as View).let { riga -> (riga.parent as ViewGroup).let { it.addView(opzioniStile, it.indexOfChild(riga) + 1) } }
        findViewById<View>(R.id.riscrivi).setOnClickListener {
            if (scelta.tipo != Timeline.Tipo.TESTO) return@setOnClickListener
            val s = vivo.scritte.getOrNull(scelta.i) ?: return@setOnClickListener
            if (s.disegno) disegna(s.testo.id) else scrivi(scelta.i)
        }
        findViewById<View>(R.id.elimina_testo).setOnClickListener {
            val i = scelta.i
            if (scelta.tipo != Timeline.Tipo.TESTO || i !in vivo.scritte.indices) return@setOnClickListener
            val era = if (vivo.scritte[i].disegno) "Drawing" else "Text"
            fai(vivo.copy(scritte = vivo.scritte.filterIndexed { k, _ -> k != i }), Timeline.Scelta.NESSUNA, timeline.t)
            avvisoAnnulla("$era deleted")
        }
    }

    private fun conClip(f: (Clip) -> Clip): Bozza? {
        val i = scelta.i
        if (scelta.tipo != Timeline.Tipo.CLIP || i !in storia.adesso.clip.indices) return null
        return storia.adesso.copy(clip = storia.adesso.clip.toMutableList().also { it[i] = f(it[i]) })
    }

    private fun cambiaClip(f: (Clip) -> Clip) {
        val b = conClip(f) ?: return
        fai(b, scelta, timeline.t)
    }

    private fun conMusica(f: (Musica) -> Musica): Bozza? {
        val i = scelta.i
        if (scelta.tipo != Timeline.Tipo.MUSICA || i !in storia.adesso.musica.indices) return null
        return storia.adesso.copy(musica = storia.adesso.musica.toMutableList().also { it[i] = f(it[i]) })
    }

    private fun cambiaMusica(f: (Musica) -> Musica) { conMusica(f)?.let { fai(it, scelta, timeline.t) } }

    /** Un colore toccato nel pannello del testo: il testo cambia colore, un disegno ricolora i suoi tratti. */
    private fun coloreScelto(c: Int) {
        val i = scelta.i
        val s = vivo.scritte.getOrNull(i)?.takeIf { scelta.tipo == Timeline.Tipo.TESTO } ?: return
        if (!s.disegno) { cambiaTesto { it.copy(colore = c) }; return }
        val n = vivo.scritte.toMutableList().also { it[i] = s.copy(tratti = s.tratti.map { t -> t.copia(colore = c) }) }
        fai(vivo.copy(scritte = n), scelta, timeline.t)
    }

    private fun cambiaTesto(f: (Testo) -> Testo) {
        val i = scelta.i
        if (scelta.tipo != Timeline.Tipo.TESTO || i !in vivo.scritte.indices) return
        val s = vivo.scritte.toMutableList().also { it[i] = it[i].copy(testo = f(it[i].testo)) }
        fai(vivo.copy(scritte = s), scelta, timeline.t)
    }

    private fun valore(sid: Int, tid: Int, v: Int, testo: String) {
        findViewById<CursoreVista>(sid).valore = v.toFloat()
        findViewById<TextView>(tid).text = testo
    }

    /** Un cursore del volume: 0–200% (scritto a mano fino a 400), 100 al centro, calamite ogni 25, − e + di 5. */
    private fun cursoreVolume(id: Int, con: (Int) -> Bozza?) {
        findViewById<CursoreVista>(id).apply {
            da = 0f; a = Progetto.VOLUME_CURSORE.toFloat(); normale = 100f
            calamite = Progetto.CALAMITE_VOLUME; soglia = Progetto.CALAMITA_VOLUME
            passo = { v, verso -> (Math.round(v / 5f) * 5f + verso * 5f) }
            ascoltatore = CursoreVista.Ascoltatore { v, fine -> con(v.roundToInt())?.let { if (fine) fai(it, scelta, timeline.t) else anteprima(it) } }
        }
    }

    // ---------------------------------------------------------------- la transizione scelta

    private val TIPI_TRANSIZIONE = listOf("None", "Fade to black")

    private fun preparaTransizione() {
        val riga = findViewById<LinearLayout>(R.id.tipi_transizione)
        TIPI_TRANSIZIONE.forEachIndexed { k, nome -> riga.addView(Ui.pillola(this, nome, 40) { tipoTransizione(k == 1) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() }
        }) }
        findViewById<CursoreVista>(R.id.durata_transizione).apply {
            da = Progetto.DISSOLVENZA_MIN / 1000f; a = Progetto.DISSOLVENZA_MAX / 1000f; normale = DISSOLVENZA_STD / 1000f
            calamite = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 3f); soglia = 0.06f
            passo = { v, verso -> Math.round(v * 20f + verso) / 20f }
            ascoltatore = CursoreVista.Ascoltatore { v, fine -> durataTransizione((v * 1000).roundToInt().toLong(), fine) }
        }
        findViewById<View>(R.id.valore_durata).setOnClickListener {
            if (scelta.tipo != Timeline.Tipo.TRANSIZIONE) return@setOnClickListener
            val k = scelta.i
            tastierino("Length (s)", String.format(java.util.Locale.US, "%.2f", Progetto.durataTransizione(clip, k) / 1000f), "0.1 – 3") { n ->
                durataTransizione((n * 1000).roundToInt().toLong(), true)
            }
        }
    }

    private fun tipoTransizione(si: Boolean) {
        val k = scelta.i
        if (scelta.tipo != Timeline.Tipo.TRANSIZIONE || si == Progetto.transizione(clip, k)) return
        fai(vivo.copy(clip = Progetto.conTransizione(clip, k, si)), scelta, timeline.t)
    }

    private fun durataTransizione(ms: Long, fine: Boolean) {
        val k = scelta.i
        if (scelta.tipo != Timeline.Tipo.TRANSIZIONE) return
        // allungare una transizione spenta la accende: si vuole vederla
        val n = vivo.copy(clip = Progetto.conTransizione(storia.adesso.clip, k, true, ms))
        if (fine) fai(n, scelta, timeline.t) else anteprima(n)
    }

    // ---------------------------------------------------------------- il tastierino dei numeri
    // Nel pannello, non la tastiera del telefono: in orizzontale quella copre tutto (e con la S Pen è scomoda).

    private class Numero(val titolo: String, var testo: String, val limiti: String, val fatto: (Float) -> Unit)
    private var numero: Numero? = null

    private fun preparaTastierino() {
        val tasti = findViewById<LinearLayout>(R.id.tasti)
        for (riga in listOf("123", "456", "789", ".0⌫")) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (t in riga.map { it.toString() }) r.addView(TextView(this).apply {
                text = t; textSize = 22f; gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.testo))
                background = ContextCompat.getDrawable(context, R.drawable.fondo_valore)
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
                }
                isClickable = true
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); tasto(t) }
            })
            tasti.addView(r)
        }
        findViewById<View>(R.id.chiudi_numero).setOnClickListener { chiudiTastierino(true) }
    }

    private fun tasto(t: String) {
        val n = numero ?: return
        findViewById<TextView>(R.id.schermo_numero).alpha = 1f
        n.testo = when {
            t == "⌫" -> n.testo.dropLast(1)
            t == "." && n.testo.contains('.') -> n.testo
            n.testo.length >= 7 -> n.testo
            else -> n.testo + t
        }
        findViewById<TextView>(R.id.schermo_numero).text = n.testo.ifEmpty { " " }
    }

    /** Apre il tastierino al posto del pannello; «Done» scrive il numero, indietro lo chiude senza. */
    private fun tastierino(titolo: String, adesso: String, limiti: String, fatto: (Float) -> Unit) {
        numero = Numero(titolo, "", limiti, fatto)
        findViewById<TextView>(R.id.testa_numero).text = "$titolo  ·  $limiti"
        // si scrive da zero: il numero di adesso resta come suggerimento
        findViewById<TextView>(R.id.schermo_numero).apply { text = adesso; alpha = 0.5f }
        aggiornaIspettore()
    }

    private fun chiudiTastierino(scrivi: Boolean) {
        val n = numero ?: return
        numero = null
        if (scrivi && n.testo.isNotEmpty()) {
            val v = n.testo.toFloatOrNull()
            if (v == null || !v.isFinite()) avviso("That's not a number") else n.fatto(v)
        }
        findViewById<TextView>(R.id.schermo_numero).alpha = 1f
        aggiornaIspettore()
    }



    private fun aggiornaIspettore() {
        // con la penna accesa (o il tastierino aperto) l'ispettore mostra solo lei
        val tipo = if (disegnando || numero != null) null else scelta.tipo
        findViewById<View>(R.id.p_numero).visibility = if (numero != null && !disegnando) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_transizione).visibility = if (tipo == Timeline.Tipo.TRANSIZIONE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_niente).visibility = if (tipo == Timeline.Tipo.NIENTE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_clip).visibility = if (tipo == Timeline.Tipo.CLIP) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_musica).visibility = if (tipo == Timeline.Tipo.MUSICA) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_testo).visibility = if (tipo == Timeline.Tipo.TESTO) View.VISIBLE else View.GONE
        findViewById<View>(R.id.p_mano).visibility = if (disegnando) View.VISIBLE else View.GONE
        lavagna.attiva = disegnando
        lavagna.tratti = storia.adesso.scritte.firstOrNull { it.testo.id == disegnoId }?.tratti ?: emptyList()
        scritteVista.scelta = if (scelta.tipo == Timeline.Tipo.TESTO) scelta.i else -1
        when (tipo) {
            null -> {}
            Timeline.Tipo.TRANSIZIONE -> {
                val k = scelta.i
                val n = clip.size
                findViewById<TextView>(R.id.testa_transizione).text = when (k) {
                    0 -> "Transition  ·  start"; n -> "Transition  ·  end"; else -> "Transition  ·  clip $k → ${k + 1}"
                }
                val si = Progetto.transizione(clip, k)
                val tipi = findViewById<LinearLayout>(R.id.tipi_transizione)
                TIPI_TRANSIZIONE.indices.forEach { j -> tipi.getChildAt(j)?.let { Ui.segna(it, (j == 1) == si) } }
                (tipi.getChildAt(1) as? TextView)?.text = when (k) { 0 -> "From black"; n -> "To black"; else -> "Through black" }
                findViewById<View>(R.id.riga_durata).alpha = if (si) 1f else SPENTO
                val ms = Progetto.durataTransizione(clip, k)
                findViewById<CursoreVista>(R.id.durata_transizione).valore = ms / 1000f
                findViewById<TextView>(R.id.valore_durata).text = Progetto.secondi(ms)
            }
            Timeline.Tipo.NIENTE -> {
                val m = misuraVideo
                findViewById<TextView>(R.id.info_progetto).text = "${vivo.nome.ifBlank { "Video" }}  ·  ${Progetto.tempo(Progetto.totaleMs(clip))}" +
                    (if (m != null) "  ·  ${m.first}×${m.second}" else "")
            }
            Timeline.Tipo.CLIP -> {
                val c = clip.getOrNull(scelta.i) ?: return
                val dur = Progetto.secondi(c.lunghezzaMs)
                findViewById<TextView>(R.id.testa_clip).text = "Clip ${scelta.i + 1}  ·  " +
                    (if (c.velocita == 1f) dur else "$dur → ${Progetto.secondi(c.vistaMs)}")
                valore(R.id.volume_clip, R.id.valore_clip, c.volume, "${c.volume}%")
                findViewById<ImageButton>(R.id.muto_clip).setImageResource(if (c.volume == 0) R.drawable.ic_muto else R.drawable.ic_volume)
                findViewById<CursoreVista>(R.id.velocita_clip).valore = Progetto.posizione(c.velocita)
                findViewById<TextView>(R.id.valore_velocita).text = Progetto.velocitaScritta(c.velocita)
                // Split taglia QUESTA clip alla riga: con la riga fuori si vede spento e dice perché
                val dentro = rigaDentro(scelta.i) != null
                findViewById<View>(R.id.dividi).alpha = if (dentro) 1f else SPENTO
                findViewById<TextView>(R.id.aiuto_clip).text =
                    if (dentro) "Hold the clip on the timeline, then drag to move it." else "To split it, move the white line onto this clip."
            }
            Timeline.Tipo.MUSICA -> {
                val m = vivo.musica.getOrNull(scelta.i) ?: return
                findViewById<TextView>(R.id.testa_musica).text = "${m.nome}  ·  ${Progetto.secondi(m.lunghezzaMs)}"
                valore(R.id.volume_musica, R.id.valore_musica, m.volume, "${m.volume}%")
                findViewById<ImageButton>(R.id.muto_musica).setImageResource(if (m.volume == 0) R.drawable.ic_muto else R.drawable.ic_volume)
                for ((b, si) in listOf(R.id.entra_musica to m.entra, R.id.esce_musica to m.esce)) findViewById<MaterialButton>(b).apply {
                    isChecked = si
                    val col = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, if (si) R.color.accento else R.color.testo))
                    setTextColor(col); strokeColor = if (si) col else android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.riga))
                }
            }
            Timeline.Tipo.TESTO -> {
                val s = vivo.scritte.getOrNull(scelta.i) ?: return
                val lungo = Progetto.secondi(s.fineMs - s.inizioMs)
                findViewById<TextView>(R.id.testa_testo).text = if (s.disegno) "Drawing  ·  $lungo" else "“${s.testo.testo.replace('\n', ' ')}”  ·  $lungo"
                // un disegno: niente font né stile, ma il colore sì (ricolora tutti i tratti); al posto di Edit, «Draw more»
                for (r in listOf(R.id.font, R.id.stili)) (findViewById<View>(r).parent as View).visibility = if (s.disegno) View.GONE else View.VISIBLE
                findViewById<MaterialButton>(R.id.riscrivi).apply {
                    text = if (s.disegno) "Draw more" else "Edit"
                    setIconResource(if (s.disegno) R.drawable.ic_pennello else R.drawable.ic_matita)
                }
                findViewById<TextView>(R.id.aiuto_testo).text = if (s.disegno)
                    "Drag the pink edges on the timeline to make it longer or shorter." else "Drag it on the video to move it · pinch to resize."
                val font = findViewById<LinearLayout>(R.id.font)
                Ui.righeFont(this, font) { fid -> cambiaTesto { it.copy(font = fid) } }
                Ui.segnaFont(font, s.testo.font)
                val colori = findViewById<LinearLayout>(R.id.colori)
                Ui.righeColori(this, colori, Ui.COLORI) { coloreScelto(it) }
                Ui.segnaColori(colori, if (s.disegno) s.tratti.first().colore else s.testo.colore)
                val stili = findViewById<LinearLayout>(R.id.stili)
                Ui.STILI.forEachIndexed { k, (st, _) -> stili.getChildAt(k)?.let { Ui.segna(it, st == s.testo.stile) } }
                opzioniStile.mostra(if (s.disegno) Testo.SEMPLICE else s.testo.stile, s.testo.secondo, s.testo.spessore)
            }
        }
    }

    // ---------------------------------------------------------------- la penna (la stessa di foto e design)

    /** Accende la penna: [id] = continua quel disegno; null = il primo tratto ne fa uno nuovo alla riga. */
    private fun disegna(id: Long?) {
        player.pause()
        val s = vivo.scritte.firstOrNull { it.testo.id == id }
        // il disegno da continuare si vede tutto: la riga va dentro il suo tempo
        if (s != null && (timeline.t < s.inizioMs || timeline.t >= s.fineMs)) { timeline.posizione(s.inizioMs); vaiA(s.inizioMs) }
        disegnoId = s?.testo?.id
        disegnando = true
        if (s != null) { scelta = Timeline.Scelta(Timeline.Tipo.TESTO, vivo.scritte.indexOf(s)); timeline.scegli(scelta) }
        aggiornaIspettore()
    }

    /** I tratti dopo il dito → il disegno in corso (nasce al primo tratto, sparisce se si cancella tutto). */
    private fun inchiostro(t: List<Tratto>, fine: Boolean) {
        val base = storia.adesso
        val i = base.scritte.indexOfFirst { it.testo.id == disegnoId }
        val nuove: List<Scritta> = when {
            i >= 0 && t.isEmpty() -> base.scritte.filterIndexed { k, _ -> k != i }
            i >= 0 -> base.scritte.toMutableList().also { it[i] = it[i].copy(tratti = t) }
            t.isEmpty() -> return
            else -> {
                val nuovo = Testo(id = System.currentTimeMillis(), testo = "")
                val sc = Progetto.mettiScritta(base.scritte, nuovo, timeline.t, TESTO_MS)
                disegnoId = nuovo.id
                sc.map { if (it.testo.id == nuovo.id) it.copy(tratti = t) else it }
            }
        }
        val k = nuove.indexOfFirst { it.testo.id == disegnoId }
        val s = if (k >= 0) Timeline.Scelta(Timeline.Tipo.TESTO, k) else Timeline.Scelta.NESSUNA
        if (fine) fai(base.copy(scritte = nuove), s, timeline.t)
        else { scelta = s; anteprima(base.copy(scritte = nuove)) }
        scritteVista.mostra(vivo.scritte, timeline.t)
    }

    /** Fine della penna: il disegno fatto resta scelto (si allunga e si sposta sulla timeline). */
    private fun finisciDisegno() {
        disegnando = false
        val k = vivo.scritte.indexOfFirst { it.testo.id == disegnoId }
        disegnoId = null
        scelta = if (k >= 0) Timeline.Scelta(Timeline.Tipo.TESTO, k) else Timeline.Scelta.NESSUNA
        timeline.scegli(scelta)
        aggiornaIspettore()
    }

    // ---------------------------------------------------------------- attrezzi

    /** La riga bianca sta dentro la clip [i]? Allora Split la taglia lì. */
    private fun rigaDentro(i: Int): Long? = Progetto.dove(clip, timeline.t)?.takeIf { it.first == i }?.second

    /** Split: taglia la clip SCELTA alla riga. Se la riga è fuori, lo dice e non taglia un'altra clip. */
    private fun dividi() {
        player.pause()
        val i = attuale()
        val nelFile = rigaDentro(i)
        if (nelFile == null) { avviso("Move the white line onto this clip to split it"); return }
        val nuove = Progetto.dividi(clip, i, nelFile)
        if (nuove == null) { avviso("Move the line a bit away from the edge of the clip to split it"); return }
        fai(vivo.copy(clip = nuove), Timeline.Scelta(Timeline.Tipo.CLIP, i + 1), timeline.t)
    }

    /** Duplicate di un testo o disegno: la copia va subito dopo (se c'è già qualcosa, su un'altra riga). */
    private fun duplicaScritta() {
        val i = scelta.i
        val s = vivo.scritte.getOrNull(i)?.takeIf { scelta.tipo == Timeline.Tipo.TESTO } ?: return
        val copia = s.testo.copy(id = System.currentTimeMillis())
        val sc = Progetto.mettiScritta(vivo.scritte, copia, s.fineMs, s.fineMs - s.inizioMs)
        val nuove = sc.map { if (it.testo.id == copia.id) it.copy(tratti = s.tratti) else it }
        val k = nuove.indexOfFirst { it.testo.id == copia.id }
        fai(vivo.copy(scritte = nuove), Timeline.Scelta(Timeline.Tipo.TESTO, k), nuove[k].inizioMs)
        timeline.posizione(nuove[k].inizioMs); vaiA(nuove[k].inizioMs)
    }

    private fun duplica() {
        val i = attuale()
        val nuove = Progetto.duplica(clip, i)
        fai(vivo.copy(clip = nuove), Timeline.Scelta(Timeline.Tipo.CLIP, i + 1), Progetto.inizioGlobale(nuove, i + 1))
    }

    private fun elimina() {
        val i = attuale()
        if (i !in clip.indices) return
        val nuove = clip.toMutableList().also { it.removeAt(i) }
        fai(vivo.copy(clip = nuove), Timeline.Scelta.NESSUNA, Progetto.inizioGlobale(nuove, i))
        avvisoAnnulla("Clip deleted")
    }

    /** Scrivere un testo nuovo ([i] = -1, alla riga) o riscriverne uno. Il nuovo prende font, colore e stile dell'ultimo. */
    private fun scrivi(i: Int) {
        val vecchio = vivo.scritte.getOrNull(i)
        val campo = EditText(this).apply {
            setText(vecchio?.testo?.testo ?: ""); setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            // in orizzontale la tastiera non deve prendersi tutto lo schermo (si vede ancora il video)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
        }
        val box = FrameLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(campo) }
        val qui = timeline.t
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(if (vecchio == null) "Add text" else "Edit text")
            .setView(box)
            .setPositiveButton(if (vecchio == null) "Add" else "Done") { _, _ ->
                val s = campo.text.toString().trimEnd()
                if (s.isBlank()) return@setPositiveButton
                if (vecchio != null) { cambiaTesto { it.copy(testo = s) }; return@setPositiveButton }
                val ultimo = vivo.scritte.lastOrNull()?.testo
                val nuovo = Testo(
                    // un altro testo già sullo schermo in quel momento: il nuovo va più in basso, non sopra
                id = System.currentTimeMillis(), testo = s, y = (0.2f + 0.14f * Progetto.scritteA(vivo.scritte, qui).count { !it.disegno }).let { if (it > 0.85f) 0.2f else it },
                misura = 0.08f,
                    font = ultimo?.font ?: Caratteri.PRIMO, colore = ultimo?.colore ?: 0xFFFFFFFF.toInt(),
                    stile = ultimo?.stile ?: Testo.OMBRA,
                    secondo = ultimo?.secondo ?: 0, spessore = ultimo?.spessore ?: Testo.SPESSORE_STD,
                )
                val sc = Progetto.mettiScritta(vivo.scritte, nuovo, qui, TESTO_MS)
                fai(vivo.copy(scritte = sc), Timeline.Scelta(Timeline.Tipo.TESTO, sc.indexOfFirst { it.testo.id == nuovo.id }), qui)
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        campo.requestFocus()
    }

    // ---------------------------------------------------------------- export

    private fun sceltaEsporta() {
        if (clip.isEmpty() || esportazione != null) return
        val d = BottomSheetDialog(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt())
        }
        fun riga(icona: Int, titolo: String, sotto: String, audio: Boolean) {
            col.addView(layoutInflater.inflate(R.layout.item_tipo, col, false).apply {
                findViewById<android.widget.ImageView>(R.id.icona).setImageResource(icona)
                findViewById<TextView>(R.id.titolo).text = titolo
                findViewById<TextView>(R.id.sotto).text = sotto
                setOnClickListener { d.dismiss(); esporta(audio) }
            })
        }
        riga(R.drawable.ic_video, "Video", ".mp4 in Movies › 151 Editor", false)
        riga(R.drawable.ic_musica, "Sound only", ".m4a in Music › 151 Editor", true)
        d.setContentView(col)
        d.show()
    }

    private fun esporta(soloAudio: Boolean) {
        if (clip.isEmpty() || esportazione != null) return
        esportaAudio = soloAudio
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { permessoVecchio.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        player.pause()

        val vista = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.testo)) }
        val barra = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
            addView(vista); addView(barra)
        }
        val cosa = if (soloAudio) "sound" else "video"
        vista.text = "Making your $cosa…"
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle("Export")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Stop", null)
            .create()

        val primo = clip.first()
        val copia = storia.adesso
        lavoro.execute {
            val info = Archivio.info(this, Uri.parse(primo.uri))
            val mute = if (!soloAudio) emptySet() else copia.clip.indices.filter { Archivio.durataAudio(this, Uri.parse(copia.clip[it].uri)) == null }.toSet()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (info == null) { avviso("The first clip can't be read any more. Delete it and add it again."); return@runOnUiThread }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val e = Esporta(this, copia, info.larghezza, info.altezza, soloAudio, mute,
                    avanzamento = { p -> barra.isIndeterminate = false; barra.setProgressCompat(p, true); vista.text = "Making your $cosa… $p%" },
                    fineGrezza = { esito ->
                        esportazione = null
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (dialogo.isShowing) dialogo.dismiss()
                        when (esito) {
                            is Esporta.Esito.Fatto -> fatto(esito, soloAudio)
                            is Esporta.Esito.Errore -> avviso("Export failed (${esito.perche}). Nothing was saved.")
                            Esporta.Esito.Fermato -> {}
                        }
                    })
                esportazione = e
                dialogo.show()
                dialogo.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { e.ferma() }
                e.parti()
            }
        }
    }

    private fun fatto(e: Esporta.Esito.Fatto, soloAudio: Boolean) {
        val tipo = if (soloAudio) "audio/mp4" else "video/mp4"
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved")
            .setMessage("${e.nome}\nis in ${if (soloAudio) "Music" else "Movies"} › 151 Editor. The draft stays here.")
            .setPositiveButton(if (soloAudio) "Listen" else "Watch") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, tipo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(tipo)
                    .putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
            .setNeutralButton("OK", null)
            .show()
    }
}
