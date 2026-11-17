package com.the151universe.art

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * L'editor foto, in verticale. Si apre da «Edit a photo» (sceglie la foto), da Condividi /
 * Modifica di un'altra app, o da una bozza della casa. Ogni gesto si salva subito nella bozza. Cinque attrezzi: Crop (forme, gira, specchia, raddrizza),
 * Light, Filters, Text, Draw (la penna comune: [LavagnaVista] + [PannelloMano]). Ogni gesto passa da Annulla/Rifai; Save mostra il risultato
 * (tieni premuto = l'originale) e salva sempre una COPIA nuova.
 */
class FotoActivity : AppCompatActivity(), FotoVista.Ascoltatore {
    companion object {
        const val ID = "id"
        // gli stili dei testi: gli stessi degli altri editor (Ui)
        private val STILI = Ui.STILI
        private val VOCI_LUCE = listOf("Brightness", "Contrast", "Saturation", "Warmth", "Shadows", "Highlights")
    }

    private var uri: Uri? = null
    private var id: String? = null
    private var nome = ""
    private var png = false
    /** bozza nata adesso: se si esce senza aver cambiato ne' salvato niente, sparisce */
    private var nuova = false
    private var esportata = false
    private lateinit var storia: Storia<Modifiche>
    /** quello che si vede adesso: uguale a storia.adesso, salvo mentre un dito sta ancora muovendo qualcosa */
    private var vivo = Modifiche()

    private var sorgente: Bitmap? = null
    @Volatile private var colorata: Bitmap? = null
    @Volatile private var chiaveColorata: Any? = null
    private val pittore = Executors.newSingleThreadExecutor()
    private val salvataggio = Executors.newSingleThreadExecutor()
    private val attesa = AtomicReference<Modifiche?>(null)
    // solo sul filo del pittore
    private var orientata: Bitmap? = null
    private var chiaveOrientata: Pair<Int, Boolean>? = null
    private var tavolozza = IntArray(0)

    private lateinit var vista: FotoVista
    private lateinit var lavagna: LavagnaVista
    private var attrezzo = 0
    private var scelto: Long? = null
    private var voceLuce = 0
    private val dp by lazy { resources.displayMetrics.density }

    private val scegli = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u == null) finish() else nuova(u)
    }

    private val permessoVecchio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) avviso("151 Editor needs storage access to save the photo on this phone.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_foto)
        vista = findViewById(R.id.vista)
        vista.ascoltatore = this
        storia = Storia(Modifiche())
        lavagna = findViewById(R.id.lavagna)
        lavagna.cornice = { vista.cornice() }
        lavagna.taglio = { vista.taglio() }
        lavagna.ascoltatore = LavagnaVista.Ascoltatore { t, fine ->
            val m = storia.adesso.copy(tratti = t)
            if (fine) fai(m) else anteprima(m)
        }
        findViewById<FrameLayout>(R.id.p_mano).addView(PannelloMano(this, lavagna, null).vista)

        findViewById<View>(R.id.indietro).setOnClickListener { esci() }
        findViewById<View>(R.id.annulla).setOnClickListener { if (storia.annulla()) { dopoStoria(); memorizza() } }
        findViewById<View>(R.id.rifai).setOnClickListener { if (storia.rifai()) { dopoStoria(); memorizza() } }
        findViewById<View>(R.id.salva).setOnClickListener { apriSalva() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // prima «Done» (il testo scelto si lascia andare), poi esce
            override fun handleOnBackPressed() { if (scelto != null) lasciaTesto() else esci() }
        })

        listOf(R.id.tab_ritaglio, R.id.tab_luce, R.id.tab_filtri, R.id.tab_testo, R.id.tab_disegno).forEachIndexed { i, id ->
            findViewById<View>(id).setOnClickListener { mostraAttrezzo(i) }
        }
        preparaRitaglio(); preparaLuce(); preparaFiltri(); preparaTesto()
        mostraAttrezzo(0)
        aggiornaBottoni()

        val bozza = savedInstanceState?.getString(ID) ?: intent?.getStringExtra(ID)
        val daFuori = when (intent?.action) {
            Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        when {
            bozza != null -> apriBozza(bozza)
            daFuori != null && savedInstanceState == null -> nuova(daFuori)
            savedInstanceState == null -> scegli.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            else -> finish()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        id?.let { out.putString(ID, it) }
    }

    override fun onDestroy() {
        pittore.shutdownNow(); salvataggio.shutdown()
        super.onDestroy()
    }

    private fun avviso(t: String) = Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_LONG).show()

    // ---------------------------------------------------------------- caricare

    /** Una foto nuova diventa subito una bozza (che sopravvive anche se Android chiude l'app). */
    private fun nuova(u: Uri) {
        findViewById<View>(R.id.carica).visibility = View.VISIBLE
        pittore.execute {
            val nuovoId = Archivio.nuovoId()
            val buona = Archivio.fotoDa(this, u, nuovoId)
            val b = if (buona == null) null else BozzaFoto(
                "Photo ${Archivio.elencoFoto(this).size + 1}", buona.toString(), Modifiche(), SalvaFoto.png(this, u),
            )
            val scritta = b != null && Archivio.salvaFoto(this, nuovoId, b)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (b == null || !scritta) { nonSiApre(); return@runOnUiThread }
                id = nuovoId; nome = b.nome; png = b.png; nuova = true
                carica(Uri.parse(b.uri), b.m)
            }
        }
    }

    private fun apriBozza(bid: String) {
        val b = Archivio.caricaFoto(this, bid) ?: return finish()
        id = bid; nome = b.nome; png = b.png
        carica(Uri.parse(b.uri), b.m)
    }

    private fun nonSiApre() {
        findViewById<View>(R.id.carica).visibility = View.GONE
        MaterialAlertDialogBuilder(this)
            .setTitle("This photo can't be opened")
            .setMessage("The phone didn't let 151 Editor read it. Try picking it again from Edit a photo.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun carica(u: Uri, stato: Modifiche?) {
        uri = u
        findViewById<View>(R.id.carica).visibility = View.VISIBLE
        pittore.execute {
            val b = try { SalvaFoto.anteprima(this, u) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                findViewById<View>(R.id.carica).visibility = View.GONE
                if (b == null) { nonSiApre(); return@runOnUiThread }
                sorgente = b
                val m = stato ?: Modifiche()
                storia = Storia(m); vivo = m
                findViewById<View>(R.id.salva).isEnabled = true
                miniatureFiltri(b)
                dopoStoria()
            }
        }
    }

    // ---------------------------------------------------------------- disegnare

    private fun chiaveColore(m: Modifiche): Any = listOf(m.giri, m.specchio, m.luce, m.filtro, m.forza)

    /** Mostra [vivo]. Geometria e testi subito; il colore (e i giri) li rifa' il pittore, sempre l'ultima richiesta. */
    private fun ridisegna() {
        val c = colorata
        val pronta = c != null && chiaveColorata == chiaveColore(vivo)
        val stessaForma = c != null && (chiaveColorata as? List<*>)?.let { it[0] == vivo.giri && it[1] == vivo.specchio } == true
        if (stessaForma) vista.mostra(c, vivo)
        if (!pronta && sorgente != null) {
            if (attesa.getAndSet(vivo) == null) pittore.execute(colora)
        }
    }

    private val colora = Runnable {
        while (true) {
            val m = attesa.get() ?: break
            val src = sorgente ?: break
            val chiave = chiaveColore(m)
            val ok = try {
                val o = m.giri to m.specchio
                if (chiaveOrientata != o) {
                    // niente recycle: la vecchia puo' essere ancora sullo schermo; ci pensa il GC
                    orientata = Disegno.orienta(src, m.giri, m.specchio); chiaveOrientata = o
                }
                val base = orientata!!
                val out = if (Foto.coloreNeutro(m)) base else {
                    if (tavolozza.size != base.width * base.height) tavolozza = IntArray(base.width * base.height)
                    val px = tavolozza // riusata: createBitmap copia, cosi' il dito che scorre non sporca la memoria
                    base.getPixels(px, 0, base.width, 0, 0, base.width, base.height)
                    Foto.colora(px, m)
                    Bitmap.createBitmap(px, base.width, base.height, Bitmap.Config.ARGB_8888)
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    colorata = out; chiaveColorata = chiave
                    val st = vivo
                    if (st.giri == m.giri && st.specchio == m.specchio) vista.mostra(out, st)
                }
                true
            } catch (_: OutOfMemoryError) { false }
            if (!ok) { attesa.set(null); break }
            if (attesa.compareAndSet(m, null)) break
        }
    }

    // ---------------------------------------------------------------- storia

    /** Un gesto finito: entra nella storia (annulla/rifai). */
    private fun fai(m: Modifiche) {
        storia.fai(m); vivo = storia.adesso
        dopoGesto(); memorizza()
    }

    /** La bozza su disco = quello che c'e' adesso (scrittura atomica, pochi byte). */
    private fun memorizza() {
        val i = id ?: return
        val u = uri ?: return
        if (sorgente == null) return
        if (!Archivio.salvaFoto(this, i, BozzaFoto(nome, u.toString(), storia.adesso, png))) {
            avviso("The phone refused to save the draft.")
        }
    }

    /** Un gesto ancora in corso: si vede ma non entra nella storia. */
    private fun anteprima(m: Modifiche) { vivo = m; ridisegna() }

    private fun dopoStoria() {
        vivo = storia.adesso
        if (scelto != null && vivo.testi.none { it.id == scelto }) scelto = null
        dopoGesto()
    }

    /** «Done» sul testo scelto. */
    private fun lasciaTesto() {
        scelto = null; vista.scelto = null; aggiornaPannelli()
    }

    private fun dopoGesto() {
        vista.scelto = scelto
        lavagna.tratti = storia.adesso.tratti
        ridisegna(); aggiornaBottoni(); aggiornaPannelli()
    }

    private fun aggiornaBottoni() {
        findViewById<TextView>(R.id.nome_progetto).text = nome.ifBlank { "Photo" }
        for ((id, ok) in listOf(R.id.annulla to storia.puoiAnnullare, R.id.rifai to storia.puoiRifare)) {
            findViewById<View>(id).apply { isEnabled = ok; alpha = if (ok) 1f else EditorActivity.SPENTO }
        }
    }

    /** Si esce sempre senza domande: tutto e' gia' nella bozza. Una foto appena aperta e mai toccata non resta fra le bozze. */
    private fun esci() {
        val i = id
        if (i != null && nuova && !esportata && storia.adesso == Modifiche()) Archivio.eliminaFoto(this, i)
        finish()
    }

    // ---------------------------------------------------------------- pezzi dei pannelli

    /** Una «pillola» da toccare: testo, scelta = bordo e testo accesi. */
    private fun pillola(testo: String, alTocco: () -> Unit): TextView = TextView(this).apply {
        text = testo
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = (44 * dp).toInt()
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        setTextColor(ContextCompat.getColor(context, R.color.testo))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply {
            marginEnd = (6 * dp).toInt(); topMargin = (2 * dp).toInt(); bottomMargin = (2 * dp).toInt()
        }
        setOnClickListener { alTocco() }
        segna(this, false)
    }

    private fun segna(v: View, si: Boolean) {
        v.background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(ContextCompat.getColor(this@FotoActivity, if (si) R.color.riga else R.color.fondo))
            setStroke((1.5f * dp).toInt(), ContextCompat.getColor(this@FotoActivity, if (si) R.color.accento else R.color.riga))
        }
        if (v is TextView) v.setTextColor(ContextCompat.getColor(this, if (si) R.color.accento else R.color.testo))
    }

    private fun mostraAttrezzo(i: Int) {
        attrezzo = i
        val pannelli = listOf(R.id.p_ritaglio, R.id.p_luce, R.id.p_filtri, R.id.p_testo, R.id.p_mano)
        val tab = listOf(R.id.tab_ritaglio, R.id.tab_luce, R.id.tab_filtri, R.id.tab_testo, R.id.tab_disegno)
        // INVISIBLE, non GONE: il pannello resta alto uguale e la foto non salta
        pannelli.forEachIndexed { k, id -> findViewById<View>(id).visibility = if (k == i) View.VISIBLE else View.INVISIBLE }
        tab.forEachIndexed { k, id ->
            val b = findViewById<MaterialButton>(id)
            val c = ColorStateList.valueOf(ContextCompat.getColor(this, if (k == i) R.color.accento else R.color.testo))
            b.setTextColor(c); b.iconTint = c
        }
        vista.ritagliando = i == 0
        lavagna.attiva = i == 4
        if (i != 3 && scelto != null) { scelto = null; vista.scelto = null }
        aggiornaPannelli()
    }

    private fun aggiornaPannelli() {
        val m = vivo
        // ritaglio
        val rapporti = findViewById<LinearLayout>(R.id.rapporti)
        Foto.RAPPORTI.forEachIndexed { k, (id, _) -> rapporti.getChildAt(k)?.let { segna(it, id == m.rapporto) } }
        val rad = findViewById<Slider>(R.id.raddrizza)
        if (!toccando(rad)) rad.value = m.raddrizza.coerceIn(-Foto.RADDRIZZA_MAX, Foto.RADDRIZZA_MAX)
        findViewById<TextView>(R.id.gradi).text = "%.1f°".format(m.raddrizza).replace(".0°", "°")
        // luce
        val voci = findViewById<LinearLayout>(R.id.voci_luce)
        VOCI_LUCE.forEachIndexed { k, nome ->
            (voci.getChildAt(k) as? TextView)?.let {
                val v = valoreLuce(m.luce, k)
                it.text = if (v == 0) nome else "$nome  ${if (v > 0) "+" else ""}$v"
                segna(it, k == voceLuce)
            }
        }
        val cl = findViewById<Slider>(R.id.cursore_luce)
        if (!toccando(cl)) cl.value = valoreLuce(m.luce, voceLuce).toFloat()
        findViewById<TextView>(R.id.valore_luce).text = valoreLuce(m.luce, voceLuce).toString()
        // filtri
        val filtri = findViewById<LinearLayout>(R.id.filtri)
        Foto.FILTRI.forEachIndexed { k, f -> filtri.getChildAt(k)?.let { segnaFiltro(it, f.id == m.filtro) } }
        findViewById<View>(R.id.riga_forza).visibility = if (m.filtro.isEmpty()) View.INVISIBLE else View.VISIBLE
        val fz = findViewById<Slider>(R.id.forza)
        if (!toccando(fz)) fz.value = m.forza.coerceIn(0, 100).toFloat()
        findViewById<TextView>(R.id.valore_forza).text = m.forza.toString()
        // testo
        val t = m.testi.firstOrNull { it.id == scelto }
        findViewById<View>(R.id.testo_vuoto).visibility = if (t == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.testo_scelto).visibility = if (t == null) View.INVISIBLE else View.VISIBLE
        if (t != null) {
            findViewById<TextView>(R.id.testa_testo).text = "“" + t.testo.replace('\n', ' ') + "”"
            val font = findViewById<LinearLayout>(R.id.font)
            Ui.righeFont(this, font, 44) { id -> cambiaTesto { it.copy(font = id) } }
            Ui.segnaFont(font, t.font)
            val colori = findViewById<LinearLayout>(R.id.colori)
            Ui.righeColori(this, colori, Ui.COLORI) { c -> cambiaTesto { it.copy(colore = c) } }
            Ui.segnaColori(colori, t.colore)
            val stili = findViewById<LinearLayout>(R.id.stili)
            STILI.forEachIndexed { k, (s, _) -> stili.getChildAt(k)?.let { segna(it, s == t.stile) } }
            opzioniStile.mostra(t.stile, t.secondo, t.spessore)
            val mi = findViewById<Slider>(R.id.misura)
            if (!toccando(mi)) mi.value = t.misura.coerceIn(Testo.MISURA_MIN, Testo.MISURA_MAX)
        }
    }

    private lateinit var opzioniStile: OpzioniStile

    private val toccati = HashSet<Slider>()
    private fun toccando(s: Slider) = s in toccati

    /** Un cursore: mentre scorre si vede, quando si lascia entra nella storia. */
    private fun cursore(id: Int, mentre: (Float) -> Modifiche?) {
        val s = findViewById<Slider>(id)
        s.addOnChangeListener { _, v, daDito -> if (daDito) mentre(v)?.let { anteprima(it); aggiornaPannelli() } }
        s.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(sl: Slider) { toccati.add(sl) }
            override fun onStopTrackingTouch(sl: Slider) {
                toccati.remove(sl)
                fai(mentre(sl.value) ?: vivo)
            }
        })
    }

    // ---------------------------------------------------------------- ritaglio

    private fun preparaRitaglio() {
        val rapporti = findViewById<LinearLayout>(R.id.rapporti)
        for ((id, nome) in Foto.RAPPORTI) rapporti.addView(pillola(nome) {
            val b = sorgente ?: return@pillola
            fai(Foto.conRapporto(vivo, id, b.width, b.height))
        })
        findViewById<View>(R.id.ruota).setOnClickListener {
            val b = sorgente ?: return@setOnClickListener
            fai(Foto.gira(vivo, b.width, b.height))
        }
        findViewById<View>(R.id.specchia).setOnClickListener { if (sorgente != null) fai(Foto.specchia(vivo)) }
        cursore(R.id.raddrizza) { v ->
            // vicino allo zero si aggancia allo zero: dritta davvero, senza inseguire il decimo
            vivo.copy(raddrizza = if (kotlin.math.abs(v) < 0.6f) 0f else (v * 2).roundToInt() / 2f)
        }
        findViewById<View>(R.id.azzera_geometria).setOnClickListener {
            fai(vivo.copy(giri = 0, specchio = false, raddrizza = 0f, ritaglio = Ritaglio(), rapporto = "free"))
        }
    }

    override fun ritaglio(r: Ritaglio, fine: Boolean) {
        val m = vivo.copy(ritaglio = r)
        if (fine) fai(m) else anteprima(m)
    }

    // ---------------------------------------------------------------- luce

    private fun valoreLuce(l: Luce, k: Int) = when (k) {
        0 -> l.luminosita; 1 -> l.contrasto; 2 -> l.saturazione; 3 -> l.calore; 4 -> l.ombre; else -> l.luci
    }

    private fun conLuce(l: Luce, k: Int, v: Int) = when (k) {
        0 -> l.copy(luminosita = v); 1 -> l.copy(contrasto = v); 2 -> l.copy(saturazione = v)
        3 -> l.copy(calore = v); 4 -> l.copy(ombre = v); else -> l.copy(luci = v)
    }

    private fun preparaLuce() {
        val voci = findViewById<LinearLayout>(R.id.voci_luce)
        VOCI_LUCE.forEachIndexed { k, nome -> voci.addView(pillola(nome) { voceLuce = k; aggiornaPannelli() }) }
        cursore(R.id.cursore_luce) { v -> vivo.copy(luce = conLuce(vivo.luce, voceLuce, v.roundToInt().coerceIn(-100, 100))) }
        findViewById<View>(R.id.azzera_luce).setOnClickListener { fai(vivo.copy(luce = conLuce(vivo.luce, voceLuce, 0))) }
    }

    // ---------------------------------------------------------------- filtri

    private fun preparaFiltri() {
        val riga = findViewById<LinearLayout>(R.id.filtri)
        for (f in Foto.FILTRI) {
            val casella = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (2 * dp).toInt() }
                contentDescription = "Filter ${f.nome}"
                setOnClickListener { fai(vivo.copy(filtro = f.id, forza = if (f.id == vivo.filtro) vivo.forza else 100)) }
            }
            casella.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ContextCompat.getColor(context, R.color.riga))
                setPadding((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
            })
            casella.addView(TextView(this).apply {
                text = f.nome; textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, (3 * dp).toInt(), 0, 0)
            })
            riga.addView(casella)
        }
        cursore(R.id.forza) { v -> vivo.copy(forza = v.roundToInt().coerceIn(0, 100)) }
    }

    private fun segnaFiltro(casella: View, si: Boolean) {
        val g = casella as LinearLayout
        val img = g.getChildAt(0) as ImageView
        img.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@FotoActivity, if (si) R.color.accento else R.color.riga))
        }
        (g.getChildAt(1) as TextView).setTextColor(ContextCompat.getColor(this, if (si) R.color.accento else R.color.testo))
    }

    /** Le miniature dei filtri, dal centro della foto: si fanno una volta, sul filo del pittore. */
    private fun miniatureFiltri(b: Bitmap) {
        pittore.execute {
            val lato = minOf(b.width, b.height)
            val quadrato = Bitmap.createBitmap(b, (b.width - lato) / 2, (b.height - lato) / 2, lato, lato)
            val piccola = Bitmap.createScaledBitmap(quadrato, 160, 160, true)
            val px = IntArray(160 * 160)
            val fatte = Foto.FILTRI.map { f ->
                piccola.getPixels(px, 0, 160, 0, 0, 160, 160)
                Foto.colora(px, Modifiche(filtro = f.id))
                Bitmap.createBitmap(px, 160, 160, Bitmap.Config.ARGB_8888)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val riga = findViewById<LinearLayout>(R.id.filtri)
                fatte.forEachIndexed { k, m -> ((riga.getChildAt(k) as? LinearLayout)?.getChildAt(0) as? ImageView)?.setImageBitmap(m) }
            }
        }
    }

    // ---------------------------------------------------------------- testo

    private fun preparaTesto() {
        findViewById<View>(R.id.aggiungi_testo).setOnClickListener { scrivi(null) }
        // font e colori: le righe comuni a tutti gli editor (Ui), coi fissati per primi
        val stili = findViewById<LinearLayout>(R.id.stili)
        for ((s, nome) in STILI) stili.addView(pillola(nome) { cambiaTesto { it.copy(stile = s) } })
        // sotto gli stili: colore e spessore di «Outline», colore di «Box»
        opzioniStile = OpzioniStile(this) { sec, sp, fine ->
            val id = scelto ?: return@OpzioniStile
            val m = storia.adesso.copy(testi = storia.adesso.testi.map {
                if (it.id == id) it.copy(secondo = sec ?: it.secondo, spessore = sp ?: it.spessore) else it
            })
            if (fine) fai(m) else { anteprima(m); aggiornaPannelli() }
        }
        (stili.parent as View).let { riga -> (riga.parent as ViewGroup).let { it.addView(opzioniStile, it.indexOfChild(riga) + 1) } }
        findViewById<View>(R.id.riscrivi).setOnClickListener { scelto?.let { scrivi(it) } }
        findViewById<View>(R.id.chiudi_testo).setOnClickListener { lasciaTesto() }
        findViewById<View>(R.id.duplica_testo).setOnClickListener {
            val t = vivo.testi.firstOrNull { it.id == scelto } ?: return@setOnClickListener
            // la copia poco più in basso, così si vede che sono due
            val c = t.copy(id = System.currentTimeMillis(), y = (t.y + 0.07f).let { if (it > 0.95f) t.y - 0.07f else it })
            scelto = c.id
            fai(storia.adesso.copy(testi = storia.adesso.testi + c))
        }
        findViewById<View>(R.id.elimina_testo).setOnClickListener {
            val id = scelto ?: return@setOnClickListener
            scelto = null
            fai(storia.adesso.copy(testi = storia.adesso.testi.filter { it.id != id }))
            Snackbar.make(findViewById(R.id.radice), "Text deleted", Snackbar.LENGTH_LONG)
                .setAction("Undo") { if (storia.annulla()) { dopoStoria(); memorizza() } }.show()
        }
        cursore(R.id.misura) { v ->
            val id = scelto ?: return@cursore null
            vivo.copy(testi = vivo.testi.map { if (it.id == id) it.copy(misura = v) else it })
        }
    }

    private fun cambiaTesto(f: (Testo) -> Testo) {
        val id = scelto ?: return
        fai(vivo.copy(testi = vivo.testi.map { if (it.id == id) f(it) else it }))
    }

    /** Scrivere un testo nuovo ([id] null) o riscriverne uno. Il nuovo prende font, colore e stile dell'ultimo. */
    private fun scrivi(id: Long?) {
        val vecchio = vivo.testi.firstOrNull { it.id == id }
        val campo = EditText(this).apply {
            setText(vecchio?.testo ?: ""); setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1; maxLines = 5
        }
        val box = FrameLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(campo) }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(if (vecchio == null) "Add text" else "Edit text")
            .setView(box)
            .setPositiveButton(if (vecchio == null) "Add" else "Done") { _, _ ->
                val s = campo.text.toString().trimEnd()
                if (s.isBlank()) return@setPositiveButton
                if (vecchio != null) { cambiaTesto { it.copy(testo = s) }; return@setPositiveButton }
                val ultimo = vivo.testi.lastOrNull()
                val nuovo = Testo(
                    id = System.currentTimeMillis(), testo = s,
                    font = ultimo?.font ?: Caratteri.PRIMO,
                    colore = ultimo?.colore ?: 0xFFFFFFFF.toInt(),
                    stile = ultimo?.stile ?: Testo.OMBRA,
                    secondo = ultimo?.secondo ?: 0, spessore = ultimo?.spessore ?: Testo.SPESSORE_STD,
                )
                scelto = nuovo.id
                if (attrezzo != 3) mostraAttrezzo(3)
                fai(vivo.copy(testi = vivo.testi + nuovo))
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        campo.requestFocus()
    }

    override fun testoScelto(id: Long?) {
        scelto = id
        if (id != null && attrezzo != 3) mostraAttrezzo(3)
        vista.scelto = id
        aggiornaPannelli()
    }

    override fun testoSposta(id: Long, x: Float, y: Float, fine: Boolean) {
        val m = vivo.copy(testi = vivo.testi.map { if (it.id == id) it.copy(x = x, y = y) else it })
        if (fine) fai(m) else anteprima(m)
    }

    override fun testoMisura(id: Long, misura: Float, fine: Boolean) {
        val m = vivo.copy(testi = vivo.testi.map { if (it.id == id) it.copy(misura = misura) else it })
        if (fine) fai(m) else { anteprima(m); aggiornaPannelli() }
    }

    override fun testoRiscrivi(id: Long) { scelto = id; vista.scelto = id; scrivi(id) }

    // ---------------------------------------------------------------- salvare

    /** Il risultato in piccolo, per lo schermo del salvataggio: stessa ricetta del file. */
    private fun risultatoPiccolo(): Bitmap? {
        val c = colorata ?: return null
        return SalvaFoto.ricetta(this, c, vivo)
    }

    private fun apriSalva() {
        val u = uri ?: return
        val orig = sorgente ?: return
        if (chiaveColorata != chiaveColore(vivo)) { avviso("One moment…"); return }
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { permessoVecchio.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        val finita = risultatoPiccolo() ?: return
        val m = storia.adesso
        val d = Dialog(this, R.style.Theme_Art)
        d.setContentView(R.layout.dialog_salva)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val img = d.findViewById<ImageView>(R.id.finita)
        val confronto = d.findViewById<TextView>(R.id.confronto)
        val stato = d.findViewById<TextView>(R.id.stato)
        val sinistra = d.findViewById<MaterialButton>(R.id.sinistra)
        val destra = d.findViewById<MaterialButton>(R.id.destra)
        img.setImageBitmap(finita)
        stato.text = "Saves a new copy. Your original stays as it is."
        img.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { img.setImageBitmap(orig); confronto.text = "Original"; confronto.setTypeface(null, Typeface.BOLD) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    img.setImageBitmap(finita); confronto.text = "Hold the photo to see the original"; confronto.setTypeface(null, Typeface.NORMAL)
                    if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
        sinistra.setOnClickListener { d.dismiss() }
        destra.setOnClickListener {
            destra.isEnabled = false; sinistra.isEnabled = false
            stato.text = "Saving…"
            salvataggio.execute {
                val esito: Any = try {
                    val grande = SalvaFoto.componi(this, u, m)
                    try { SalvaFoto.inGalleria(this, grande, png) } finally { grande.recycle() }
                } catch (e: OutOfMemoryError) { "the photo is too big for this phone's memory" }
                catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    sinistra.isEnabled = true; destra.isEnabled = true
                    @Suppress("UNCHECKED_CAST")
                    val fatto = esito as? Pair<Uri, String>
                    if (fatto == null) {
                        stato.text = "Not saved: $esito"
                        return@runOnUiThread
                    }
                    esportata = true
                    stato.text = "Saved in Pictures › 151 Editor\n${fatto.second}"
                    sinistra.text = "Share"
                    sinistra.setOnClickListener {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = if (fatto.second.endsWith(".png")) "image/png" else "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, fatto.first)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share photo"))
                    }
                    destra.text = "Done"
                    destra.setOnClickListener { d.dismiss() }
                }
            }
        }
        d.show()
    }
}
