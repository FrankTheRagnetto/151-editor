package com.the151universe.art

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * L'editor Audio: un progetto fatto solo di suoni, su più corsie ([CorsieVista]).
 * Dalla 0.17.0 senza pannello a destra («crowded and ugly»): in alto indietro, nome, tempo,
 * annulla/rifai, Save; in basso Play grande a sinistra e UNA fila che cambia con quello che fai
 * ([Barra]): il progetto, il suono scelto, il suo volume, le sue dissolvenze, la corsia toccata.
 * Ogni gesto passa dalla [Storia] e si salva subito.
 *
 * L'anteprima: un orologio (il tempo vero che passa) e un lettore per corsia, tenuto al passo
 * come la musica del video; volume, corsia (muta, solo, volume) e dissolvenze li fa un [Guadagno]
 * dentro ciascuno, con la stessa formula dell'export ([Suono.guadagno]).
 */
@OptIn(UnstableApi::class)
class SuonoActivity : AppCompatActivity(), CorsieVista.Ascoltatore {
    companion object {
        const val ID = "id"
        /** quanto prima si prepara il pezzo che sta per cominciare */
        const val PRIMA_MS = 1500L
        /** quanto dura il Loop appena acceso, se non c'è un suono scelto */
        const val LOOP_STD = 5000L
    }

    private enum class Barra { PROGETTO, SUONO, VOLUME, VELOCITA, NUMERO, FADE, CORSIA }

    private lateinit var id: String
    private lateinit var storia: Storia<ProgettoSuono>
    private var vivo = ProgettoSuono("")
    private val pezzi get() = vivo.pezzi
    private var scelto = -1
    private var barra = Barra.PROGETTO

    private lateinit var corsie: CorsieVista
    private lateinit var tempo: TextView
    private lateinit var suona: ImageButton
    private val main = Handler(Looper.getMainLooper())
    private val lavoro = Executors.newSingleThreadExecutor()
    private var esportazione: Esporta? = null
    private val dp by lazy { resources.displayMetrics.density }

    // --- l'orologio dell'anteprima ---
    private var suonando = false
    private var partito = 0L
    private var daMs = 0L
    private fun adesso(): Long = if (suonando) daMs + (SystemClock.elapsedRealtime() - partito) else corsie.t

    /** Un lettore per corsia, fatto la prima volta che serve. */
    private class Lettore(val p: ExoPlayer, val g: Guadagno, var caricato: String? = null)
    private val lettori = HashMap<Int, Lettore>()

    private val orologio = object : Runnable {
        override fun run() {
            if (suonando) {
                val t = adesso()
                val l = corsie.loop
                when {
                    l != null && t >= l.last -> riparti(l.first)
                    t >= Suono.totaleMs(pezzi) -> ferma(Suono.totaleMs(pezzi))
                    else -> corsie.posizione(t)
                }
            }
            sincronizza()
            scriviTempo()
            main.postDelayed(this, 33)
        }
    }

    /** Un lettore col nostro [Guadagno] in fondo alla catena del suono (lo stesso dell'editor video). */
    private fun lettore(c: Int): Lettore = lettori.getOrPut(c) {
        val g = Guadagno()
        val fabbrica = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder(context)
                    .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(100_000).setTargetPcmBufferDurationUs(150_000)
                        .setMaxPcmBufferDurationUs(200_000).build())
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(arrayOf(g))
                    .build()
        }
        Lettore(ExoPlayer.Builder(this, fabbrica).build(), g)
    }

    /** Un suono: qualsiasi file audio del telefono. [sostituisci] = indice da cambiare, -1 = nuovo. */
    private var sostituisci = -1
    private val scegliSuono = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        val qui = corsie.t
        val dove = sostituisci
        lavoro.execute {
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val dur = Archivio.durataAudio(this, u)
            val nome = Archivio.nome(this, u).substringBeforeLast('.')
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (dur == null) { avviso("This file has no sound 151 Editor can read."); return@runOnUiThread }
                val vecchio = pezzi.getOrNull(dove)
                if (vecchio != null) {
                    // stesso posto e regolazioni, al massimo lungo quanto prima
                    val l = pezzi.toMutableList()
                    l[dove] = vecchio.copy(uri = u.toString(), nome = nome, durataMs = dur, inizioMs = 0, fineMs = minOf(dur, vecchio.lunghezzaMs))
                    fai(vivo.copy(pezzi = l), dove)
                    return@runOnUiThread
                }
                val r = Suono.metti(pezzi, Musica(u.toString(), nome, dur), qui)
                if (r == null) { avviso("All the lanes are busy at the line. Move the line to a free spot."); return@runOnUiThread }
                fai(vivo.copy(pezzi = r.first), r.second)
            }
        }
    }

    private val permessoVecchio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) esporta() else avviso("151 Editor needs storage access to save on this phone.")
    }

    private val permessoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) registra() else avviso("151 Editor needs the microphone to record your voice.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suono)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        id = intent.getStringExtra(ID) ?: return finish()
        val s = Archivio.caricaSuono(this, id) ?: return finish()
        storia = Storia(s); vivo = s

        tempo = findViewById(R.id.tempo)
        suona = findViewById(R.id.suona)
        corsie = findViewById(R.id.corsie)
        corsie.ascoltatore = this

        findViewById<TextView>(R.id.nome_progetto).text = vivo.nome.ifBlank { "Audio" }
        findViewById<View>(R.id.indietro).setOnClickListener { finish() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (barra) {
                    Barra.VOLUME, Barra.VELOCITA, Barra.FADE -> mostra(Barra.SUONO)
                    Barra.NUMERO -> chiudiNumero(false)
                    Barra.SUONO, Barra.CORSIA -> fatto()
                    Barra.PROGETTO -> finish()
                }
            }
        })
        findViewById<View>(R.id.annulla).setOnClickListener { if (storia.annulla()) dopoStoria() }
        findViewById<View>(R.id.rifai).setOnClickListener { if (storia.rifai()) dopoStoria() }
        findViewById<View>(R.id.esporta).setOnClickListener { esporta() }
        suona.setOnClickListener { suonaOFerma() }
        preparaBarre()

        corsie.imposta(pezzi, scelto, vivo.corsie, vivo.segni)
        aggiornaBarra(); aggiornaBottoni()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(orologio)
        main.post(orologio)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(orologio)
        ferma(corsie.t)
    }

    override fun onDestroy() {
        main.removeCallbacks(orologio)
        esportazione?.ferma()
        corsie.chiudi()
        for (l in lettori.values) l.p.release()
        lettori.clear()
        lavoro.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- stato

    /** Un gesto finito: entra nella storia, si salva, tutto si rifà. */
    private fun fai(nuovo: ProgettoSuono, sc: Int) {
        storia.fai(nuovo)
        vivo = storia.adesso
        Archivio.salvaSuono(this, id, vivo)
        if (sc != scelto && barra != Barra.CORSIA) barra = if (sc >= 0) Barra.SUONO else Barra.PROGETTO
        scelto = sc
        corsie.imposta(pezzi, scelto, vivo.corsie, vivo.segni)
        aggiornaBarra(); aggiornaBottoni()
    }

    /** Un gesto ancora in corso (trascinamento, cursore): si vede e si sente, ma non entra nella storia. */
    private fun anteprima(nuovo: ProgettoSuono, sc: Int) {
        vivo = nuovo; scelto = sc
        corsie.imposta(pezzi, scelto, vivo.corsie, vivo.segni)
        aggiornaBarra()
    }

    private fun dopoStoria() {
        vivo = storia.adesso
        Archivio.salvaSuono(this, id, vivo)
        if (scelto !in pezzi.indices) { scelto = -1; if (barra != Barra.CORSIA) barra = Barra.PROGETTO }
        corsie.imposta(pezzi, scelto, vivo.corsie, vivo.segni)
        aggiornaBarra(); aggiornaBottoni()
    }

    /** «Done»: si lascia il suono scelto o la corsia, torna la fila del progetto. */
    private fun fatto() {
        scelto = -1; corsie.scegli(-1); corsie.corsiaAperta = -1
        mostra(Barra.PROGETTO)
    }

    private fun aggiornaBottoni() {
        findViewById<View>(R.id.esporta).isEnabled = pezzi.isNotEmpty()
        findViewById<View>(R.id.annulla).apply { isEnabled = storia.puoiAnnullare; alpha = if (isEnabled) 1f else EditorActivity.SPENTO }
        findViewById<View>(R.id.rifai).apply { isEnabled = storia.puoiRifare; alpha = if (isEnabled) 1f else EditorActivity.SPENTO }
    }

    // ---------------------------------------------------------------- suonare

    private fun suonaOFerma() {
        if (pezzi.isEmpty()) return
        if (suonando) { ferma(adesso()); return }
        val l = corsie.loop
        val da = when {
            l != null && (corsie.t < l.first || corsie.t >= l.last) -> l.first
            corsie.t >= Suono.totaleMs(pezzi) - 50 -> 0L
            else -> corsie.t
        }
        corsie.posizione(da)
        daMs = da; partito = SystemClock.elapsedRealtime(); suonando = true
        suona.setImageResource(R.drawable.ic_pausa); suona.contentDescription = "Pause"
        sincronizza()
    }

    /** Il Loop torna all'inizio: l'orologio riparte da [da] e ogni lettore si rimette al suo posto. */
    private fun riparti(da: Long) {
        daMs = da; partito = SystemClock.elapsedRealtime()
        for (l in lettori.values) l.p.pause()
        corsie.posizione(da)
    }

    private fun ferma(dove: Long) {
        suonando = false
        for (l in lettori.values) l.p.pause()
        if (::corsie.isInitialized) corsie.posizione(dove)
        if (::suona.isInitialized) { suona.setImageResource(R.drawable.ic_play); suona.contentDescription = "Play" }
    }

    /**
     * Ogni 33 ms, per ogni corsia: il pezzo sotto la riga suona dal punto giusto, col volume di
     * quel momento; se fra poco ne comincia uno, si prepara fermo al suo inizio (parte senza attesa).
     */
    private fun sincronizza() {
        if (!suonando) { for (l in lettori.values) if (l.p.playWhenReady) l.p.pause(); return }
        val t = adesso()
        val ta = t + EditorActivity.AVANTI_MS
        for (c in 0 until Suono.CORSIE_MAX) {
            val m = Suono.a(pezzi, c, t)
            if (m == null) {
                val l = lettori[c]
                if (l != null && l.p.playWhenReady) l.p.pause()
                // il prossimo di questa corsia, pronto al suo inizio
                val dopo = pezzi.firstOrNull { it.corsia == c && it.posMs > t && it.posMs - t < PRIMA_MS } ?: continue
                val lp = lettore(c)
                if (lp.caricato != dopo.uri) carica(lp, dopo.uri)
                velocitaGiusta(lp, dopo.velocita)
                if (lp.p.playbackState == Player.STATE_READY && abs(lp.p.currentPosition - dopo.inizioMs) > 250) lp.p.seekTo(dopo.inizioMs)
                continue
            }
            val l = lettore(c)
            if (l.caricato != m.uri) carica(l, m.uri)
            velocitaGiusta(l, m.velocita)
            l.g.adesso = Suono.guadagno(vivo, m, (ta - m.posMs).coerceAtMost(m.suonaMs))
            val voluto = m.nelFile(t)
            if (!l.p.playWhenReady) { if (abs(l.p.currentPosition - voluto) > 120) l.p.seekTo(voluto); l.p.play() }
            else if (l.p.playbackState == Player.STATE_READY && abs(l.p.currentPosition - voluto) > 250) l.p.seekTo(voluto)
        }
    }

    /** La velocità del pezzo, col tono vero (PlaybackParameters tiene il tono a 1: niente voce da criceto). */
    private fun velocitaGiusta(l: Lettore, v: Float) {
        if (l.p.playbackParameters.speed != v) l.p.setPlaybackParameters(PlaybackParameters(v))
    }

    private fun carica(l: Lettore, uri: String) {
        l.p.setMediaItem(MediaItem.fromUri(uri)); l.p.prepare(); l.caricato = uri
    }

    private var tempoScritto = ""
    private fun scriviTempo() {
        val nuovo = "${Progetto.tempo(adesso())} / ${Progetto.tempo(Suono.totaleMs(pezzi))}"
        if (nuovo != tempoScritto) { tempoScritto = nuovo; tempo.text = nuovo }
    }

    /** Gli avvisi stanno SOPRA la barra in basso: non coprono mai Play né i bottoni. */
    private fun avviso(t: String) {
        if (t.isNotEmpty()) Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_SHORT).setAnchorView(R.id.barra_basso).show()
    }

    private fun avvisoAnnulla(t: String) {
        Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_LONG).setAnchorView(R.id.barra_basso)
            .setAction("Undo") { if (storia.annulla()) dopoStoria() }.show()
    }

    // ---------------------------------------------------------------- le corsie parlano

    override fun scorri(t: Long) { if (suonando) ferma(t); scriviTempo() }

    override fun fermo(t: Long) { scriviTempo() }

    override fun scegli(i: Int) {
        scelto = i
        if (i >= 0) corsie.corsiaAperta = -1
        mostra(if (i >= 0) Barra.SUONO else Barra.PROGETTO)
    }

    override fun bordo(i: Int, sinistro: Boolean, delta: Long, finito: Boolean) {
        val (l, k) = Suono.bordo(storia.adesso.pezzi, i, sinistro, delta)
        val n = storia.adesso.copy(pezzi = l)
        if (finito) fai(n, k) else anteprima(n, k)
    }

    override fun trascina(i: Int, delta: Long, corsia: Int, finito: Boolean) {
        val base = storia.adesso.pezzi
        val m = base.getOrNull(i) ?: return
        val (l, k) = Suono.sposta(base, i, m.posMs + delta, corsia)
        val n = storia.adesso.copy(pezzi = l)
        if (finito) fai(n, k) else anteprima(n, k)
    }

    override fun corsia(c: Int) {
        if (corsie.corsiaAperta == c && barra == Barra.CORSIA) { fatto(); return }
        scelto = -1; corsie.scegli(-1)
        corsie.corsiaAperta = c
        mostra(Barra.CORSIA)
    }

    override fun loop(a: Long, b: Long) { if (suonando && (corsie.t < a || corsie.t > b)) riparti(a) }

    // ---------------------------------------------------------------- registrare la voce
    // Dalla riga bianca; gli altri suoni stanno zitti (il telefono li registrerebbe). Il file sta
    // nell'app (files/voci, come nel video) ed entra nella prima corsia libera alla riga.

    private fun registra() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permessoMicrofono.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (suonando) ferma(adesso())
        val qui = corsie.t
        if ((0 until Suono.CORSIE_MAX).all { c -> Suono.a(pezzi, c, qui) != null }) {
            avviso("All the lanes are busy at the line. Move the line to a free spot."); return
        }
        val file = java.io.File(java.io.File(filesDir, "voci").apply { mkdirs() }, "voce-${System.currentTimeMillis()}.m4a")
        val rec = try {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000); setAudioSamplingRate(44_100); setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare(); start()
            }
        } catch (_: Exception) { file.delete(); avviso("The microphone didn't start. Try again."); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        //: in cuffia il progetto suona dalla riga mentre si registra, per andare a tempo
        val uscite = (getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toIntArray()
        val ascolta = pezzi.isNotEmpty() && Suono.sonoCuffie(uscite)
        if (ascolta) {
            corsie.posizione(qui)
            daMs = qui; partito = SystemClock.elapsedRealtime(); suonando = true
            suona.setImageResource(R.drawable.ic_pausa); suona.contentDescription = "Pause"
            sincronizza()
        }
        val inizio = SystemClock.elapsedRealtime()
        val orologioVoce = TextView(this).apply {
            textSize = 34f; gravity = android.view.Gravity.CENTER; fontFeatureSettings = "tnum"
            setTextColor(ContextCompat.getColor(context, R.color.accento))
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val tic = object : Runnable {
            override fun run() { orologioVoce.text = "● " + Progetto.tempo(SystemClock.elapsedRealtime() - inizio); main.postDelayed(this, 100) }
        }
        tic.run()
        var finito = false
        fun fine(tieni: Boolean) {
            if (finito) return
            finito = true
            main.removeCallbacks(tic)
            if (suonando) ferma(qui)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            var buono = tieni
            try { rec.stop() } catch (_: Exception) { buono = false } // troppo corta: niente file valido
            rec.release()
            if (!buono) { file.delete(); return }
            val u = Uri.fromFile(file)
            lavoro.execute {
                val dur = Archivio.durataAudio(this, u)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (dur == null) { file.delete(); avviso("The recording can't be read."); return@runOnUiThread }
                    val n = pezzi.count { it.nome.startsWith("Voice") } + 1
                    val r = Suono.metti(pezzi, Musica(u.toString(), "Voice $n", dur), qui)
                    if (r == null) { file.delete(); avviso("All the lanes are busy at the line."); return@runOnUiThread }
                    fai(vivo.copy(pezzi = r.first), r.second)
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Recording your voice")
            .setMessage(when {
                ascolta -> "The project is playing in your headphones."
                pezzi.isNotEmpty() -> "Plug in headphones to hear the project while you record."
                else -> null
            })
            .setView(orologioVoce)
            .setCancelable(false)
            .setPositiveButton("Stop") { _, _ -> fine(true) }
            .setNegativeButton("Cancel") { _, _ -> fine(false) }
            .show()
    }

    // ---------------------------------------------------------------- la barra in basso

    private fun preparaBarre() {
        // il progetto
        findViewById<View>(R.id.vai_inizio).setOnClickListener { if (suonando) ferma(0) else corsie.posizione(0); scriviTempo() }
        findViewById<View>(R.id.vai_fine).setOnClickListener { val f = Suono.totaleMs(pezzi); if (suonando) ferma(f) else corsie.posizione(f); scriviTempo() }
        findViewById<View>(R.id.loop).setOnClickListener { cambiaLoop() }
        // un segnalino sulla riga bianca; di nuovo nello stesso punto lo toglie
        findViewById<View>(R.id.segna).setOnClickListener {
            val prima = vivo.segni
            val dopo = Suono.segna(prima, corsie.t)
            fai(vivo.copy(segni = dopo), scelto)
            avviso(if (dopo.size > prima.size) "Marker added — sounds snap to it" else "Marker removed")
        }
        findViewById<View>(R.id.vedi_tutto).setOnClickListener { corsie.vediTutto() }
        findViewById<View>(R.id.pareggia).setOnClickListener { pareggia() }
        findViewById<View>(R.id.aggiungi_suono).setOnClickListener { sostituisci = -1; scegliSuono.launch(arrayOf("audio/*")) }
        findViewById<View>(R.id.aggiungi_voce).setOnClickListener { registra() }

        // il suono scelto
        findViewById<View>(R.id.chiudi_suono).setOnClickListener { fatto() }
        findViewById<View>(R.id.apri_volume).setOnClickListener { mostra(Barra.VOLUME) }
        findViewById<View>(R.id.apri_fade).setOnClickListener { mostra(Barra.FADE) }
        findViewById<View>(R.id.apri_velocita).setOnClickListener { mostra(Barra.VELOCITA) }

        // la velocità come nel video: il cursore lavora in «posizioni» (-1 = 0,25× · 0 = 1× · +1 = 4×), calamite sulle tonde
        findViewById<View>(R.id.chiudi_velocita).setOnClickListener { mostra(Barra.SUONO) }
        findViewById<CursoreVista>(R.id.velocita_suono).apply {
            da = -1f; a = 1f; normale = 0f
            calamite = Progetto.CALAMITE_VELOCITA.map { Progetto.posizione(it) }.toFloatArray(); soglia = 0.035f
            passo = { p, verso -> Progetto.posizione(Progetto.passoVelocita(Progetto.daPosizione(p), verso)) }
            ascoltatore = CursoreVista.Ascoltatore { p, fine -> conVelocita(Progetto.daPosizione(p))?.let { if (fine) fai(it, scelto) else anteprima(it, scelto) } }
        }
        findViewById<View>(R.id.valore_velocita).setOnClickListener {
            val m = pezzi.getOrNull(scelto) ?: return@setOnClickListener
            numero("Speed (×)", Progetto.velocitaScritta(m.velocita).removeSuffix("×"), "0.1 – 30") { n -> conVelocita(n)?.let { fai(it, scelto) } }
        }
        preparaTasti()
        findViewById<View>(R.id.cambia_suono).setOnClickListener { sostituisci = scelto; scegliSuono.launch(arrayOf("audio/*")) }
        findViewById<View>(R.id.dividi_suono).setOnClickListener {
            val r = Suono.dividi(pezzi, scelto, corsie.t)
            if (r == null) { avviso("Put the line on the sound, a bit away from its edges, to split it"); return@setOnClickListener }
            fai(vivo.copy(pezzi = r.first), r.second)
        }
        // un colore per il suono scelto: un pallino del colore davanti a ogni nome
        findViewById<View>(R.id.colore_suono).setOnClickListener {
            val m = pezzi.getOrNull(scelto) ?: return@setOnClickListener
            val voci = (listOf("Default" to ContextCompat.getColor(this, R.color.blocco_testo)) + Suono.NOMI_COLORI.zip(Suono.COLORI.toList())).map { (n, c) ->
                android.text.SpannableString("●  $n").apply { setSpan(android.text.style.ForegroundColorSpan(c), 0, 1, 0); setSpan(android.text.style.RelativeSizeSpan(1.6f), 0, 1, 0) }
            }.toTypedArray<CharSequence>()
            MaterialAlertDialogBuilder(this).setTitle("Colour").setSingleChoiceItems(voci, m.colore) { d, k ->
                d.dismiss()
                if (k != m.colore) conScelto { it.copy(colore = k) }?.let { fai(it, scelto) }
            }.show()
        }
        // «Remove silences»: i silenzi lunghi del suono scelto spariscono, i buchi si chiudono
        findViewById<View>(R.id.togli_silenzi).setOnClickListener {
            val m = pezzi.getOrNull(scelto) ?: return@setOnClickListener
            val o = corsie.onda(m.uri)
            if (o == null) { avviso("Still reading the sound… try again in a moment"); return@setOnClickListener }
            val r = Suono.togliSilenzi(pezzi, scelto, o)
            if (r == null) { avviso("No long silences in this sound"); return@setOnClickListener }
            val resta = Suono.parlato(o, m.inizioMs, m.fineMs).sumOf { (it.last + 1 - it.first) / m.velocita.toDouble() }
            fai(vivo.copy(pezzi = r.first), r.second)
            avvisoAnnulla("Silences removed: " + Progetto.tempo(Math.round(m.suonaMs - resta)) + " shorter")
        }
        // «Clean noise»: una copia pulita del suono prende il suo posto (in tutti i pezzi dello stesso file)
        findViewById<View>(R.id.pulisci_suono).setOnClickListener { pulisci() }
        // «Crossfade»: solo quando lo chiede chi lo usa, sul suono scelto verso quello subito dopo
        findViewById<View>(R.id.passaggio_suono).setOnClickListener {
            val i = scelto
            if (Suono.passaggio(pezzi, i, 100) == null) {
                avviso("Crossfade needs another sound right after this one, in the same lane"); return@setOnClickListener
            }
            val durate = longArrayOf(1_000, 2_000, 3_000, 5_000)
            MaterialAlertDialogBuilder(this).setTitle("Crossfade into the next sound")
                .setItems(durate.map { "${it / 1000} s" }.toTypedArray()) { _, k ->
                    val r = Suono.passaggio(pezzi, i, durate[k]) ?: run { avviso("No free lane for the overlap"); return@setItems }
                    val n = r.first[r.second]
                    fai(vivo.copy(pezzi = r.first), r.second)
                    avvisoAnnulla("Crossfade %s s: “%s” moved to lane %d".format(
                        if (n.durEntra % 1000 == 0L) "${n.durEntra / 1000}" else "%.1f".format(n.durEntra / 1000f), n.nome, n.corsia + 1))
                }.show()
        }
        findViewById<View>(R.id.duplica_suono).setOnClickListener {
            val r = Suono.duplica(pezzi, scelto)
            if (r == null) { avviso("No free lane right after it for a copy"); return@setOnClickListener }
            fai(vivo.copy(pezzi = r.first), r.second)
        }
        findViewById<View>(R.id.elimina_suono).setOnClickListener {
            val i = scelto
            if (i !in pezzi.indices) return@setOnClickListener
            fai(vivo.copy(pezzi = pezzi.filterIndexed { k, _ -> k != i }), -1)
            avvisoAnnulla("Sound deleted")
        }

        // il volume del suono: 0–200% col cursore (100 al centro, calamite ogni 25, − e + di 5)
        findViewById<View>(R.id.chiudi_volume).setOnClickListener { mostra(Barra.SUONO) }
        cursoreVolume(R.id.volume_suono) { v -> conScelto { it.copy(volume = v) }?.let { it to scelto } }

        // le dissolvenze: 0 = niente, fino a 5 s, calamite sui tempi tondi
        findViewById<View>(R.id.chiudi_fade).setOnClickListener { mostra(Barra.SUONO) }
        for ((cid, entra) in listOf(R.id.fade_entra to true, R.id.fade_esce to false)) findViewById<CursoreVista>(cid).apply {
            da = 0f; a = Suono.FADE_MAX / 1000f; normale = 0f
            calamite = floatArrayOf(0f, 0.5f, 1f, 2f, 3f, 4f, 5f); soglia = 0.08f
            passo = { v, verso -> (Math.round(v * 10f + verso) / 10f) }
            ascoltatore = CursoreVista.Ascoltatore { v, fine ->
                val ms = (v * 1000).roundToInt().toLong()
                conScelto { if (entra) Suono.conEntra(it, ms) else Suono.conEsce(it, ms) }?.let { if (fine) fai(it, scelto) else anteprima(it, scelto) }
            }
        }

        // la corsia toccata
        findViewById<View>(R.id.chiudi_corsia).setOnClickListener { fatto() }
        findViewById<View>(R.id.muta_corsia).setOnClickListener { cambiaCorsia { it.copy(muto = !it.muto) } }
        findViewById<View>(R.id.solo_corsia).setOnClickListener { cambiaCorsia { it.copy(solo = !it.solo) } }
        cursoreVolume(R.id.volume_corsia) { v ->
            val c = corsie.corsiaAperta
            if (c < 0) null else Suono.conCorsia(storia.adesso, c) { it.copy(volume = v) } to scelto
        }
    }

    /** Un cursore del volume come nel video: 0–200%, 100 al centro, calamite ogni 25, − e + di 5. */
    private fun cursoreVolume(id: Int, con: (Int) -> Pair<ProgettoSuono, Int>?) {
        findViewById<CursoreVista>(id).apply {
            da = 0f; a = Progetto.VOLUME_CURSORE.toFloat(); normale = 100f
            calamite = Progetto.CALAMITE_VOLUME; soglia = Progetto.CALAMITA_VOLUME
            passo = { v, verso -> (Math.round(v / 5f) * 5f + verso * 5f) }
            ascoltatore = CursoreVista.Ascoltatore { v, fine -> con(v.roundToInt())?.let { (s, k) -> if (fine) fai(s, k) else anteprima(s, k) } }
        }
    }

    /** La velocità [v] al suono scelto: rallentato si ferma dove comincia il prossimo della corsia (Suono.conVelocita). */
    private fun conVelocita(v: Float): ProgettoSuono? {
        val i = scelto
        if (i !in storia.adesso.pezzi.indices) return null
        val nuovi = Suono.conVelocita(storia.adesso.pezzi, i, v)
        if (nuovi[i].velocita > Progetto.vicina(v) && !avvisatoPosto) { avvisatoPosto = true; avviso("It stops here: the next sound in this lane is in the way") }
        return storia.adesso.copy(pezzi = nuovi)
    }
    private var avvisatoPosto = false

    // --- il numero scritto a mano (Speed): una fila di tasti al posto della barra ---
    private class Numero(var testo: String, val fatto: (Float) -> Unit, val torna: Barra)
    private var numero: Numero? = null

    private fun preparaTasti() {
        val fila = findViewById<LinearLayout>(R.id.tasti_numero)
        val dp = resources.displayMetrics.density
        for (t in "1234567890.⌫".map { it.toString() }) fila.addView(TextView(this).apply {
            text = t; textSize = 20f; gravity = android.view.Gravity.CENTER
            contentDescription = if (t == "⌫") "Delete one" else t
            setTextColor(ContextCompat.getColor(context, R.color.testo))
            background = ContextCompat.getDrawable(context, R.drawable.fondo_valore)
            layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            }
            isClickable = true
            setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); tasto(t) }
        })
        findViewById<View>(R.id.chiudi_numero).setOnClickListener { chiudiNumero(true) }
    }

    private fun tasto(t: String) {
        val n = numero ?: return
        n.testo = when {
            t == "⌫" -> n.testo.dropLast(1)
            t == "." && n.testo.contains('.') -> n.testo
            n.testo.length >= 6 -> n.testo
            else -> n.testo + t
        }
        findViewById<TextView>(R.id.schermo_numero).apply { alpha = 1f; text = n.testo.ifEmpty { " " } }
    }

    /** Apre la fila dei tasti; «Done» scrive il numero, indietro la chiude senza. Si scrive da zero: il numero di adesso resta come suggerimento. */
    private fun numero(titolo: String, adesso: String, limiti: String, fatto: (Float) -> Unit) {
        numero = Numero("", fatto, barra)
        findViewById<TextView>(R.id.testa_numero).text = "$titolo  ·  $limiti"
        findViewById<TextView>(R.id.schermo_numero).apply { text = adesso; alpha = 0.5f }
        mostra(Barra.NUMERO)
    }

    private fun chiudiNumero(scrivi: Boolean) {
        val n = numero ?: return
        numero = null
        mostra(n.torna)
        if (scrivi && n.testo.isNotEmpty()) {
            val v = n.testo.toFloatOrNull()
            if (v == null || !v.isFinite() || v <= 0f) avviso("That's not a number") else n.fatto(v)
        }
    }

    private fun conScelto(f: (Musica) -> Musica): ProgettoSuono? {
        val i = scelto
        if (i !in storia.adesso.pezzi.indices) return null
        return storia.adesso.copy(pezzi = storia.adesso.pezzi.toMutableList().also { it[i] = f(it[i]) })
    }

    private fun cambiaCorsia(f: (Corsia) -> Corsia) {
        val c = corsie.corsiaAperta
        if (c < 0) return
        fai(Suono.conCorsia(storia.adesso, c, f), scelto)
    }

    /** Loop acceso: il suono scelto, se no 5 s dalla riga (o la parte che si vede, se è più corta). */
    private fun cambiaLoop() {
        if (corsie.loop != null) { corsie.loop = null; aggiornaBarra(); return }
        val tot = Suono.totaleMs(pezzi)
        if (tot <= 0) return
        val m = pezzi.getOrNull(scelto)
        val r = if (m != null) m.posMs..m.fineGlobaleMs else {
            val a = corsie.t.coerceAtMost((tot - LOOP_STD).coerceAtLeast(0))
            a..(a + LOOP_STD).coerceAtMost(tot)
        }
        corsie.loop = r
        if (suonando) riparti(r.first)
        aggiornaBarra()
        avviso("Drag the pink edges at the top to choose what repeats")
    }

    /**
     * «Even out»: ogni suono arriva allo stesso livello (Suono.livello / volumePari), misurato
     * sull'onda già letta. Un passo solo nella storia: Undo torna come prima.
     */
    private fun pareggia() {
        if (pezzi.isEmpty()) return
        val mancano = pezzi.count { corsie.onda(it.uri) == null }
        if (mancano > 0) { avviso("Still reading the sounds… try again in a moment"); return }
        val nuovi = pezzi.map { m ->
            val o = corsie.onda(m.uri) ?: return@map m
            val l = Suono.livello(o, m.inizioMs, m.fineMs) ?: return@map m
            m.copy(volume = Suono.volumePari(l))
        }
        if (nuovi == pezzi) { avviso("They're already even"); return }
        fai(vivo.copy(pezzi = nuovi), scelto)
        avvisoAnnulla("All sounds evened out")
    }

    private fun mostra(b: Barra) { barra = b; aggiornaBarra() }

    private fun aggiornaBarra() {
        val m = pezzi.getOrNull(scelto)
        if (m == null && barra in listOf(Barra.SUONO, Barra.VOLUME, Barra.VELOCITA, Barra.NUMERO, Barra.FADE)) { barra = Barra.PROGETTO; numero = null }
        if (barra != Barra.VELOCITA) avvisatoPosto = false
        if (barra == Barra.CORSIA && corsie.corsiaAperta < 0) barra = Barra.PROGETTO
        if (barra != Barra.CORSIA) corsie.corsiaAperta = -1
        for ((id, b) in listOf(R.id.barra_progetto to Barra.PROGETTO, R.id.barra_suono to Barra.SUONO, R.id.barra_volume to Barra.VOLUME, R.id.barra_velocita to Barra.VELOCITA, R.id.barra_numero to Barra.NUMERO,
            R.id.barra_fade to Barra.FADE, R.id.barra_corsia to Barra.CORSIA)) findViewById<View>(id).visibility = if (barra == b) View.VISIBLE else View.GONE
        acceso(R.id.loop, corsie.loop != null)
        when (barra) {
            Barra.PROGETTO, Barra.NUMERO -> {}
            Barra.VELOCITA -> {
                m!!
                findViewById<CursoreVista>(R.id.velocita_suono).valore = Progetto.posizione(m.velocita)
                findViewById<TextView>(R.id.valore_velocita).text = Progetto.velocitaScritta(m.velocita)
            }
            Barra.SUONO -> {
                m!!
                findViewById<TextView>(R.id.nome_suono).text = "${m.nome}\n${if (m.velocita == 1f) Progetto.secondi(m.suonaMs) else Progetto.secondi(m.lunghezzaMs) + " → " + Progetto.secondi(m.suonaMs)} · lane ${m.corsia + 1}"
                findViewById<MaterialButton>(R.id.apri_volume).text = if (m.volume == 0) "Muted" else "${m.volume}%"
                findViewById<MaterialButton>(R.id.apri_volume).setIconResource(if (m.volume == 0) R.drawable.ic_muto else R.drawable.ic_volume)
                acceso(R.id.apri_fade, m.entra || m.esce)
                findViewById<MaterialButton>(R.id.apri_velocita).text = Progetto.velocitaScritta(m.velocita)
                acceso(R.id.apri_velocita, m.velocita != 1f)
            }
            Barra.VOLUME -> {
                findViewById<TextView>(R.id.testa_volume).text = m!!.nome
                findViewById<CursoreVista>(R.id.volume_suono).valore = m.volume.toFloat()
                findViewById<TextView>(R.id.valore_suono).text = "${m.volume}%"
            }
            Barra.FADE -> {
                m!!
                val e = if (m.entra) m.durEntra else 0L; val u = if (m.esce) m.durEsce else 0L
                findViewById<CursoreVista>(R.id.fade_entra).valore = e / 1000f
                findViewById<CursoreVista>(R.id.fade_esce).valore = u / 1000f
                findViewById<TextView>(R.id.valore_entra).text = if (e == 0L) "Off" else Progetto.secondi(e)
                findViewById<TextView>(R.id.valore_esce).text = if (u == 0L) "Off" else Progetto.secondi(u)
            }
            Barra.CORSIA -> {
                val c = corsie.corsiaAperta
                val k = Suono.corsia(vivo, c)
                findViewById<TextView>(R.id.testa_corsia).text = "Lane ${c + 1}"
                acceso(R.id.muta_corsia, k.muto)
                acceso(R.id.solo_corsia, k.solo)
                findViewById<CursoreVista>(R.id.volume_corsia).valore = k.volume.toFloat()
                findViewById<TextView>(R.id.valore_corsia).text = "${k.volume}%"
            }
        }
    }

    /** Un bottone acceso è rosa (come Fade in/out nel video), spento è bianco. */
    private fun acceso(id: Int, si: Boolean) {
        findViewById<MaterialButton>(id).apply {
            val col = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, if (si) R.color.accento else R.color.testo))
            setTextColor(col); iconTint = col
            if (isCheckable) isChecked = si
            if (strokeWidth > 0) strokeColor = if (si) col else android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.riga))
        }
    }

    // ---------------------------------------------------------------- pulire il rumore

    private fun pulisci() {
        val m = pezzi.getOrNull(scelto) ?: return
        if (esportazione != null) return
        ferma(adesso())
        val vista = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.testo)); text = "Listening for the noise…" }
        val barra = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
            addView(vista); addView(barra)
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialogo = MaterialAlertDialogBuilder(this).setTitle("Clean noise").setView(box).setCancelable(false)
            .setNegativeButton("Stop") { _, _ -> stop.set(true) }.create()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialogo.show()
        lavoro.execute {
            val esito = try {
                Pulizia(this, m.uri, avanti = { p -> runOnUiThread { barra.isIndeterminate = false; barra.setProgressCompat(p, true); vista.text = "Cleaning… $p%" } },
                    fermato = { stop.get() || isFinishing }).fai()
            } catch (e: Exception) { e }
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (dialogo.isShowing) dialogo.dismiss()
                if (isFinishing) return@runOnUiThread
                when (esito) {
                    is Pulizia.Esito -> {
                        val nuovo = Uri.fromFile(esito.file).toString()
                        // l'utente può aver cambiato il progetto nel frattempo: si sostituisce per uri, non per posto
                        if (pezzi.none { it.uri == m.uri }) { esito.file.delete(); return@runOnUiThread }
                        val i = scelto
                        fai(vivo.copy(pezzi = pezzi.map { if (it.uri == m.uri) it.copy(uri = nuovo) else it }), i)
                        val giu = -esito.dB
                        avvisoAnnulla(if (giu < 3) "Not much background noise found (−%.0f dB)".format(giu)
                            else "Background noise lowered by %.0f dB".format(giu))
                    }
                    is Pulizia.SenzaPause -> avviso("No quiet moments to learn the noise from (music without pauses?). Nothing changed.")
                    is Exception -> avviso("Cleaning failed (${esito.message ?: esito.javaClass.simpleName}). Nothing changed.")
                    else -> {} // fermato
                }
            }
        }
    }

    // ---------------------------------------------------------------- salvare il file audio

    /** Save: prima il formato (l'ultimo scelto è già segnato, MP3 la prima volta), poi il file. */
    private fun esporta() {
        if (pezzi.isEmpty() || esportazione != null) return
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { permessoVecchio.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        ferma(adesso())
        val pref = getSharedPreferences("suono", MODE_PRIVATE)
        val tutti = FormatoSuono.entries
        var scelto = tutti.indexOf(FormatoSuono.da(pref.getString("formato", null)))
        val voci = tutti.map { f ->
            android.text.SpannableStringBuilder(f.nome).apply {
                append("   ")
                val da = length
                append(f.spiega)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.testo_piano)), da, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.85f), da, length, 0)
            }
        }.toTypedArray<CharSequence>()
        MaterialAlertDialogBuilder(this)
            .setTitle("Save as")
            .setSingleChoiceItems(voci, scelto) { _, i -> scelto = i }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val f = tutti[scelto]
                pref.edit().putString("formato", f.name).apply()
                esportaIn(f)
            }
            .show()
    }

    private fun esportaIn(formato: FormatoSuono) {
        if (esportazione != null) return
        val vista = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.testo)) }
        val barraAvanti = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
            addView(vista); addView(barraAvanti)
        }
        vista.text = "Making your ${formato.nome}…"
        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle("Save")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Stop", null)
            .create()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val e = Esporta(this, Bozza("", emptyList()), 0, 0, soloAudio = true, suono = storia.adesso, formato = formato,
            avanzamento = { p -> barraAvanti.isIndeterminate = false; barraAvanti.setProgressCompat(p, true); vista.text = "Making your ${formato.nome}… $p%" },
            fineGrezza = { esito ->
                esportazione = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (dialogo.isShowing) dialogo.dismiss()
                when (esito) {
                    is Esporta.Esito.Fatto -> salvato(esito, formato.mime)
                    is Esporta.Esito.Errore -> avviso("Saving failed (${esito.perche}). Nothing was saved.")
                    Esporta.Esito.Fermato -> {}
                }
            })
        esportazione = e
        dialogo.show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { e.ferma() }
        e.parti()
    }

    private fun salvato(e: Esporta.Esito.Fatto, mime: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved")
            .setMessage("${e.nome}\nis in Music › 151 Editor. The project stays here.")
            .setPositiveButton("Listen") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, e.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            }
            .setNeutralButton("OK", null)
            .show()
    }
}
