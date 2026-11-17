package com.the151universe.art

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * L'editor «Design»: volantini A4, post quadrati, storie verticali. Si apre da «New project» →
 * Design (prima si sceglie formato o modello) o da una bozza della casa. Ogni gesto si salva
 * subito nella bozza; Annulla/Rifai per tutto; Save fa sempre un file NUOVO (PNG o PDF).
 * L'ispettore sotto mostra SOLO la cosa scelta, sempre alto uguale.
 */
class GraficaActivity : AppCompatActivity(), GraficaVista.Ascoltatore {
    companion object {
        const val ID = "id"
        private const val NUOVA = "nuova"
        private const val PER_FOTO = "perFoto"
        private const val PER_SFONDO = "perSfondo"
        private const val LATO_FOTO = 1600
    }

    private var id: String? = null
    private var nuova = false
    private var esportata = false
    private lateinit var storia: Storia<Grafica>
    /** quello che si vede: uguale a storia.adesso, salvo mentre un dito o un cursore sta ancora muovendo */
    private var vivo: Grafica = Grafiche.nuova("", Formato.A4)
    private var scelto: Long? = null
    /** cosa mostra l'ispettore quando niente è scelto */
    private var modo = Modo.AGGIUNGI
    private enum class Modo { AGGIUNGI, FORME, PAGINA, DISEGNO }
    /** il disegno che la penna sta facendo (null = il prossimo tratto ne fa nascere uno) */
    private var disegnoId: Long? = null
    /** la pagina aperta (0 = la prima) */
    private var pagina = 0
    /** la cella scelta della tabella scelta (riga, colonna) */
    private var cella: Pair<Int, Int>? = null
    /** «Trimmed»: il foglio senza la fascia dell'abbondanza (solo come si guarda, non si salva) */
    private var tagliata = false
    /** margini e colonne visibili (e calamita); solo come si guarda, non si salva */
    private var conGuide = true
    /** libro aperto: due pagine affiancate (si ricorda fra un progetto e l'altro) */
    private var affiancate = false
    /** il pezzo scelto del testo scelto (lettere da..a esclusa); null = la riga intera */
    private var pezzo: IntRange? = null
    /** la barra sopra la tastiera mentre si scrive sulla pagina */
    private lateinit var barraTastiera: LinearLayout
    private lateinit var attrezzi: View
    private lateinit var filaPagineVista: View
    private lateinit var corniceVista: FrameLayout

    private lateinit var vista: GraficaVista
    private lateinit var lavagna: LavagnaVista
    private val penna by lazy { PannelloMano(this, lavagna) { finisciDisegno() } }
    private lateinit var pannello: LinearLayout
    private lateinit var cornicePannello: View
    private val dp by lazy { resources.displayMetrics.density }

    // le foto, lette fuori dal filo principale; la vista ridisegna quando arrivano
    private val foto = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inArrivo = HashSet<String>()
    private val illeggibili = HashSet<String>()
    private val lettore = Executors.newSingleThreadExecutor()
    private val salvataggio = Executors.newSingleThreadExecutor()
    private val bozza = Executors.newSingleThreadExecutor()
    private val inCoda = java.util.concurrent.atomic.AtomicReference<Pair<String, Grafica>?>(null)

    /** dove va la prossima foto scelta: un oggetto nuovo, la sostituzione di uno, o lo sfondo */
    private var perFoto: Long? = null
    private var fotoPerSfondo = false

    private val scegliFoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) arrivaFoto(u)
    }

    // «Open a PDF»: ogni pagina diventa una pagina del design (ApriPdf)
    private val scegliPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) apriPdf(u)
    }

    // «Cards from a list»: un file di testo con la lista (txt, csv) dal telefono, senza permessi
    private var listaArrivata: ((String) -> Unit)? = null
    private val scegliLista = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@registerForActivityResult
        val t = try { contentResolver.openInputStream(u)?.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) } } catch (_: Exception) { null }
        if (t == null) avviso("This file can't be read") else listaArrivata?.invoke(t.removePrefix("\uFEFF"))
    }

    /**
     * Una carta + una lista = tutte le carte ([Carte]): la pagina scelta è il modello, i suoi {campi} si riempiono
     * con le righe; nasce un progetto nuovo, il modello resta com'è.
     */
    // le foto delle carte: molte in un gesto, coi loro nomi (cat.jpg va sulla carta «cat»)
    private var fotoArrivate: ((List<Uri>) -> Unit)? = null
    private val scegliFotoCarte = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { u ->
        if (u.isNotEmpty()) fotoArrivate?.invoke(u)
    }

    private fun nomeDi(u: Uri): String = try {
        contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null } ?: (u.lastPathSegment ?: "")

    private fun carteDaLista() {
        val g = storia.adesso
        val p = pagina.coerceAtLeast(0)
        // il retro: la pagina dopo (o prima, se questa è l'ultima); scelto da sé se il design è proprio una carta a due facce
        val altra = if (g.pagine >= 2) (if (p + 1 < g.pagine) p + 1 else p - 1) else null
        var retro: Int? = if (g.pagine == 2) altra else null
        val campi = Carte.campi(g, p, altra)
        if (campi.isEmpty()) {
            MaterialAlertDialogBuilder(this).setTitle("Cards from a list")
                .setMessage("This page is the card. Write {Title} in a text where the first column goes, {Text} where the second goes (any name in curly brackets works). Then come back here and give the list.")
                .setPositiveButton("Add {Title} and {Text}") { _, _ ->
                    var n = storia.adesso
                    for ((i, t) in listOf("{Title}", "{Text}").withIndex()) {
                        val base = Elemento(0, Elemento.TESTO, 0f, 0f, Grafica.LARGO * 0.8f, 100f, testo = t, misura = if (i == 0) 120f else 80f,
                            colore = Grafiche.contrario(n.sfondo), allinea = Elemento.CENTRO, font = carattereNuovo(n), segni = if (i == 0) listOf(Segno(0, t.length, Ricco.GRASSETTO)) else emptyList())
                        n = Grafiche.aggiungi(n, base.copy(h = GraficaDisegno.altezzaTesto(this, base)), p, Grafica.LARGO / 2f to n.alto * (if (i == 0) 0.42f else 0.6f))
                    }
                    fai(n)
                }
                .setNegativeButton("OK", null).show()
            return
        }
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4; maxLines = 6; gravity = Gravity.TOP or Gravity.START; textSize = 15f
            hint = campi.joinToString(" ; ") + "\n" + campi.mapIndexed { i, _ -> listOf("cat", "gatto", "the cat sleeps").getOrElse(i) { "…" } }.joinToString(" ; ")
        }
        val conto = TextView(this).apply { textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.testo_piano)) }
        // le foto scelte: (nome del file, copia dentro l'app)
        var foto: List<Pair<String, String>> = emptyList()
        var importando = false
        val fotoTesto = TextView(this).apply { textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.testo_piano)); setPadding(0, 0, 0, (6 * dp).toInt()) }
        fun conta() {
            val righe = Carte.righe(campo.text.toString(), campi)
            val n = righe.size
            val max = Grafica.PAGINE_MAX / (if (retro != null) 2 else 1)
            conto.text = if (n == 0) "One card per line: ${campi.joinToString(" ; ")}" else "$n card" + (if (n == 1) "" else "s") + (if (retro != null) " · front and back" else "") + (if (n > max) " (the most is $max)" else "")
            fotoTesto.text = when {
                importando -> "Reading the photos…"
                foto.isEmpty() -> "Photos: none. One per card, by name (cat.jpg goes on «cat») or in order."
                else -> {
                    val a = Carte.abbina(righe, foto.map { it.first })
                    val perNome = righe.isNotEmpty() && a.any { it != null } && foto.any { f -> righe.any { r -> r.any { Carte.chiave(it) == Carte.chiave(f.first) } } }
                    "${foto.size} photo" + (if (foto.size == 1) "" else "s") + when {
                        n == 0 -> ""
                        perNome -> " · ${a.count { it != null }} of $n cards by name"
                        else -> " · in order, ${minOf(foto.size, n)} of $n cards"
                    }
                }
            }
        }
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) = conta()
        })
        conta()
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val scelte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun righeScelte() {
            scelte.removeAllViews()
            val fuori = pannello
            pannello = scelte
            try {
                if (altra != null) riga().apply {
                    setPadding(0, 0, 0, 0)
                    addView(etichetta("Back"))
                    addView(pillola("None", retro == null, uguale = true) { retro = null; righeScelte(); conta() })
                    addView(pillola("Page ${altra + 1}", retro != null, uguale = true) { retro = altra; righeScelte(); conta() })
                }
                riga().apply {
                    setPadding(0, 0, 0, 0)
                    addView(etichetta("Photos"))
                    addView(pillola(if (foto.isEmpty()) "Choose…" else "Change…", false, uguale = true) {
                        if (importando) return@pillola
                        fotoArrivate = { uris ->
                            importando = true; conta()
                            lettore.execute {
                                val prese = uris.take(Grafica.PAGINE_MAX).mapNotNull { u -> Archivio.importaFoto(this@GraficaActivity, u)?.let { nomeDi(u) to it } }
                                runOnUiThread {
                                    importando = false
                                    if (prese.size < uris.size) avviso("${uris.size - prese.size} photos can't be read")
                                    foto = prese; righeScelte(); conta()
                                }
                            }
                        }
                        scegliFotoCarte.launch(arrayOf("image/*"))
                    })
                    if (foto.isNotEmpty()) addView(pillola("None", false, uguale = true) { foto = emptyList(); righeScelte(); conta() })
                }
            } finally { pannello = fuori }
        }
        righeScelte()
        corpo.addView(scelte); corpo.addView(fotoTesto); corpo.addView(conto); corpo.addView(campo)
        // con la tastiera aperta la finestra si accorcia: scorre, e la lista tiene le sue righe
        val scorre = android.widget.ScrollView(this).apply { isFillViewport = true; addView(corpo) }
        val d = MaterialAlertDialogBuilder(this).setTitle("Cards from a list").setView(scorre)
            .setPositiveButton("Make the cards", null)
            .setNeutralButton("Open a file…", null)
            .setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                listaArrivata = { t -> campo.setText(t) }
                scegliLista.launch(arrayOf("text/*", "application/csv"))
            }
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val righe = Carte.righe(campo.text.toString(), campi)
                if (righe.isEmpty()) { avviso("Write at least one line"); return@setOnClickListener }
                if (importando) { avviso("Wait: the photos are still being read"); return@setOnClickListener }
                d.dismiss()
                val nome = (g.nome.ifBlank { "Design" } + " · cards")
                // foto senza un riquadro sulla carta: ne nasce uno vuoto sul fronte (solo nelle carte nuove)
                var modello = g
                if (foto.isNotEmpty() && Carte.cornice(g, p) == null && (retro == null || Carte.cornice(g, retro!!) == null)) {
                    val lato = Grafica.LARGO * 0.6f
                    modello = Grafiche.aggiungi(g, Elemento(0, Elemento.FOTO, 0f, 0f, lato, lato), p, Grafica.LARGO / 2f to g.alto * 0.3f)
                }
                val quali = Carte.abbina(righe, foto.map { it.first })
                val carte = Carte.unisci(modello, p, righe, nome, retro, quali.map { i -> i?.let { foto[it].second } })
                inizia(carte, nome)
                val n = carte.pagine / (if (retro != null) 2 else 1)
                avviso("$n cards" + (if (retro != null) ", front and back" else "") + " · a new project. Save › Paper › A4 puts them on sheets.")
            }
        }
        // la tastiera si apre al tocco sulla lista: aperta da sé schiaccerebbe la lista sotto Back e Photos
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    private fun apriPdf(u: Uri) {
        val conto = scritta("Opening…", 15f, R.color.testo).apply { setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0) }
        val basta = java.util.concurrent.atomic.AtomicBoolean(false)
        val d = MaterialAlertDialogBuilder(this).setTitle("Opening the PDF").setView(conto)
            .setNegativeButton("Cancel") { _, _ -> basta.set(true) }.setCancelable(false).create()
        d.show()
        lettore.execute {
            val esito = try {
                ApriPdf.apri(this, u, { fatte, tutte -> runOnUiThread { conto.text = "Page ${minOf(fatte + 1, tutte)} of $tutte" } }, { basta.get() || isDestroyed })
            } catch (_: Exception) { ApriPdf.Esito(null, "This PDF can't be opened.") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                d.dismiss()
                val g = esito.grafica
                if (g == null) { esito.errore?.let { avviso(it) }; return@runOnUiThread }
                val bid = Archivio.nuovoId()
                if (!Archivio.salvaGrafica(this, bid, g)) { avviso("The phone refused to save the project."); return@runOnUiThread }
                id = bid; nuova = true
                apriEditor(GraficaDisegno.misura(this, g))
            }
        }
    }

    private val permessoVecchio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) avviso("151 Editor needs storage access to save on this phone.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_grafica)
        vista = findViewById(R.id.vista)
        pannello = findViewById(R.id.pannello)
        cornicePannello = findViewById(R.id.cornice_pannello)
        attrezzi = findViewById(R.id.attrezzi)
        filaPagineVista = findViewById(R.id.fila_pagine)
        corniceVista = findViewById(R.id.cornice_vista)
        barraTastiera = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.carta))
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            visibility = View.GONE
            isClickable = true
        }
        corniceVista.addView(barraTastiera, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (60 * dp).toInt(), Gravity.BOTTOM))
        // la tastiera sale: la barra le sta sopra e la riga del cursore pure (la finestra non si stringe)
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(window.decorView,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(i: androidx.core.view.WindowInsetsCompat, l: MutableList<androidx.core.view.WindowInsetsAnimationCompat>) =
                    i.also { sopraTastiera() }
                override fun onEnd(a: androidx.core.view.WindowInsetsAnimationCompat) { sopraTastiera() }
            })
        corniceVista.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sopraTastiera() }
        vista.ascoltatore = this
        vista.immagini = GraficaDisegno.Immagini { immagine(it) }
        lavagna = findViewById(R.id.lavagna)
        preparaAttrezzi()
        lavagna.cornice = { vista.foglio() }
        lavagna.ascoltatore = LavagnaVista.Ascoltatore { t, fine ->
            val (n, nid) = Grafiche.disegno(storia.adesso, disegnoId, t, pagina)
            disegnoId = nid
            if (fine) fai(n) else { vivo = n; vista.mostra(n) }
        }
        storia = Storia(vivo, 100)

        findViewById<View>(R.id.indietro).setOnClickListener { esci() }
        findViewById<View>(R.id.inizio_indietro).setOnClickListener { finish() }
        findViewById<View>(R.id.annulla).setOnClickListener { if (storia.annulla()) dopoStoria() }
        findViewById<View>(R.id.rifai).setOnClickListener { if (storia.rifai()) dopoStoria() }
        findViewById<View>(R.id.salva).setOnClickListener { apriSalva() }
        findViewById<View>(R.id.pagina_prima).setOnClickListener { vaiA(if (affiancate) inizioLibro(pagina) - 2 else pagina - 1, aperto = true) }
        findViewById<View>(R.id.pagina_dopo).setOnClickListener { vaiA(if (affiancate) inizioLibro(pagina) + 2 else pagina + 1, aperto = true) }
        affiancate = getSharedPreferences("grafica", MODE_PRIVATE).getBoolean("affiancate", false)
        vista.affiancate = affiancate
        findViewById<View>(R.id.pagine_affiancate).setOnClickListener {
            affiancate = !affiancate
            getSharedPreferences("grafica", MODE_PRIVATE).edit().putBoolean("affiancate", affiancate).apply()
            vista.affiancate = affiancate
            filaPagine()
        }
        findViewById<View>(R.id.pagine_conta).setOnClickListener { if (pagina < 0) esciDallaMadre() else apriPagine() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    findViewById<View>(R.id.editor).visibility != View.VISIBLE -> finish()
                    vista.scrivendo -> smettiDiScrivere(scelto)
                    pezzo != null -> { pezzo = null; vista.pezzo = null; ispettore() }
                    scelto != null || modo != Modo.AGGIUNGI -> { scegli(null) }
                    else -> esci()
                }
            }
        })

        // il telefono può chiudere l'app mentre si sceglie una foto: si riparte da dov'eri
        savedInstanceState?.let {
            nuova = it.getBoolean(NUOVA)
            fotoPerSfondo = it.getBoolean(PER_SFONDO)
            perFoto = if (it.containsKey(PER_FOTO)) it.getLong(PER_FOTO) else null
        }
        val bozza = savedInstanceState?.getString(ID) ?: intent?.getStringExtra(ID)
        if (bozza != null) apriBozza(bozza) else mostraInizio()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        id?.let { out.putString(ID, it) }
        out.putBoolean(NUOVA, nuova)
        out.putBoolean(PER_SFONDO, fotoPerSfondo)
        perFoto?.let { out.putLong(PER_FOTO, it) }
    }

    override fun onPause() {
        super.onPause()
        bozzaScritta()
    }

    override fun onDestroy() {
        lettore.shutdownNow(); salvataggio.shutdown(); bozza.shutdown()
        super.onDestroy()
    }

    private fun avviso(t: String) = Ui.tinta(Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_LONG)).show()

    // ---------------------------------------------------------------- foto

    private fun immagine(uri: String): Bitmap? {
        foto.get(uri)?.let { return it }
        if (uri in illeggibili || !inArrivo.add(uri)) return null
        lettore.execute {
            val b = try { SalvaFoto.anteprima(this, Uri.parse(uri), LATO_FOTO) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
            runOnUiThread {
                inArrivo.remove(uri)
                if (isDestroyed) return@runOnUiThread
                if (b == null) illeggibili.add(uri) else foto.put(uri, b)
                vista.invalidate()
            }
        }
        return null
    }

    private fun chiediFoto(sostituisci: Long?, sfondo: Boolean) {
        perFoto = sostituisci; fotoPerSfondo = sfondo
        scegliFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** La foto scelta diventa una copia dentro l'app, poi entra nel design. */
    private fun arrivaFoto(u: Uri) {
        val sost = perFoto; val sfondo = fotoPerSfondo
        lettore.execute {
            val copia = Archivio.importaFoto(this, u)
            val b = copia?.let { try { SalvaFoto.anteprima(this, Uri.parse(it), LATO_FOTO) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (copia == null || b == null) { avviso("This photo can't be read."); return@runOnUiThread }
                foto.put(copia, b)
                val g = storia.adesso
                when {
                    sfondo -> fai(g.copy(fotoSfondo = copia, vela = if (g.fotoSfondo.isEmpty() && g.vela == 0) 20 else g.vela))
                    sost != null -> g.trova(sost)?.let { fai(Grafiche.sostituisci(g, it.copy(uri = copia))) }
                    else -> {
                        // larga 640 punti, la forma della foto, mai più alta del foglio
                        var w = 640f; var h = w * b.height / b.width.coerceAtLeast(1)
                        val maxH = g.alto * 0.7f
                        if (h > maxH) { w *= maxH / h; h = maxH }
                        val n = Grafiche.aggiungi(g, Elemento(0, Elemento.FOTO, 0f, 0f, w, h, uri = copia), pagina, vista.centroVisto())
                        scelto = n.elementi.last().id
                        fai(n)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- aprire

    private fun mostraInizio() {
        findViewById<View>(R.id.inizio).visibility = View.VISIBLE
        findViewById<View>(R.id.editor).visibility = View.GONE
        val corpo = findViewById<LinearLayout>(R.id.inizio_corpo)
        corpo.removeAllViews()
        corpo.addView(sezione("Blank"))
        val formati = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sagome = mapOf(Formato.A4 to (42 to 59), Formato.A5 to (36 to 51), Formato.POST to (52 to 52), Formato.STORIA to (36 to 64))
        Formato.entries.forEachIndexed { i, f ->
            formati.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                minimumHeight = (150 * dp).toInt()
                setPadding((6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt())
                background = tondo(R.color.carta, 12f)
                foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams(0, (150 * dp).toInt(), 1f).apply { if (i > 0) marginStart = (10 * dp).toInt() }
                val (sw, sh) = sagome.getValue(f)
                addView(View(context).apply {
                    background = GradientDrawable().apply { cornerRadius = 3 * dp; setStroke((2 * dp).toInt(), ContextCompat.getColor(context, R.color.testo)) }
                    layoutParams = LinearLayout.LayoutParams((sw * dp).toInt(), (sh * dp).toInt()).apply { bottomMargin = (10 * dp).toInt() }
                })
                addView(scritta(f.nome, 15f, R.color.testo, grassetto = true))
                addView(scritta(f.sotto, 12f, R.color.testo_piano))
                contentDescription = "New ${f.nome}"
                setOnClickListener { inizia(Grafiche.nuova(f.nome, f)) }
            })
        }
        corpo.addView(formati)
        // carte e misure libere: una fila di pillole sotto i quattro grandi
        corpo.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                (Formato.TUTTI - Formato.entries.toSet()).forEach { f ->
                    addView(Ui.pillola(context, "${f.corto} · ${f.sotto}", 44) { inizia(Grafiche.nuova(f.nome, f)) }.apply { contentDescription = "New ${f.nome}" })
                }
                addView(Ui.pillola(context, "Custom size…", 44) { scriviMisura(Formato.POST) { f -> inizia(Grafiche.nuova(f.nome, f)) } })
            })
        })
        if (Grafiche.MODELLI.isNotEmpty()) corpo.addView(sezione("Start from a template"))
        val modelli = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        Grafiche.MODELLI.forEachIndexed { i, m ->
            val alto = 150 * dp
            // largo al massimo quanto la sua colonna (i modelli stanno in fila, tutti nello schermo)
            val colonna = (resources.displayMetrics.widthPixels - 40 * dp - 10 * dp * (Grafiche.MODELLI.size - 1)) / Grafiche.MODELLI.size
            val k = min(alto / m.alto, min(104 * dp, colonna) / Grafica.LARGO)
            val mini = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((Grafica.LARGO * k).toInt(), (m.alto * k).toInt()).apply { bottomMargin = (8 * dp).toInt() }
                elevation = 6 * dp
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            lettore.execute {
                val b = try { GraficaDisegno.immagine(this, GraficaDisegno.misura(this, m), (Grafica.LARGO * k).toInt(), { null }, editor = true) }
                    catch (_: Exception) { null }
                runOnUiThread { if (!isDestroyed && b != null) mini.setImageBitmap(b) }
            }
            modelli.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (10 * dp).toInt() }
                addView(mini)
                addView(scritta(m.nome, 13f, R.color.testo))
                addView(scritta(m.formato.nome, 12f, R.color.testo_piano))
                contentDescription = "Template ${m.nome}"
                setOnClickListener { inizia(m) }
            })
        }
        if (Grafiche.MODELLI.isNotEmpty()) corpo.addView(modelli)
        corpo.addView(sezione("Open a file"))
        corpo.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            background = tondo(R.color.carta, 12f)
            foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_pagina)
                layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(scritta("Open a PDF", 15f, R.color.testo, grassetto = true))
                addView(scritta("Every page comes in, to flip through and print", 12f, R.color.testo_piano))
            })
            contentDescription = "Open a PDF"
            setOnClickListener { scegliPdf.launch(arrayOf("application/pdf")) }
        })
        corpo.addView(scritta("You can change the size later from Page.", 13f, R.color.testo_piano).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() }
        })
    }

    private fun sezione(t: String) = scritta(t.uppercase(), 13f, R.color.testo_piano, grassetto = true).apply {
        letterSpacing = 0.04f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (16 * dp).toInt(); bottomMargin = (10 * dp).toInt(); marginStart = (4 * dp).toInt()
        }
    }

    private fun scritta(t: String, sp: Float, colore: Int, grassetto: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp
        setTextColor(ContextCompat.getColor(context, colore))
        if (grassetto) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun tondo(colore: Int, raggio: Float) = GradientDrawable().apply {
        cornerRadius = raggio * dp; setColor(ContextCompat.getColor(this@GraficaActivity, colore))
    }

    /** Un design nuovo (vuoto o da un modello): nasce la bozza, si apre l'editor. */
    private fun inizia(g: Grafica, nome: String? = null) {
        val bid = Archivio.nuovoId()
        val n = GraficaDisegno.misura(this, g.copy(nome = nome ?: "Design ${Archivio.elencoGrafica(this).size + 1}"))
        if (!Archivio.salvaGrafica(this, bid, n)) { avviso("The phone refused to save the project."); return }
        id = bid; nuova = true
        apriEditor(n)
    }

    private fun apriBozza(bid: String) {
        val g = Archivio.caricaGrafica(this, bid)
        if (g == null) { avviso("This design can't be opened."); finish(); return }
        id = bid
        val m = GraficaDisegno.misura(this, g)
        apriEditor(m)
        // aprire non è cambiare: la bozza si riscrive (e sale in cima alla casa) solo se le misure sono cambiate
        if (m != g) memorizza()
    }

    private fun apriEditor(g: Grafica) {
        findViewById<View>(R.id.inizio).visibility = View.GONE
        findViewById<View>(R.id.editor).visibility = View.VISIBLE
        vista.fissi = g.fonteParole.isNotEmpty()
        storia = Storia(g, 100); vivo = g; scelto = null; stileScelto = null; modo = Modo.AGGIUNGI; disegnoId = null; pagina = 0
        mostra()
    }

    // ---------------------------------------------------------------- storia

    /** Un gesto finito: entra in Annulla/Rifai e nella bozza. I testi prendono la loro altezza vera. */
    private fun fai(g: Grafica) {
        storia.fai(GraficaDisegno.misura(this, rifili(g), storia.adesso)); vivo = storia.adesso
        pagina = Grafiche.dentro(vivo, pagina)
        if (vivo.trova(scelto)?.pagina != pagina) scelto = null
        mostra(); memorizza()
    }

    /**
     * La bozza si scrive fuori dal filo dello schermo: sul libro di 177 pagine (1,4 MB) scriverla a ogni lettera
     * fermava la tastiera (telefono di prova, 06/11). Una sola in coda: se ne arrivano altre mentre scrive, vale l'ultima.
     */
    private fun memorizza() {
        val i = id ?: return
        val g = storia.adesso
        if (inCoda.getAndSet(i to g) != null) return
        bozza.execute {
            val (qi, qg) = inCoda.getAndSet(null) ?: return@execute
            if (!Archivio.salvaGrafica(this, qi, qg)) runOnUiThread { if (!isDestroyed) avviso("The phone refused to save the draft.") }
        }
    }

    /** Aspetta che la bozza in coda sia scritta (si esce, l'app va in fondo). */
    private fun bozzaScritta() {
        try { bozza.submit { }.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    private fun dopoStoria() {
        if (vista.scrivendo) { vista.cursoreA(-1); pezzo = null; vista.pezzo = null }
        vivo = storia.adesso
        pagina = Grafiche.dentro(vivo, pagina)
        if (vivo.trova(scelto)?.pagina != pagina) scelto = null
        mostra(); memorizza()
    }

    private fun mostra() {
        findViewById<TextView>(R.id.nome_progetto).text = vivo.nome.ifBlank { "Design" }
        vista.scelto = scelto
        // i più scelti restano solo se ci sono ancora (Annulla, Elimina)
        scelti = if (scelto == null) emptySet() else scelti.filter { vivo.trova(it) != null }.toSet()
        if (scelto == null) aggiungendo = false
        vista.insieme = if (scelti.size > 1) scelti else emptySet(); vista.aggiungi = aggiungendo
        vista.pagina = pagina
        vista.tagliata = tagliata
        vista.conGuide = conGuide
        vista.cella = cella
        vista.mostra(vivo)
        filaPagine()
        // la gomma vede i tratti del disegno in corso, in frazioni del foglio
        lavagna.tratti = storia.adesso.trova(disegnoId)?.let { Grafiche.trattiNelFoglio(storia.adesso, it) } ?: emptyList()
        for ((b, ok) in listOf(R.id.annulla to storia.puoiAnnullare, R.id.rifai to storia.puoiRifare)) {
            findViewById<View>(b).apply { isEnabled = ok; alpha = if (ok) 1f else EditorActivity.SPENTO }
        }
        ispettore()
    }

    // ---------------------------------------------------------------- pagine

    /** ‹ «3 / 72» ›: con una pagina sola la fila resta (il foglio non salta quando arriva la seconda). */
    private fun filaPagine() {
        val n = vivo.pagine
        if (pagina < 0) {
            // si lavora sulla pagina madre: la pillola la nomina e un tocco riporta alle pagine
            findViewById<TextView>(R.id.pagine_conta).apply {
                text = "${vivo.nomiMadri.getOrElse(-pagina - 1) { "Master" }} · Done"
                contentDescription = "Master page, tap to go back to the pages"
            }
            for (b in listOf(R.id.pagina_prima, R.id.pagina_dopo)) findViewById<View>(b).apply { isEnabled = false; alpha = 0.35f }
            return
        }
        findViewById<TextView>(R.id.pagine_conta).apply {
            val altra = if (!affiancate) -1 else (if (pagina % 2 == 1) pagina + 1 else pagina - 1).takeIf { it in 0 until n } ?: -1
            val qui = if (altra < 0) "${pagina + 1}" else "${minOf(pagina, altra) + 1}–${maxOf(pagina, altra) + 1}"
            text = android.text.SpannableStringBuilder(qui).apply {
                val i = length; append(" / $n")
                setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.testo_piano)), i, length, 0)
            }
            contentDescription = "Page ${pagina + 1} of $n, open all pages"
        }
        findViewById<ImageButton>(R.id.pagine_affiancate).apply {
            setColorFilter(ContextCompat.getColor(context, if (affiancate) R.color.accento else R.color.testo))
            contentDescription = if (affiancate) "One page at a time" else "Two pages side by side"
        }
        val ultima = if (affiancate) inizioLibro(pagina) + (if (pagina == 0) 0 else 1) else pagina
        for ((b, ok) in listOf(R.id.pagina_prima to (pagina > 0), R.id.pagina_dopo to (ultima < n - 1))) {
            findViewById<View>(b).apply { isEnabled = ok; alpha = if (ok) 1f else 0.35f }
        }
    }

    // ---------------------------------------------------------------- pagine madri

    /** La pagina vera da cui si è entrati nella madre (ci si torna con «Done»). */
    private var paginaPrima = 0

    private fun entraNellaMadre(k: Int) {
        if (k !in 1..vivo.madri) return
        if (pagina >= 0) paginaPrima = pagina
        if (vista.scrivendo) { vista.cursoreA(-1); pezzo = null; vista.pezzo = null }
        pagina = -k; scelto = null; disegnoId = null; modo = Modo.AGGIUNGI
        vista.paginaIntera()
        mostra()
        // sopra la fila delle pagine: in fondo coprirebbe gli attrezzi proprio quando servono
        Ui.tinta(Snackbar.make(findViewById(R.id.radice), "Editing the master page: what you add shows on every page that uses it", Snackbar.LENGTH_LONG))
            .setAnchorView(R.id.fila_pagine).show()
    }

    private fun esciDallaMadre() {
        if (vista.scrivendo) { vista.cursoreA(-1); pezzo = null; vista.pezzo = null }
        pagina = paginaPrima.coerceIn(0, vivo.pagine - 1); scelto = null; disegnoId = null; modo = Modo.AGGIUNGI
        mostra()
    }

    /** Il numero di pagina sulla madre: «#» in basso al centro, alla misura più usata nel progetto. */
    private fun numeroDiPagina() {
        val g = storia.adesso
        val misura = g.elementi.filter { it.tipo == Elemento.TESTO }.groupingBy { it.misura }.eachCount().maxByOrNull { it.value }?.key ?: 24f
        val base = Elemento(0, Elemento.TESTO, 0f, 0f, 200f, 40f, testo = "#", misura = misura,
            colore = Grafiche.contrario(g.sfondo), allinea = Elemento.CENTRO, font = carattereNuovo(g))
        val e = base.copy(h = GraficaDisegno.altezzaTesto(this, base))
        val n = Grafiche.aggiungi(g, e, pagina, Grafica.LARGO / 2f to g.alto - 60f - e.h / 2f)
        scelto = n.elementi.last().id
        fai(n)
    }

    private fun scriviNome(titolo: String, ora: String, ok: (String) -> Unit) {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; setText(ora); setSelectAllOnFocus(true) }
        val d = MaterialAlertDialogBuilder(this).setTitle(titolo)
            .setView(FrameLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(campo) })
            .setPositiveButton("Done") { _, _ -> campo.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Cancel", null).create()
        // la tastiera si apre al tocco sulla lista: aperta da sé schiaccerebbe la lista sotto Back e Photos
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    /** Le righe «Margins» del pannello Page: margini e colonne di tutte le pagine, e se si vedono. */
    private fun righeGabbia(g: Grafica) {
        val m = g.gabbia
        riga().apply {
            addView(etichetta("Margins"))
            addView(pillola("None", m == null, uguale = true) { fai(storia.adesso.copy(gabbia = null)) })
            addView(pillola(if (m == null) "Set…" else descriviGabbia(m), m != null, uguale = true) {
                scriviGabbia(storia.adesso.gabbia ?: Gabbia(), storia.adesso.pagine > 1) { n -> conGuide = true; fai(storia.adesso.copy(gabbia = n)) }
            })
        }
        // la madre di questa pagina ha margini suoi: valgono quelli, qui si dice dove cambiarli
        val k = g.madre(pagina)
        if (k > 0 && g.gabbieMadri.getOrNull(k - 1) != null) aiuto("This page uses the margins of «${g.nomiMadri[k - 1]}»: change them in Edit «${g.nomiMadri[k - 1]}».")
        if (Grafiche.gabbiaDi(g, pagina) != null) {
            riga().apply {
                addView(etichetta("Guides"))
                addView(pillola("Show", conGuide, uguale = true) { conGuide = true; mostra() })
                addView(pillola("Hide", !conGuide, uguale = true) { conGuide = false; mostra() })
            }
            if (conGuide) aiuto("Objects snap to the pink margins and purple columns. The lines don't print.")
        }
    }

    /** Larghezza e altezza in mm per una pagina su misura. */
    private fun scriviMisura(ora: Formato, ok: (Formato) -> Unit) {
        fun campo(v: Float) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(mmCorto(if (ora.stampa) v else 0f).takeIf { ora.stampa } ?: ""); setSelectAllOnFocus(true); textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val w = campo(Math.round(ora.mmW * 10f) / 10f); val h = campo(Math.round(ora.mmH * 10f) / 10f)
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(TextView(context).apply { text = "Width"; textSize = 14f; setPadding(0, 0, (8 * dp).toInt(), 0) }); addView(w)
            addView(TextView(context).apply { text = "×"; textSize = 16f; setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0) })
            addView(TextView(context).apply { text = "Height"; textSize = 14f; setPadding(0, 0, (8 * dp).toInt(), 0) }); addView(h)
            addView(TextView(context).apply { text = "mm"; textSize = 14f; setPadding((8 * dp).toInt(), 0, 0, 0) })
        }
        val d = MaterialAlertDialogBuilder(this).setTitle("Page size")
            .setView(corpo)
            .setPositiveButton("Done") { _, _ ->
                val a = w.text.toString().replace(',', '.').toFloatOrNull(); val b = h.text.toString().replace(',', '.').toFloatOrNull()
                if (a == null || b == null || !a.isFinite() || !b.isFinite()) avviso("Write the width and the height in mm")
                else if (a < Formato.MM_MIN || b < Formato.MM_MIN || a > Formato.MM_MAX || b > Formato.MM_MAX) avviso("Between ${Formato.MM_MIN.roundToInt()} and ${Formato.MM_MAX.roundToInt()} mm")
                else ok(Formato.suMisura(a, b))
            }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); w.requestFocus()
    }

    private fun mmCorto(v: Float) = if (v == v.roundToInt().toFloat()) "${v.roundToInt()}" else String.format(java.util.Locale.ROOT, "%.1f", v)

    private fun descriviGabbia(m: Gabbia): String {
        val tutti = listOf(m.alto, m.basso, m.interno, m.esterno)
        val mm = if (tutti.distinct().size == 1) "${mmCorto(m.alto)} mm" else "Custom"
        return if (m.colonne > 1) "$mm · ${m.colonne} col" else mm
    }

    /** Margini e colonne in un foglio solo: tre righe di due numeri, in mm (le colonne sono un numero intero). */
    private fun scriviGabbia(ora: Gabbia, libro: Boolean, ok: (Gabbia) -> Unit) {
        val campi = mutableListOf<EditText>()
        fun campo(v: String, intero: Boolean) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or if (intero) 0 else InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(v); setSelectAllOnFocus(true); textAlignment = View.TEXT_ALIGNMENT_CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            campi += this
        }
        fun nome(t: String) = TextView(this).apply {
            text = t; textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
        }
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (4 * dp).toInt(), (20 * dp).toInt(), 0)
            val nomi = if (libro) "Inside" to "Outside" else "Left" to "Right"
            listOf(
                listOf("Top" to mmCorto(ora.alto), "Bottom" to mmCorto(ora.basso)),
                listOf(nomi.first to mmCorto(ora.interno), nomi.second to mmCorto(ora.esterno)),
                listOf("Columns" to "${ora.colonne}", "Gutter" to mmCorto(ora.spazio)),
            ).forEach { coppia ->
                addView(LinearLayout(this@GraficaActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    coppia.forEach { (t, v) -> addView(nome(t)); addView(campo(v, t == "Columns")) }
                })
            }
            if (libro) addView(TextView(this@GraficaActivity).apply {
                text = "Inside = the side at the fold. Sizes in mm."; textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
        }
        val d = MaterialAlertDialogBuilder(this).setTitle("Margins and columns")
            .setView(corpo)
            .setPositiveButton("Done") { _, _ ->
                val v = campi.map { it.text.toString().replace(',', '.').toFloatOrNull() }
                if (v.any { it == null || !it.isFinite() }) { avviso("Write a number in every box"); return@setPositiveButton }
                val n = Gabbia(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!.roundToInt(), v[5]!!).giusta()
                if (Grafiche.guide(storia.adesso.copy(gabbia = n), 0) == null) avviso("The margins leave no room on the page")
                else ok(n)
            }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show(); campi.first().requestFocus()
    }

    /** Le righe «Master» del pannello Page: quale madre usa questa pagina, nuova, modifica, su tutte; aggiungi pagine. */
    private fun righeMadri(g: Grafica) {
        val k = g.madre(pagina)
        riga().apply {
            addView(etichetta("Master"))
            addView(pillola("None", k == 0, stretta = true) { fai(Grafiche.usaMadre(storia.adesso, 0, listOf(pagina))) })
            g.nomiMadri.forEachIndexed { i, nome -> addView(pillola(nome, k == i + 1, stretta = true) { fai(Grafiche.usaMadre(storia.adesso, i + 1, listOf(pagina))) }) }
            addView(pillola("+ New", false, stretta = true) {
                scriviNome("Name of the master page", if (g.madri == 0) "Lined" else "Master ${g.madri + 1}") { nome ->
                    val (n, nk) = Grafiche.nuovaMadre(storia.adesso, nome)
                    fai(Grafiche.usaMadre(n, nk, listOf(pagina)))
                    entraNellaMadre(nk)
                }
            })
        }
        if (k > 0) riga().apply {
            addView(pillola("Edit «${g.nomiMadri[k - 1]}»", false, uguale = true) { entraNellaMadre(k) })
            addView(pillola("Use on all pages", g.madreDi.size == g.pagine && (0 until g.pagine).all { g.madre(it) == k }, uguale = true) {
                fai(Grafiche.usaMadre(storia.adesso, k, 0 until storia.adesso.pagine))
            })
        }
        riga().apply {
            addView(etichetta("Pages"))
            addView(pillola("Add pages…", false, uguale = true) {
                scriviNumero("How many pages after this one?", 10f) { v ->
                    val prima0 = storia.adesso.pagine
                    val (n, _) = Grafiche.aggiungiPagine(storia.adesso, pagina, v.roundToInt())
                    if (n.pagine == prima0) avviso("The most is ${Grafica.PAGINE_MAX} pages")
                    else { fai(n); avviso("Added ${n.pagine - prima0} pages · ${n.pagine} in all") }
                }
            })
        }
    }

    /** Libro aperto: la pagina di sinistra della coppia di [p] (la prima pagina sta da sola: 0). */
    private fun inizioLibro(p: Int) = if (p <= 0) 0 else if (p % 2 == 1) p else p - 1

    override fun apriPagina(p: Int) = vaiA(p)

    /** Il dito sfoglia: come i tasti ‹ › (stessi limiti, libro aperto di due in due). */
    override fun sfoglia(verso: Int) {
        if (pagina < 0) return
        findViewById<View>(if (verso > 0) R.id.pagina_dopo else R.id.pagina_prima).takeIf { it.isEnabled }?.performClick()
    }

    private fun vaiA(p: Int, aperto: Boolean = false) {
        // libro aperto e ‹ ›: la coppia prima o dopo (da 1 si torna a 0, la copertina da sola)
        val r = if (aperto && affiancate && p < 1) 0 else p
        val q = r.coerceIn(0, storia.adesso.pagine - 1)
        if (q == pagina) return
        pagina = q; scelto = null; disegnoId = null
        if (modo == Modo.DISEGNO || modo == Modo.FORME) modo = Modo.AGGIUNGI
        mostra()
    }

    private fun apriPagine() {
        scegli(null)
        PagineSchermo(this, lettore, { storia.adesso }, { g -> fai(g) }, { if (storia.annulla()) dopoStoria() },
            // le miniature si fanno fuori dal filo principale: la foto la leggono lì, piccola, subito
            // (prima: foto non ancora in memoria = miniatura bianca per sempre)
            { u -> foto.get(u) ?: try { SalvaFoto.anteprima(this, Uri.parse(u), 600) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null } },
            pagina) { p -> pagina = -1; vaiA(p) }.mostra()
    }

    /** Si esce sempre senza domande: tutto è già nella bozza. Un design appena nato e mai toccato non resta. */
    private fun esci() {
        val i = id
        if (i != null && nuova && !esportata && !storia.puoiAnnullare) { bozzaScritta(); Archivio.eliminaGrafica(this, i) }
        finish()
    }

    // ---------------------------------------------------------------- la vista

    override fun scelto(id: Long?) { scegli(id) }

    /** più oggetti scelti insieme (un gruppo, o «Select more»); vuoto = uno solo, [scelto] */
    private var scelti: Set<Long> = emptySet()
    /** «Select more»: i tocchi sulla pagina aggiungono e tolgono */
    private var aggiungendo = false
    /** «Move photo»: il dito sposta la foto dentro il riquadro */
    private var dentroFoto = false

    private fun scegli(id: Long?) {
        if (id != scelto) { cella = null; pezzo = null; vista.pezzo = null; smettiDiScrivere(scelto); dentroFoto = false; vista.dentroFoto = false }
        scelto = id; modo = Modo.AGGIUNGI; disegnoId = null; scorsa = 0
        // un oggetto di un gruppo porta con sé tutto il gruppo
        scelti = if (id == null) emptySet() else Grafiche.insiemeDi(storia.adesso, id).takeIf { it.size > 1 } ?: emptySet()
        if (id == null) aggiungendo = false
        vista.scelto = id; vista.cella = cella; vista.insieme = scelti; vista.aggiungi = aggiungendo
        ispettore()
    }

    override fun tocca(id: Long) {
        val g = storia.adesso
        val ora = scelti.ifEmpty { setOfNotNull(scelto) }
        val blocco = Grafiche.insiemeDi(g, id)
        val n = if (blocco.all { it in ora }) ora - blocco else ora + blocco
        if (n.isEmpty()) { scegli(null); return }
        scelti = n
        if (scelto !in n) scelto = n.first()
        vista.scelto = scelto; vista.insieme = scelti
        ispettore()
    }

    /** I più oggetti: allinea, spazi uguali, gruppo, copia, elimina. */
    private fun pannelloMolti(g: Grafica) {
        val ids = scelti.ifEmpty { setOfNotNull(scelto) }.filter { g.trova(it) != null }.toSet()
        val pezzi = g.elementi.filter { it.id in ids }.groupBy { if (it.gruppo != 0L) "g${it.gruppo}" else "e${it.id}" }.size
        val unGruppo = ids.size > 1 && g.elementi.filter { it.id in ids }.map { it.gruppo }.distinct().let { it.size == 1 && it[0] != 0L }
        testa(if (ids.size == 1) "1 object" else "${ids.size} objects", if (aggiungendo) " · tap others to add or remove" else if (unGruppo) " · group" else "")
        riga(scorre = true).apply {
            addView(etichetta("Align"))
            listOf(Grafiche.A_SINISTRA to "Left", Grafiche.A_CENTRO to "Center", Grafiche.A_DESTRA to "Right",
                Grafiche.IN_ALTO to "Top", Grafiche.A_META to "Middle", Grafiche.IN_BASSO to "Bottom").forEach { (k, t) ->
                addView(pillola(t, false, stretta = true) { fai(Grafiche.allinea(storia.adesso, ids, k)) })
            }
        }
        if (pezzi >= 3) riga().apply {
            addView(etichetta("Space"))
            addView(pillola("Even across", false, uguale = true) { fai(Grafiche.distribuisci(storia.adesso, ids, true)) })
            addView(pillola("Even down", false, uguale = true) { fai(Grafiche.distribuisci(storia.adesso, ids, false)) })
        } else aiuto(if (ids.size == 1) "Alone it aligns to the page. Tap others to align them together." else "With three or more you can also space them evenly.")
        azioni(listOfNotNull(
            if (aggiungendo) Azione(R.drawable.ic_fatto, "Stop adding") { aggiungendo = false; vista.aggiungi = false; ispettore() }
            else Azione(R.drawable.ic_tutto, "Select more") { aggiungendo = true; vista.aggiungi = true; ispettore() },
            if (unGruppo) Azione(R.drawable.ic_griglia, "Ungroup") { fai(Grafiche.separa(storia.adesso, ids)); scelti = ids; vista.insieme = ids; ispettore() }
            else if (ids.size > 1) Azione(R.drawable.ic_griglia, "Group") { fai(Grafiche.raggruppa(storia.adesso, ids)) } else null,
            Azione(R.drawable.ic_duplica, "Duplicate") {
                val (n, nuovi) = Grafiche.duplicaTutti(storia.adesso, ids)
                scelti = nuovi; scelto = nuovi.firstOrNull(); vista.scelto = scelto; vista.insieme = nuovi; fai(n)
            },
            Azione(R.drawable.ic_cestino, "Delete") {
                val n = Grafiche.eliminaTutti(storia.adesso, ids)
                scegli(null); fai(n)
                Ui.tinta(Snackbar.make(findViewById(R.id.radice), "Deleted ${ids.size}", Snackbar.LENGTH_LONG)).setAction("Undo") { if (storia.annulla()) dopoStoria() }.show()
            },
        ))
    }

    override fun pezzo(id: Long, p: IntRange?) {
        pezzo = p
        ispettore()
    }

    override fun scrive(id: Long, cursore: Int) {
        if (cursore < 0) { smettiDiScrivere(id); return }
        pezzo = vista.pezzo
        if (barraTastiera.visibility != View.VISIBLE) ispettore() else scritto()?.let { barraSopraTastiera(storia.adesso, it) }
    }

    override fun testo(id: Long, cella: Pair<Int, Int>?, testo: String, segni: List<Segno>) {
        val e = storia.adesso.trova(id) ?: return
        val sc = if (cella != null) Scritto(e, cella.first, cella.second) else Scritto(e)
        // una cella vuota con uno stile: le prime lettere lo prendono
        val nuova = if (cella != null) Grafiche.scrittaInCella(storia.adesso, e, cella.first, cella.second, testo, segni)?.let { Tabelle.conCella(e, cella.first, cella.second, it) } else null
        fai(Grafiche.sostituisci(storia.adesso, nuova ?: sc.con(testo, segni)))
    }

    override fun gesto(g: Grafica, fine: Boolean) {
        if (fine) fai(g) else vivo = g
    }


    override fun cella(id: Long, r: Int, c: Int) {
        smettiDiScrivere(id)
        pezzo = null; vista.pezzo = null
        cella = if (cella == r to c) null else r to c
        vista.cella = cella
        ispettore()
    }

    override fun riscriviCella(id: Long, r: Int, c: Int) {
        if (scelto != id) scegli(id)
        smettiDiScrivere(id)
        pezzo = null; vista.pezzo = null
        cella = r to c; vista.cella = cella
        ispettore()
    }

    // ---------------------------------------------------------------- l'ispettore

    private fun cambia(f: (Elemento) -> Elemento) {
        val e = storia.adesso.trova(scelto) ?: return
        fai(Grafiche.sostituisci(storia.adesso, f(e)))
    }

    /** quanti tocchi sono in corso di consegna alle viste (dispatchTouchEvent) */
    private var inTocco = 0
    private var ispettoreDopo = false

    /**
     * Il pannello non si rifà mai dentro un tocco: rifatto mentre il tocco scendeva fra le sue viste, il tocco
     * seguente chiudeva l'app (NullPointerException getZ in ViewGroup.buildOrderedChildList, telefono di prova 05/11, «+» della
     * misura due volte). Chiesto durante un tocco, si rifà una volta sola subito dopo.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        inTocco++
        try { return super.dispatchTouchEvent(ev) } finally {
            inTocco--
            if (inTocco == 0 && ispettoreDopo) { ispettoreDopo = false; pannello.post { if (!isFinishing) ispettore() } }
        }
    }

    private fun ispettore() {
        if (inTocco > 0) { ispettoreDopo = true; return }
        segnaAttrezzi()
        pannello.removeAllViews()
        (penna.vista.parent as? ViewGroup)?.removeView(penna.vista)
        lavagna.attiva = modo == Modo.DISEGNO
        val g = vivo
        val e = g.trova(scelto)
        // niente scelto: niente pannello e niente istruzioni; qualcosa scelto: niente
        // attrezzi né fila delle pagine (più posto al pannello); si scrive: solo la pagina e la barra
        // sopra la tastiera. La pagina non si muove mai: lo spazio tolto lo tiene riservaSotto.
        val sc = scritto(g)
        val scrive = vista.scrivendo && sc != null
        val vuoto = e == null && modo == Modo.AGGIUNGI
        val senzaPannello = vuoto || scrive
        val senzaAttrezzi = e != null || scrive
        cornicePannello.visibility = if (senzaPannello) View.GONE else View.VISIBLE
        attrezzi.visibility = if (senzaAttrezzi) View.GONE else View.VISIBLE
        filaPagineVista.visibility = if (senzaAttrezzi) View.GONE else View.VISIBLE
        // niente posto tenuto per il pannello: la pagina prende tutto lo spazio e il pannello, quando sale, ne
        // copre il fondo senza rimpicciolirla (GraficaVista.misure); la riga scelta sale da sola sopra di lui
        vista.riservaSotto = 0f
        barraTastiera.visibility = if (scrive) View.VISIBLE else View.GONE
        if (scrive && sc != null) { barraSopraTastiera(g, sc); return }
        when {
            (aggiungendo || scelti.size > 1) && e != null && modo != Modo.DISEGNO -> pannelloMolti(g)
            modo == Modo.DISEGNO -> {
                pannello.addView(penna.vista)
                aiuto("One finger draws · Undo is at the top")
            }
            vuoto -> {}
            e == null && modo == Modo.FORME -> {
                testa("Add a shape", "")
                riquadriForme()
                aiuto("It lands in the middle: drag it where you want")
            }
            e == null && pagina < 0 -> {
                val k = -pagina
                val usate = (0 until g.pagine).count { g.madre(it) == k }
                testa("Master «${g.nomiMadri.getOrElse(k - 1) { "" }}»", " · on $usate page" + if (usate == 1) "" else "s")
                aiuto("What you put here shows on every page that uses it. # in a text = the page number.", 52)
                val propria = g.gabbieMadri.getOrNull(k - 1)
                riga().apply {
                    addView(etichetta("Margins"))
                    addView(pillola("Like the pages", propria == null, uguale = true) { fai(Grafiche.gabbiaMadre(storia.adesso, k, null)) })
                    addView(pillola(if (propria == null) "Own…" else descriviGabbia(propria), propria != null, uguale = true) {
                        scriviGabbia(propria ?: storia.adesso.gabbia ?: Gabbia(), storia.adesso.pagine > 1) { n -> conGuide = true; fai(Grafiche.gabbiaMadre(storia.adesso, k, n)) }
                    })
                }
                azioni(spazio = false, lista = listOf(
                    Azione(R.drawable.ic_testo, "Page number") { numeroDiPagina() },
                    Azione(R.drawable.ic_matita, "Rename") { scriviNome("Name of the master page", g.nomiMadri[k - 1]) { fai(Grafiche.rinominaMadre(storia.adesso, k, it)) } },
                    Azione(R.drawable.ic_cestino, "Delete master") {
                        val prima = storia.adesso
                        esciDallaMadre(); fai(Grafiche.eliminaMadre(prima, k))
                        Ui.tinta(Snackbar.make(findViewById(R.id.radice), "Master page deleted", Snackbar.LENGTH_LONG)).setAction("Undo") { if (storia.annulla()) dopoStoria() }.show()
                    },
                    Azione(R.drawable.ic_fatto, "Done") { esciDallaMadre() },
                ))
            }
            e == null -> {
                testa("Page ${pagina + 1}", " · ${g.formato.nome}" + if (g.formato.stampa && g.formato.nome != g.formato.misureMm) " · ${g.formato.misureMm}" else "")
                // il pannello è alto uguale per tutti: qui le righe sono tante, scorrono (le azioni restano in fondo)
                scorrendo {
                    righeMadri(g)
                    // margini prima del formato: il formato si sceglie nascendo, i margini si cercano qui
                    righeGabbia(g)
                    riga(scorre = true).apply {
                        addView(etichetta("Size"))
                        val mio = g.formato !in Formato.TUTTI
                        Formato.TUTTI.forEach { f -> addView(pillola(f.corto, g.formato == f, stretta = true) { fai(Grafiche.cambiaFormato(storia.adesso, f)) }) }
                        addView(pillola(if (mio) g.formato.corto else "Custom…", mio, stretta = true) { scriviMisura(storia.adesso.formato) { f -> fai(Grafiche.cambiaFormato(storia.adesso, f)) } })
                    }
                    righeColori(g.sfondo, nessuno = false) { c -> fai(storia.adesso.copy(sfondo = c)) }
                    if (g.formato.stampa) {
                        riga().apply {
                            addView(etichetta("Bleed"))
                            listOf(0f to "None", 3f to "3 mm", 5f to "5 mm").forEach { (mm, t) ->
                                addView(pillola(t, g.abbondanzaMm == mm, uguale = true) { fai(storia.adesso.copy(sceltaAbbondanza = mm)) })
                            }
                        }
                        if (g.abbondanzaMm > 0f) riga().apply {
                            addView(etichetta("Show"))
                            addView(pillola("With bleed", !tagliata, uguale = true) { tagliata = false; mostra() })
                            addView(pillola("Trimmed", tagliata, uguale = true) { tagliata = true; mostra() })
                        }
                        if (g.abbondanzaMm > 0f && !tagliata) aiuto("The shaded band outside the thin line is cut off when printed.")
                    }
                    if (g.fotoSfondo.isNotEmpty()) cursore("Dim", g.vela.toFloat(), 0f, Grafica.VELA_MAX.toFloat(), "%",
                        { v -> vivo.copy(vela = v.roundToInt()) })
                    else aiuto("A photo as background? Use the button below")
                }
                azioni(spazio = false, lista = buildList {
                    add(Azione(R.drawable.ic_griglia, "Cards from a list") { carteDaLista() })
                    // la pagina-foto di un PDF aperto è ferma (si sfoglia col dito); qui si libera per spostarla o cambiarla
                    val fermi = g.di(pagina).filter { it.fermo || Grafiche.paginaFoto(g, it) }
                    if (pagina >= 0 && fermi.isNotEmpty()) {
                        val ferma = fermi.all { it.fermo }
                        add(Azione(R.drawable.ic_foto, if (ferma) "Unlock page photo" else "Lock page photo") {
                            fai(Grafiche.fermaPagina(storia.adesso, pagina, !ferma))
                            avviso(if (ferma) "The page photo moves again" else "The page photo stays put: swipe to turn pages")
                        })
                    }
                    add(Azione(R.drawable.ic_foto, if (g.fotoSfondo.isEmpty()) "Background photo" else "Change photo") { chiediFoto(null, true) })
                    if (g.fotoSfondo.isNotEmpty()) add(Azione(R.drawable.ic_cestino, "Remove photo", rosso = true) { fai(storia.adesso.copy(fotoSfondo = "")) })
                })
            }
            e.tipo == Elemento.TABELLA -> if (pezzo != null && sc != null) pannelloTesto(g, sc) else ispettoreTabella(g, e)
            e.tipo == Elemento.TESTO -> pannelloTesto(g, Scritto(e))
            e.tipo == Elemento.FOTO -> {
                testa("Photo", if (e.uri.isEmpty()) " · empty" else if (dentroFoto) " · drag to move it inside" else "")
                scorrendo {
                    if (e.uri.isNotEmpty()) {
                        // la foto nel riquadro: riempie o intera, spostata col dito, ingrandita
                        riga().apply {
                            addView(pillola("Fill", !e.adatta, uguale = true) { cambia { it.copy(adatta = false) } })
                            addView(pillola("Fit", e.adatta, uguale = true) { cambia { it.copy(adatta = true) } })
                            addView(pillola("Move photo", dentroFoto, uguale = true) { dentroFoto = !dentroFoto; vista.dentroFoto = dentroFoto; ispettore() })
                        }
                        cursore("Zoom", e.zoomFoto * 100f, 100f, Grafiche.ZOOM_FOTO_MAX * 100f, "%", { v -> conElemento { it.copy(zoomFoto = v / 100f) } })
                    }
                    riga().apply {
                        listOf(Elemento.QUADRATA to "Square", Elemento.ARROTONDATA to "Rounded", Elemento.CERCHIO to "Circle").forEach { (m, n) ->
                            addView(pillola(n, e.maschera == m, uguale = true) { cambia { it.copy(maschera = m) } })
                        }
                    }
                    righeColori(e.bordo, nessuno = true, etichetta = "Border") { c -> cambia { it.copy(bordo = c) } }
                    cursoreOpacita(e)
                }
                azioniOggetto(e, Azione(R.drawable.ic_foto, if (e.uri.isEmpty()) "Add photo" else "Replace") { chiediFoto(e.id, false) }, spazio = false)
            }
            e.tipo == Elemento.DISEGNO -> {
                testa("Drawing", "")
                cursoreOpacita(e)
                // si ridisegna solo un disegno dritto: girato, i tratti nuovi non saprebbero dove andare
                azioniOggetto(e, if (e.rot == 0f) Azione(R.drawable.ic_pennello, "Draw more") { disegna(e.id) } else null)
            }
            e.linea -> {
                testa("Shape", " · Line")
                righeColori(e.riempi, nessuno = false) { c -> cambia { it.copy(riempi = c) } }
                cursore("Width", e.spessore, 2f, 40f, "", { v -> conElemento { it.copy(spessore = v) } })
                riga().apply {
                    listOf(Elemento.PIENO to "Solid", Elemento.TRATTEGGIO to "Dashed", Elemento.FRECCIA to "Arrow").forEach { (t, n) ->
                        addView(pillola(n, e.tratto == t, uguale = true) { cambia { it.copy(tratto = t) } })
                    }
                }
                azioniOggetto(e, null)
            }
            else -> {
                testa("Shape", " · " + (Elemento.FORME.firstOrNull { it.first == e.forma }?.second ?: ""))
                righeColori(e.riempi, nessuno = true, etichetta = "Fill") { c -> cambia { it.copy(riempi = c) } }
                righeColori(e.contorno, nessuno = true, etichetta = "Outline") { c -> cambia { it.copy(contorno = c) } }
                cursoreOpacita(e)
                azioniOggetto(e, null)
            }
        }
    }

    /** La penna: [id] = continua quel disegno, null = il primo tratto ne fa uno nuovo. */
    private fun disegna(id: Long?) {
        disegnoId = id; scelto = null; vista.scelto = null
        modo = Modo.DISEGNO
        mostra()
    }

    /** Fine della penna: il disegno fatto resta scelto, pronto da spostare. */
    private fun finisciDisegno() {
        val id = disegnoId?.takeIf { storia.adesso.trova(it) != null }
        disegnoId = null
        scegli(id)
    }

    private fun conElemento(f: (Elemento) -> Elemento): Grafica? {
        val e = vivo.trova(scelto) ?: return null
        return Grafiche.sostituisci(vivo, f(e))
    }

    private fun cursoreOpacita(e: Elemento) =
        cursore("Opacity", e.opacita.toFloat(), 0f, 100f, "%", { v -> conElemento { it.copy(opacita = v.roundToInt()) } })

    // ---------------------------------------------------------------- pezzi dell'ispettore

    private fun testa(nome: String, piano: String, chiudi: Boolean = true) {
        pannello.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt())
            setPadding((14 * dp).toInt(), 0, 0, 0)
            addView(TextView(context).apply {
                text = android.text.SpannableStringBuilder(nome).apply {
                    val a = length; append(piano)
                    setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.testo_piano)), a, length, 0)
                }
                textSize = 15f; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.testo))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (chiudi) addView(Ui.fatto(context) { scegli(null) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt())
            })
        })
    }

    private fun riga(scorre: Boolean = false): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        if (scorre) {
            r.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt())
            pannello.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                // la fila continua a destra: si vede che sfuma, non che è tagliata
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt())
                addView(r)
            })
        } else {
            r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt())
            pannello.addView(r)
        }
        return r
    }

    private fun pillola(t: String, si: Boolean, uguale: Boolean = false, stretta: Boolean = false, alTocco: () -> Unit): TextView =
        Ui.pillola(this, t, 44, alTocco).apply {
            if (uguale) layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() }
            if (stretta) setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            setSingleLine()
            Ui.segna(this, si)
        }

    private fun righeColori(attuale: Int, nessuno: Boolean, etichetta: String? = null, alTocco: (Int) -> Unit) {
        val r = riga(scorre = true)
        if (etichetta != null) r.addView(TextView(this).apply {
            text = etichetta; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
            setPadding((6 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        })
        if (nessuno) r.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginEnd = (2 * dp).toInt() }
            contentDescription = "None"
            setOnClickListener { alTocco(0) }
            if (attuale == 0) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setStroke((2.5f * dp).toInt(), ContextCompat.getColor(context, R.color.accento))
            }
            addView(TextView(context).apply {
                text = "∅"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(context, R.color.fondo)); setStroke((1 * dp).toInt(), 0x55FFFFFF)
                }
                layoutParams = FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt(), Gravity.CENTER)
            })
        })
        // i colori comuni a tutti gli editor: i fissati per primi, poi questi, in fondo «+»
        Ui.righeColori(this, r, Grafiche.COLORI, tieni = r.childCount) { alTocco(it) }
        Ui.segnaColori(r, attuale)
    }

    private fun rigaFont(e: Elemento) {
        val r = riga(scorre = true)
        // la riga comune a tutti gli editor: i font fissati (★) per primi; tenuto = fissa
        Ui.righeFont(this, r, 40) { fid -> cambia { it.copy(font = fid) } }
        Ui.segnaFont(r, e.font)
        val scelta: View? = (0 until r.childCount).map { r.getChildAt(it) }.firstOrNull { it.getTag(R.id.chiave) == e.font }
        // la riga parte dal font scelto, non dall'inizio
        scelta?.let { s -> (r.parent as? HorizontalScrollView)?.post { (r.parent as HorizontalScrollView).scrollTo((s.left - 60 * dp).toInt().coerceAtLeast(0), 0) } }
    }

    private val toccati = HashSet<Slider>()

    /** Un cursore: mentre scorre si vede, quando si lascia entra nella storia. */
    private fun cursore(nome: String, valore: Float, da: Float, a: Float, unita: String, mentre: (Float) -> Grafica?) {
        val r = riga()
        r.addView(TextView(this).apply {
            text = nome; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding((6 * dp).toInt(), 0, 0, 0)
        })
        val valoreTesto = TextView(this).apply {
            textSize = 14f; gravity = Gravity.END
            setTextColor(ContextCompat.getColor(context, R.color.testo))
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            text = "${valore.roundToInt()}$unita"
        }
        val s = Slider(this).apply {
            valueFrom = da; valueTo = a; stepSize = 1f
            value = valore.coerceIn(da, a).roundToInt().toFloat()
            isTickVisible = false
            labelBehavior = com.google.android.material.slider.LabelFormatter.LABEL_GONE
            contentDescription = nome
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addOnChangeListener { _, v, daDito ->
                valoreTesto.text = "${v.roundToInt()}$unita"
                if (daDito) mentre(v)?.let { vivo = it; vista.mostra(it) }
            }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(sl: Slider) { toccati.add(sl) }
                override fun onStopTrackingTouch(sl: Slider) { toccati.remove(sl); mentre(sl.value)?.let { fai(it) } }
            })
        }
        r.addView(s); r.addView(valoreTesto)
    }

    /** Una riga di «come si fa» quando niente è scelto. */
    /** La striscia degli attrezzi, sempre visibile: toccarne uno lascia andare la cosa scelta e fa l'attrezzo. */
    private fun preparaAttrezzi() {
        findViewById<View>(R.id.att_testo).setOnClickListener { scegli(null); nuovoTesto() }
        findViewById<View>(R.id.att_foto).setOnClickListener { scegli(null); chiediFoto(null, false) }
        findViewById<View>(R.id.att_forme).setOnClickListener { scegli(null); modo = Modo.FORME; ispettore() }
        findViewById<View>(R.id.att_disegno).setOnClickListener { disegna(null) }
        findViewById<View>(R.id.att_pagina).setOnClickListener { scegli(null); modo = Modo.PAGINA; ispettore() }
    }

    /** L'attrezzo aperto si vede acceso (Shape, Draw, Page). */
    private fun segnaAttrezzi() {
        val acceso = when (modo) { Modo.FORME -> R.id.att_forme; Modo.DISEGNO -> R.id.att_disegno; Modo.PAGINA -> R.id.att_pagina; else -> 0 }
        for (b in listOf(R.id.att_testo, R.id.att_foto, R.id.att_forme, R.id.att_disegno, R.id.att_pagina)) {
            val c = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, if (b == acceso) R.color.accento else R.color.testo))
            findViewById<com.google.android.material.button.MaterialButton>(b).apply { setTextColor(c); iconTint = c }
        }
    }

    private fun aiuto(t: String, alto: Int = 34) {
        pannello.addView(TextView(this).apply {
            text = t; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (alto * dp).toInt())
        })
    }

    private fun riquadro(icona: View, nome: String, piccolo: Boolean, alTocco: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = tondo(R.color.riga, 10f)
        foreground = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = (3 * dp).toInt(); marginEnd = (3 * dp).toInt() }
        contentDescription = nome
        setOnClickListener { alTocco() }
        addView(icona)
        addView(TextView(context).apply {
            text = nome; textSize = if (piccolo) 12f else 14f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.testo))
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
    }

    private fun contenitoreRiquadri() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((5 * dp).toInt(), (4 * dp).toInt(), (5 * dp).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }.also { pannello.addView(it) }

    private fun riquadriForme() {
        val r = contenitoreRiquadri()
        val colore = ContextCompat.getColor(this, R.color.testo)
        for ((f, nome) in Elemento.FORME) {
            val mini = object : View(this) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val lato = min(width, height).toFloat()
                    val e = Elemento(0, Elemento.FORMA, 0f, 0f, if (f == Elemento.LINEA) lato else lato * 0.8f,
                        if (f == Elemento.LINEA) lato else lato * 0.8f, forma = f, riempi = colore, spessore = lato * 0.09f, raggio = lato * 0.06f)
                    c.translate((width - e.w) / 2f, (height - e.h) / 2f)
                    GraficaDisegno.elemento(context, c, e, { null }, true)
                }
            }.apply { layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt()) }
            r.addView(riquadro(mini, nome, true) { aggiungiForma(f) })
        }
        r.addView(riquadro(ImageView(this).apply {
            setImageResource(R.drawable.ic_tabella); setColorFilter(colore)
            layoutParams = LinearLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt())
        }, "Table", true) { aggiungiTabella() })
    }

    private fun aggiungiForma(f: String) {
        val g = storia.adesso
        val e = if (f == Elemento.LINEA)
            Elemento(0, Elemento.FORMA, 0f, 0f, 500f, Elemento.ALTO_LINEA, forma = f, riempi = Grafiche.contrario(g.sfondo))
        else Elemento(0, Elemento.FORMA, 0f, 0f, 360f, 360f, forma = f, riempi = ContextCompat.getColor(this, R.color.accento))
        val n = Grafiche.aggiungi(g, e, pagina, vista.centroVisto())
        scelto = n.elementi.last().id; modo = Modo.AGGIUNGI
        fai(n)
    }

    private data class Azione(val icona: Int, val nome: String, val rosso: Boolean = false, val spenta: Boolean = false, val via: () -> Unit)

    /** Le righe fatte dentro [f] stanno in una parte che scorre, alta quanto resta. */
    private var scorsa = 0

    private fun scorrendo(f: () -> Unit) {
        val fuori = pannello
        val dentro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fuori.addView(android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength((20 * dp).toInt())
            addView(dentro)
            // un tocco rifà il pannello: resta dov'eri, non torna in cima
            val dove = scorsa
            post { scrollTo(0, dove) }
            setOnScrollChangeListener { _, _, y, _, _ -> scorsa = y }
        })
        pannello = dentro
        try { f() } finally { pannello = fuori }
    }

    private fun etichetta(t: String) = TextView(this).apply {
        text = t; textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.testo_piano))
        layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        setPadding((6 * dp).toInt(), 0, 0, 0)
    }

    private fun azioni(lista: List<Azione>, spazio: Boolean = true) {
        pannello.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.riga))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)).apply { topMargin = 0 }
        })
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            // in fondo, sempre allo stesso posto
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt())
        }
        for (a in lista) {
            val colore = ContextCompat.getColor(this, if (a.rosso) R.color.accento else R.color.testo)
            r.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                contentDescription = a.nome
                isEnabled = !a.spenta
                alpha = if (a.spenta) EditorActivity.SPENTO else 1f
                setOnClickListener { a.via() }
                addView(ImageView(context).apply {
                    setImageResource(a.icona); setColorFilter(colore)
                    layoutParams = LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt())
                })
                addView(TextView(context).apply {
                    text = a.nome; textSize = 12f; setSingleLine(); setTextColor(colore); gravity = Gravity.CENTER
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            })
        }
        // lo spazio sopra le azioni le tiene in fondo
        if (spazio) pannello.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }, pannello.childCount - 1)
        pannello.addView(r)
    }

    private fun azioniOggetto(e: Elemento, prima: Azione?, spazio: Boolean = true, seconda: Azione? = null) {
        val g = storia.adesso
        azioni(spazio = spazio, lista = listOfNotNull(
            prima, seconda,
            Azione(R.drawable.ic_duplica, "Duplicate") { Grafiche.duplica(storia.adesso, e.id)?.let { (n, nid) -> scelto = nid; fai(n) } },
            Azione(R.drawable.ic_tutto, "Select") { scelti = setOf(e.id); aggiungendo = true; vista.insieme = scelti; vista.aggiungi = true; ispettore() },
            Azione(R.drawable.ic_su, "Forward", spenta = Grafiche.alBordo(g, e.id, +1)) { fai(Grafiche.livello(storia.adesso, e.id, +1)) },
            Azione(R.drawable.ic_giu, "Backward", spenta = Grafiche.alBordo(g, e.id, -1)) { fai(Grafiche.livello(storia.adesso, e.id, -1)) },
            // grigio come gli altri: il rosa vuol dire «scelto»; Undo nella striscia lo riporta
            Azione(R.drawable.ic_cestino, "Delete") {
                scelto = null; fai(Grafiche.elimina(storia.adesso, e.id))
                Ui.tinta(Snackbar.make(findViewById(R.id.radice), "Deleted", Snackbar.LENGTH_LONG)).setAction("Undo") { if (storia.annulla()) dopoStoria() }.show()
            },
        ))
    }

    // ---------------------------------------------------------------- misure e tabelle

    /** In tipografia le lettere si contano in punti (1/72 di pollice), un decimo alla volta. */
    private fun pt(g: Grafica, misura: Float) = misura * g.formato.ptPerPunto
    private fun daPt(g: Grafica, p: Float) = (p / g.formato.ptPerPunto).coerceIn(8f, 1200f)

    /** «Size − 6.0 pt +»: i tasti fanno un decimo, il numero si tocca per scriverlo. */
    private fun rigaMisura(g: Grafica, misura: Float, cambia: (Float) -> Unit) {
        val r = riga()
        r.addView(etichetta("Size"))
        val v = pt(g, misura)
        fun passo(d: Float) = cambia(daPt(g, ((v * 10f).roundToInt() + d * 10f).roundToInt() / 10f))
        r.addView(tondino(R.drawable.ic_meno, "Smaller") { passo(-0.1f) })
        r.addView(TextView(this).apply {
            text = String.format(java.util.Locale.ROOT, "%.1f pt", v); textSize = 16f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.testo)); fontFeatureSettings = "tnum"
            background = tondo(R.color.riga, 10f)
            layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginStart = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }
            contentDescription = "Size in points, tap to type"
            setOnClickListener { scriviNumero("Size in points", v) { n -> cambia(daPt(g, n)) } }
        })
        r.addView(tondino(R.drawable.ic_piu, "Bigger") { passo(+0.1f) })
    }

    private fun tondino(icona: Int, nome: String, via: () -> Unit) = ImageButton(this).apply {
        setImageResource(icona); contentDescription = nome
        background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
        layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (44 * dp).toInt())
        setOnClickListener { via() }
    }

    private fun scriviNumero(titolo: String, ora: Float, ok: (Float) -> Unit) {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.ROOT, "%.1f", ora)); setSelectAllOnFocus(true)
        }
        val d = MaterialAlertDialogBuilder(this).setTitle(titolo)
            .setView(FrameLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(campo) })
            .setPositiveButton("Done") { _, _ -> campo.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }?.let(ok) }
            .setNegativeButton("Cancel", null).create()
        // la tastiera si apre al tocco sulla lista: aperta da sé schiaccerebbe la lista sotto Back e Photos
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    /** − n + in una riga (righe, colonne). */
    private fun contatore(r: LinearLayout, nome: String, n: Int, meno: () -> Unit, piu: () -> Unit) {
        r.addView(etichetta(nome))
        r.addView(tondino(R.drawable.ic_meno, "Fewer $nome") { meno() }.apply { isEnabled = n > 1; alpha = if (n > 1) 1f else EditorActivity.SPENTO })
        r.addView(TextView(this).apply {
            text = "$n"; textSize = 16f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum"
            setTextColor(ContextCompat.getColor(context, R.color.testo))
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        r.addView(tondino(R.drawable.ic_piu, "More $nome") { piu() })
    }

    private fun aggiungiTabella() {
        val g = storia.adesso
        val k = g.formato.ptPerPunto
        // 9 pt di testo e linee da 0,5 pt, come il quaderno; nero da stampa
        val t = Tabelle.nuova(0, 3, 3, 900f, 3 * (9f / k) * 2.4f, 9f / k, Grafiche.contrario(g.sfondo), Grafiche.contrario(g.sfondo), (0.5f / k).coerceAtLeast(1f))
        val n = Grafiche.aggiungi(g, t, pagina, vista.centroVisto())
        scelto = n.elementi.last().id; cella = null; modo = Modo.AGGIUNGI
        fai(n)
    }

    private fun conTabella(f: (Elemento) -> Elemento) {
        val e = storia.adesso.trova(scelto)?.takeIf { it.tipo == Elemento.TABELLA } ?: return
        fai(Grafiche.sostituisci(storia.adesso, f(e)))
    }

    private fun ispettoreTabella(g: Grafica, e: Elemento) {
        val sc = cella?.takeIf { it.first < Tabelle.nr(e) && it.second < Tabelle.nc(e) && !Tabelle.coperta(e, it.first, it.second) }
        testa("Table", " · ${Tabelle.nr(e)} × ${Tabelle.nc(e)}" + (sc?.let { " · cell ${it.first + 1}, ${it.second + 1}" } ?: " · tap a cell"))
        scorrendo {
            riga().apply {
                contatore(this, "Rows", Tabelle.nr(e), { conTabella { Tabelle.togliRiga(it, sc?.first ?: (Tabelle.nr(it) - 1)) }; cella = null },
                    { conTabella { Tabelle.aggiungiRiga(it, sc?.first ?: (Tabelle.nr(it) - 1)) } })
            }
            riga().apply {
                contatore(this, "Columns", Tabelle.nc(e), { conTabella { Tabelle.togliColonna(it, sc?.second ?: (Tabelle.nc(it) - 1)) }; cella = null },
                    { conTabella { Tabelle.aggiungiColonna(it, sc?.second ?: (Tabelle.nc(it) - 1)) } })
            }
            if (sc != null) {
                val (r, c) = sc
                val k = Tabelle.cella(e, r, c)!!
                // lo stile della cella (come per un testo: Title, Word…), sotto la cella scelta
                testaStiliCella(g, e, r, c, fatto = false)
                righeColori(k.fondo, nessuno = true, etichetta = "Fill") { col -> conTabella { Tabelle.conCella(it, r, c, Tabelle.cella(it, r, c)!!.copy(fondo = col)) } }
                riga().apply {
                    listOf(Elemento.SINISTRA to "Left", Elemento.CENTRO to "Center", Elemento.DESTRA to "Right").forEach { (a, n) ->
                        addView(pillola(n, k.allinea == a, uguale = true) { conTabella { Tabelle.conCella(it, r, c, Tabelle.cella(it, r, c)!!.copy(allinea = a)) } })
                    }
                }
                riga().apply {
                    val puo = c + Tabelle.larga(e, r, c) < Tabelle.nc(e)
                    addView(pillola("Merge right", false, uguale = true) { if (puo) conTabella { Tabelle.unisci(it, r, c) } }.apply { isEnabled = puo; alpha = if (puo) 1f else EditorActivity.SPENTO })
                    val unita = Tabelle.larga(e, r, c) > 1
                    addView(pillola("Split", false, uguale = true) { if (unita) conTabella { Tabelle.dividi(it, r, c) } }.apply { isEnabled = unita; alpha = if (unita) 1f else EditorActivity.SPENTO })
                }
            }
            rigaFont(e)
            rigaMisura(g, e.misura) { m -> conTabella { it.copy(misura = m) } }
            righeColori(e.colore, nessuno = false, etichetta = "Text") { col -> conTabella { it.copy(colore = col) } }
            righeColori(e.contorno, nessuno = true, etichetta = "Lines") { col -> conTabella { it.copy(contorno = col) } }
            aiuto("Tap a cell to pick it · tap it again to write · drag the pink marks to resize")
        }
        azioniOggetto(e, sc?.let { (r, c) -> Azione(R.drawable.ic_tastiera, "Type") { scriviIn(Tabelle.cella(e, r, c)?.testo?.length ?: 0) } }, spazio = false)
    }

    // ---------------------------------------------------------------- testi sulla pagina
    // Mock design/grafica/testo.html: un tocco sceglie la riga, un tocco sulla riga scelta scrive lì
    // (la tastiera scrive dentro la pagina, niente finestra «Edit»), tenere premuto sceglie un pezzo.
    // La stessa barra (B I U · colore · − misura +) vale per la riga, per il pezzo e sopra la tastiera.

    /**
     * Il carattere di un testo nuovo: quello più usato nel progetto; in un progetto da stampa (A4/A5) senza
     * testi, Liberation Sans (entra nel PDF come TrueType; quello del telefono entra come Type 3).
     */
    private fun carattereNuovo(g: Grafica): String =
        g.elementi.filter { it.tipo == Elemento.TESTO }.groupingBy { it.font }.eachCount().maxByOrNull { it.value }?.key
            ?: if (g.formato.stampa) "liberation" else Caratteri.PRIMO

    /** «Text»: un testo nuovo in mezzo alla pagina che si vede, già scelto e da riscrivere. */
    private fun nuovoTesto() {
        val g = storia.adesso
        val ultimo = g.elementi.lastOrNull { it.tipo == Elemento.TESTO }
        val t = "Your text"
        // pagina ingrandita: largo quanto si vede (non esce dai lati dello schermo)
        val largo = minOf(760f, vista.largoVisto() * 0.8f)
        val base = Elemento(0, Elemento.TESTO, 160f, g.alto / 2f - 60f, largo, 120f, testo = t,
            // la misura più usata nella pagina (pagina 71: 6 pt), non quella dell'ultima riga (4,8 pt, stretta)
            font = ultimo?.font ?: carattereNuovo(g),
            misura = g.di(pagina).filter { it.tipo == Elemento.TESTO }.groupingBy { it.misura }.eachCount().maxByOrNull { it.value }?.key ?: 96f,
            colore = ultimo?.colore ?: Grafiche.contrario(g.sfondo), stile = ultimo?.stile ?: Testo.SEMPLICE,
            secondo = ultimo?.secondo ?: 0, spessoreTesto = ultimo?.spessoreTesto ?: Testo.SPESSORE_STD)
            .let { b -> val k = Grafiche.stileDelNuovo(g, stileScelto, ultimo); g.stili.getOrNull(k - 1)?.let { Grafiche.conStile(b, it, k) } ?: b.copy(classe = 0) }
        val n0 = Grafiche.aggiungi(g, base.copy(h = GraficaDisegno.altezzaTesto(this, base)), pagina, vista.centroVisto())
        // con margini e colonne: largo quanto la colonna sotto il centro di ciò che si vede, attaccato a lei
        val col = if (conGuide) Grafiche.colonnaVicina(g, pagina, vista.centroVisto()?.first ?: (Grafica.LARGO / 2f)) else null
        val n = if (col == null) n0 else n0.elementi.last().let { x ->
            val w = col.second - col.first
            Grafiche.sostituisci(n0, x.copy(x = col.first, w = w).let { it.copy(h = GraficaDisegno.altezzaTesto(this, it)) })
        }
        scelto = n.elementi.last().id; modo = Modo.AGGIUNGI
        fai(n)
        // tutto scelto: la prima lettera scritta lo sostituisce
        pezzo = 0 until t.length; vista.scelto = scelto; vista.pezzo = pezzo
        scriviIn(t.length)
    }

    /** Si smette di scrivere: la tastiera scende; un testo rimasto vuoto sparisce. */
    private fun smettiDiScrivere(id: Long?) {
        if (!vista.scrivendo) return
        vista.cursoreA(-1)
        pezzo = null; vista.pezzo = null
        val e = storia.adesso.trova(id)
        // un riquadro del filo non sparisce vuoto: il testo si riversa dal primo (può tornare a riempirlo)
        if (e != null && e.filo != 0L) fai(GraficaDisegno.scorri(this, storia.adesso, e.filo, crea = true))
        else if (e != null && e.tipo == Elemento.TESTO && e.testo.isBlank()) {
            if (scelto == id) scelto = null
            fai(Grafiche.elimina(storia.adesso, e.id))
        } else ispettore()
    }

    /** I fili toccati da questo gesto si riversano (mai mentre si scrive: il cursore resta dov'è). */
    private fun rifili(g: Grafica): Grafica {
        if (vista.scrivendo) return g
        var n = g
        for (k in g.elementi.filter { it.filo != 0L }.map { it.filo }.distinct()) {
            if (Grafiche.filoDi(g, k) != Grafiche.filoDi(storia.adesso, k)) n = GraficaDisegno.scorri(this, n, k, crea = true)
        }
        return n
    }

    /**
     * «Flow»: il testo diventa largo quanto la colonna e alto quanto i margini, e continua da solo nelle colonne
     * e nelle pagine dopo (aggiunte se servono), come InDesign. Senza margini: quelli di base (15 mm).
     */
    private fun versa(e: Elemento) {
        var g = storia.adesso
        if (Grafiche.guide(g, e.pagina) == null) g = g.copy(gabbia = g.gabbia ?: Gabbia())
        val q = Grafiche.guide(g, e.pagina) ?: run { avviso("The margins leave no room on the page"); return }
        val col = Grafiche.colonnaVicina(g, e.pagina, e.cx) ?: (q.l to q.r)
        val k = (g.elementi.maxOfOrNull { it.filo } ?: 0L) + 1
        val pronto = e.copy(filo = k, filoN = 0, x = col.first, y = q.t, w = col.second - col.first, h = q.b - q.t, rot = 0f, gruppo = 0L)
        val prima = g.pagine
        val n = GraficaDisegno.scorri(this, Grafiche.sostituisci(g, pronto), k, crea = true)
        val quanti = Grafiche.filoDi(n, k).size
        fai(n)
        avviso("The text flows in $quanti box" + (if (quanti == 1) "" else "es") + (if (n.pagine > prima) " · ${n.pagine - prima} new page" + (if (n.pagine - prima == 1) "" else "s") else ""))
    }

    /** Il filo torna un testo solo, nel primo riquadro, alto quanto le sue righe; gli altri riquadri spariscono. */
    private fun sciogli(e: Elemento) {
        val g = storia.adesso
        val r = Grafiche.filoDi(g, e.filo)
        if (r.isEmpty()) return
        val (t, sg) = Grafiche.testoDelFilo(r)
        val primo = r.first().copy(testo = t, segni = sg, filo = 0L, filoN = 0)
        val via = r.drop(1).map { it.id }.toSet()
        scelto = primo.id
        fai(Grafiche.sostituisci(g.copy(elementi = g.elementi.filter { it.id !in via }), primo.copy(h = GraficaDisegno.altezzaTesto(this, primo))))
    }

    /** La barra sta sopra la tastiera, la vista sa quanto è coperto. */
    private fun sopraTastiera() {
        if (!::barraTastiera.isInitialized) return
        val ins = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
        val tastiera = ins?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val loc = IntArray(2); corniceVista.getLocationInWindow(loc)
        val sotto = window.decorView.height - (loc[1] + corniceVista.height)
        val su = (tastiera - sotto).coerceAtLeast(0)
        val lp = barraTastiera.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != su) { lp.bottomMargin = su; barraTastiera.layoutParams = lp }
        vista.coperto = if (barraTastiera.visibility == View.VISIBLE) su + 60 * dp else 0f
    }

    /** Dove agisce la barra: un testo, o la cella (r,c) della tabella scelta (i suoi segni). */
    private data class Scritto(val e: Elemento, val r: Int = -1, val c: Int = -1) {
        val inCella get() = r >= 0
        val testo: String get() = if (inCella) Tabelle.cella(e, r, c)?.testo ?: "" else e.testo
        val segni: List<Segno> get() = if (inCella) Tabelle.cella(e, r, c)?.segni ?: emptyList() else e.segni
        fun con(t: String, sg: List<Segno>): Elemento =
            if (inCella) Tabelle.cella(e, r, c)?.let { Tabelle.conCella(e, r, c, it.copy(testo = Tabelle.pulito(t), segni = sg)) } ?: e
            else e.copy(testo = t, segni = sg)
        fun conSegni(sg: List<Segno>) = con(testo, sg)
    }

    /** Il testo scelto, o la cella scelta della tabella scelta; null = niente su cui scrivere. */
    private fun scritto(g: Grafica = storia.adesso): Scritto? {
        val e = g.trova(scelto) ?: return null
        val k = cella
        return when {
            e.tipo == Elemento.TESTO -> Scritto(e)
            e.tipo == Elemento.TABELLA && k != null && Tabelle.cella(e, k.first, k.second) != null -> Scritto(e, k.first, k.second)
            else -> null
        }
    }

    /** Su cosa agisce la barra: il pezzo scelto; scrivendo senza pezzo, la parola del cursore; se no tutto. */
    private fun bersaglio(sc: Scritto): Pair<IntRange, Boolean> {
        val t = sc.testo
        val p = pezzo?.takeIf { !it.isEmpty() && it.last < t.length }
        if (p != null) return p to false
        if (vista.scrivendo) {
            val w = Ricco.parola(t, vista.cursore)
            if (!w.isEmpty() && t.isNotEmpty() && t[w.first].isLetterOrDigit()) return w to false
        }
        // una cella intera = tutti i suoi segni (font, misura e colore di base sono della tabella)
        return 0 until t.length to !sc.inCella
    }

    /** L'aspetto della prima lettera del bersaglio (colore, font, misura veri, non «come il testo»). */
    private fun aspettoDi(sc: Scritto, r: IntRange): Aspetto {
        val e = sc.e
        val a = Ricco.aspetti(sc.testo.length, sc.segni).getOrNull(r.first) ?: Aspetto.NORMALE
        return Aspetto(a.stile, if (a.colore != 0) a.colore else e.colore, a.font.ifEmpty { e.font }, if (a.misura > 0f) a.misura else e.misura)
    }

    /** Tutto il bersaglio ha la stessa misura? (se no la barra dice «Mixed») */
    private fun misuraUnica(sc: Scritto, r: IntRange): Boolean {
        val a = Ricco.aspetti(sc.testo.length, sc.segni)
        return r.map { i -> a.getOrNull(i)?.misura?.takeIf { it > 0f } ?: sc.e.misura }.distinct().size <= 1
    }

    /** Un cambio sul bersaglio: sulla riga intera va nell'oggetto e sparisce dai pezzi. */
    private fun suTesto(intero: Boolean, sc: Scritto, pezzoF: (List<Segno>) -> List<Segno>, interoF: (Elemento) -> Elemento): Elemento =
        if (intero) interoF(sc.e) else sc.conSegni(pezzoF(sc.segni))

    private fun conAltezza(n: Elemento) = if (n.tipo == Elemento.TESTO) n.copy(h = GraficaDisegno.altezzaTesto(this, n)) else n

    private fun cambiaTesto(f: (Scritto) -> Elemento) {
        val sc = scritto() ?: return
        fai(Grafiche.sostituisci(storia.adesso, conAltezza(f(sc))))
    }

    private fun tastoStile(testo: String, nome: String, acceso: Boolean, via: () -> Unit) = TextView(this).apply {
        text = testo; textSize = 18f; gravity = Gravity.CENTER; contentDescription = nome
        setTextColor(ContextCompat.getColor(context, if (acceso) R.color.accento else R.color.testo))
        when (testo) { "B" -> setTypeface(typeface, Typeface.BOLD); "I" -> setTypeface(Typeface.SERIF, Typeface.ITALIC); "U" -> paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG }
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            if (acceso) { setColor(ContextCompat.getColor(context, R.color.riga)); setStroke((2 * dp).toInt(), ContextCompat.getColor(context, R.color.accento)) }
        }
        layoutParams = LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()).apply { marginEnd = (2 * dp).toInt() }
        setOnClickListener { via() }
    }

    /** Colori e font già usati nella pagina: i primi da offrire. */
    private fun coloriPagina(g: Grafica): List<Int> = g.di(pagina).flatMap { e ->
        when (e.tipo) {
            Elemento.TESTO -> listOf(e.colore) + e.segni.map { it.colore }
            Elemento.TABELLA -> listOf(e.colore, e.contorno) + e.celle.map { it.fondo }
            Elemento.FORMA -> listOf(e.riempi, e.contorno)
            else -> emptyList()
        }
    }.filter { it != 0 }.distinct()

    private fun fontPagina(g: Grafica): List<String> =
        g.di(pagina).filter { it.tipo == Elemento.TESTO || it.tipo == Elemento.TABELLA }.flatMap { listOf(it.font) + it.segni.map { s -> s.font } }.filter { it.isNotEmpty() }

    /**
     * B I U · colore · − misura +, sul [bersaglio]. La misura va a mezzo punto; tenuto premuto
     * corre, e si vede subito sulla pagina (in Annulla entra quando si lascia); il numero si tocca per scriverlo.
     */
    private fun barraStile(r: LinearLayout, g: Grafica, sc0: Scritto) {
        val (dove, intero) = bersaglio(sc0)
        val a = aspettoDi(sc0, dove)
        val n = sc0.testo.length
        fun bit(b: Int) = !dove.isEmpty() && Ricco.tutto(n, sc0.segni, dove.first, dove.last + 1, b)
        listOf(Triple(Ricco.GRASSETTO, "B", "Bold"), Triple(Ricco.CORSIVO, "I", "Italic"), Triple(Ricco.SOTTOLINEATO, "U", "Underline")).forEach { (b, t, nome) ->
            r.addView(tastoStile(t, nome, bit(b)) {
                if (dove.isEmpty()) return@tastoStile
                cambiaTesto { it.conSegni(Ricco.cambia(it.testo.length, it.segni, dove.first, dove.last + 1, b)) }
            })
        }
        r.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.riga))
            layoutParams = LinearLayout.LayoutParams((1 * dp).toInt().coerceAtLeast(1), (28 * dp).toInt()).apply { marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt() }
        })
        r.addView(FrameLayout(this).apply {
            contentDescription = "Colour: " + FogliTesto.nomeColore(a.colore)
            layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(a.colore)
                    setStroke((2 * dp).toInt(), ContextCompat.getColor(context, R.color.testo_piano))
                }
            }, FrameLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt(), Gravity.CENTER))
            setOnClickListener {
                FogliTesto.colore(this@GraficaActivity, "Colour", a.colore, coloriPagina(storia.adesso)) { c ->
                    cambiaTesto { x -> suTesto(intero, x, { s -> Ricco.colore(x.testo.length, s, dove.first, dove.last + 1, c) },
                        { y -> y.copy(colore = c, segni = Ricco.senza(y.testo.length, y.segni) { it.copy(colore = 0) }) }) }
                }
            }
        })
        // − misura +
        val misto = !misuraUnica(sc0, dove)
        var ora = pt(g, a.misura)
        val numero = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER; fontFeatureSettings = "tnum"; setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.testo))
            text = if (misto) "Mixed" else String.format(java.util.Locale.ROOT, "%s pt", ptCorto(ora))
            contentDescription = "Size in points, tap to type"
            layoutParams = LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f)
        }
        fun metti(p: Float, fine: Boolean) {
            val m = daPt(g, p)
            val x = scritto() ?: return
            val y = conAltezza(suTesto(intero, x, { s -> Ricco.misura(x.testo.length, s, dove.first, dove.last + 1, m) },
                { y -> y.copy(misura = m, segni = Ricco.senza(y.testo.length, y.segni) { it.copy(misura = 0f) }) }))
            val nuovo = Grafiche.sostituisci(storia.adesso, y)
            if (fine) fai(nuovo) else { vivo = nuovo; vista.mostra(nuovo) }
        }
        val gruppo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = tondo(R.color.fondo, 12f)
            layoutParams = LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f).apply { marginStart = (4 * dp).toInt() }
        }
        fun passo(verso: Int) = ImageButton(this).apply {
            setImageResource(if (verso < 0) R.drawable.ic_meno else R.drawable.ic_piu)
            contentDescription = if (verso < 0) "Smaller" else "Bigger"
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (46 * dp).toInt())
            val corre = object : Runnable {
                override fun run() {
                    ora = ((ora * 2f).roundToInt() + verso) / 2f
                    ora = ora.coerceIn(1f, 400f)
                    numero.text = String.format(java.util.Locale.ROOT, "%s pt", ptCorto(ora))
                    metti(ora, false)
                    postDelayed(this, 90)
                }
            }
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { v.isPressed = true; corre.run(); v.removeCallbacks(corre); v.postDelayed(corre, 420) }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false; v.removeCallbacks(corre)
                        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                        metti(ora, true)
                    }
                }
                true
            }
        }
        gruppo.addView(passo(-1)); gruppo.addView(numero); gruppo.addView(passo(+1))
        numero.setOnClickListener { scriviNumero("Size in points", ora) { p -> metti(p, true) } }
        r.addView(gruppo)
    }

    /** 8 → «8», 6.5 → «6.5», 5.24 → «5.2» */
    private fun ptCorto(p: Float): String {
        val d = (p * 10f).roundToInt()
        return if (d % 10 == 0) "${d / 10}" else String.format(java.util.Locale.ROOT, "%.1f", d / 10f)
    }

    private fun rigaPiena(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt())
        pannello.addView(this)
    }

    /** Un bottone largo «Liberation Sans · Font ▾» scritto col suo carattere. */
    private fun bottoneLargo(nome: String, piano: String, tf: Typeface?, via: () -> Unit) = TextView(this).apply {
        text = android.text.SpannableStringBuilder(nome).apply {
            val i = length; append("  $piano ▾")
            setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(context, R.color.testo_piano)), i, length, 0)
            setSpan(android.text.style.RelativeSizeSpan(0.8f), i, length, 0)
        }
        if (tf != null) typeface = tf
        textSize = 16f; gravity = Gravity.CENTER_VERTICAL; setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(ContextCompat.getColor(context, R.color.testo))
        setPadding((14 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        background = tondo(R.color.fondo, 12f)
        layoutParams = LinearLayout.LayoutParams(0, (46 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() }
        setOnClickListener { via() }
    }

    private fun sceltaFont(dove: IntRange, intero: Boolean, attuale: String) {
        FogliTesto.font(this, attuale, fontPagina(storia.adesso)) { f ->
            cambiaTesto { x -> suTesto(intero, x, { s -> Ricco.font(x.testo.length, s, dove.first, dove.last + 1, f) },
                { y -> y.copy(font = f, segni = Ricco.senza(y.testo.length, y.segni) { it.copy(font = "") }) }) }
        }
    }

    /** Il pannello di un testo: la riga intera o il pezzo scelto. */
    private fun pannelloTesto(g: Grafica, sc: Scritto) {
        val e = sc.e
        val (dove, intero) = bersaglio(sc)
        val a = aspettoDi(sc, dove)
        val nomeFont = Caratteri.TUTTI.firstOrNull { it.first == a.font }?.second ?: a.font
        // un testo intero: in cima la fila degli stili (al posto del nome: il testo si vede già), poi «Done»
        if (intero && !sc.inCella && e.tipo == Elemento.TESTO) testaStili(g, e)
        else if (intero) testa("Text", " · " + e.testo.replace('\n', ' '))
        else {
            testa("“" + sc.testo.substring(dove.first, dove.last + 1).replace('\n', ' ') + "”", " · piece", chiudi = false)
            (pannello.getChildAt(pannello.childCount - 1) as LinearLayout).addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_chiudi); contentDescription = if (sc.inCella) "Whole cell" else "Whole line"
                background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (44 * dp).toInt())
                setOnClickListener { pezzo = null; vista.pezzo = null; ispettore() }
            })
        }
        barraStile(rigaPiena(), g, sc)
        rigaPiena().apply {
            addView(bottoneLargo(nomeFont, "Font", Caratteri.di(this@GraficaActivity, a.font)) { sceltaFont(dove, intero, a.font) })
            if (intero) {
                val icona = when (e.allinea) { Elemento.SINISTRA -> R.drawable.ic_allinea_sx; Elemento.DESTRA -> R.drawable.ic_allinea_dx; else -> R.drawable.ic_allinea_centro }
                addView(ImageButton(context).apply {
                    setImageResource(icona); contentDescription = "Alignment"
                    background = tondo(R.color.fondo, 12f)
                    layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (46 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() }
                    setOnClickListener { cambia { it.copy(allinea = (it.allinea + 1) % 3) } }
                })
                val effetto = Ui.STILI.firstOrNull { it.first == e.stile }?.second ?: "Plain"
                addView(bottoneLargo(effetto, "", null) { sceltaEffetto() }.apply {
                    layoutParams = LinearLayout.LayoutParams((112 * dp).toInt(), (46 * dp).toInt())
                })
            }
        }
        if (intero) azioniOggetto(e, Azione(R.drawable.ic_tastiera, "Type") { scriviIn(sc.testo.length) },
            seconda = if (e.tipo != Elemento.TESTO) null
                else if (e.filo == 0L) Azione(R.drawable.ic_pagina, "Flow") { versa(e) }
                else Azione(R.drawable.ic_pagina, "Unflow") { sciogli(e) })
        else azioni(listOf(
            Azione(R.drawable.ic_tastiera, "Type") { scriviIn(dove.last + 1) },
            Azione(R.drawable.ic_pulisci, "Clear style") { cambiaTesto { it.conSegni(Ricco.pulisci(it.testo.length, it.segni, dove.first, dove.last + 1)) } },
            Azione(R.drawable.ic_testo, if (sc.inCella) "Whole cell" else "Whole line") { pezzo = null; vista.pezzo = null; ispettore() },
        ))
    }

    private val altoTesto: (Elemento) -> Float = { GraficaDisegno.altezzaTesto(this, it) }

    /**
     * «Style»: None, gli stili del progetto, «+ New» (lo stile nasce da questo testo). Il testo cambiato a mano
     * rispetto al suo stile ha «Title +» e «Update Title»: lo stile prende il suo aspetto e cambiano tutti.
     * Un tocco sullo stile già scelto: rinomina o elimina.
     */
    /** L'ultimo stile toccato: vale anche per il testo nuovo ([Grafiche.stileDelNuovo]); null = nessuno ancora. */
    private var stileScelto: Int? = null

    private fun testaStili(g: Grafica, e: Elemento) {
        val st = Grafiche.stileDi(g, e)
        filaStili(g, if (st == null) 0 else e.classe, Grafiche.cambiatoDalloStile(g, e),
            usa = { k -> fai(Grafiche.usaStile(storia.adesso, setOf(e.id), k, altoTesto)) },
            aggiorna = { k, nome -> storia.adesso.trova(e.id)?.let { fai(Grafiche.cambiaStile(storia.adesso, k, Grafiche.stileDa(nome, it), altoTesto)) } },
            nuovo = { nome ->
                storia.adesso.trova(e.id)?.let { ora ->
                    val (n, nk) = Grafiche.nuovoStile(storia.adesso, Grafiche.stileDa(nome, ora))
                    fai(Grafiche.usaStile(n, setOf(e.id), nk, altoTesto)); stileScelto = nk
                }
            })
    }

    /** La fila degli stili per la cella (r,c) della tabella [t]: come per un testo. */
    private fun testaStiliCella(g: Grafica, t: Elemento, r: Int, c: Int, fatto: Boolean = true) {
        val cel = Tabelle.cella(t, r, c) ?: return
        val k = if (g.stili.getOrNull(cel.classe - 1) == null) 0 else cel.classe
        filaStili(g, k, Grafiche.cambiataDalloStile(g, t, cel),
            usa = { q -> fai(Grafiche.usaStileCella(storia.adesso, t.id, r, c, q)) },
            aggiorna = { q, nome ->
                val ora = storia.adesso.trova(t.id); val oc = ora?.let { Tabelle.cella(it, r, c) }
                if (ora != null && oc != null) fai(Grafiche.cambiaStile(storia.adesso, q, Grafiche.stileDaCella(nome, ora, oc), altoTesto))
            },
            nuovo = { nome ->
                val ora = storia.adesso.trova(t.id); val oc = ora?.let { Tabelle.cella(it, r, c) }
                if (ora != null && oc != null) {
                    val (n, nk) = Grafiche.nuovoStile(storia.adesso, Grafiche.stileDaCella(nome, ora, oc))
                    fai(Grafiche.usaStileCella(n, t.id, r, c, nk)); stileScelto = nk
                }
            }, fatto = fatto)
    }

    /**
     * La fila «Style · None · gli stili · Update · + New» con «Done»: [k] lo stile di ora (0 = nessuno), [cambiato]
     * = ha qualcosa di diverso dal suo stile. [usa] lega allo stile k (0 = staccato), [aggiorna] cambia lo stile k
     * dall'aspetto di ora, [nuovo] fa nascere uno stile da lui.
     */
    private fun filaStili(g: Grafica, k: Int, cambiato: Boolean, usa: (Int) -> Unit, aggiorna: (Int, String) -> Unit, nuovo: (String) -> Unit, fatto: Boolean = true) {
        val st = g.stili.getOrNull(k - 1)
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        pannello.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt())
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength((28 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f)
                addView(fila, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()))
            })
            if (fatto) addView(Ui.fatto(context) { scegli(null) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt())
            })
        })
        fila.apply {
            addView(etichetta("Style").apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * dp).toInt() } })
            addView(pillola("None", k == 0, stretta = true) { stileScelto = 0; usa(0) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = (6 * dp).toInt() })
            g.stili.forEachIndexed { i, x ->
                val mio = k == i + 1
                addView(pillola(if (mio && cambiato) "${x.nome} +" else x.nome, mio, stretta = true) {
                    stileScelto = i + 1
                    if (mio && !cambiato) menuStile(i + 1) else usa(i + 1)
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = (6 * dp).toInt() })
            }
            if (st != null && cambiato) addView(pillola("Update «${st.nome}»", false, stretta = true) {
                val quanti = storia.adesso.elementi.sumOf { e -> if (e.tipo == Elemento.TESTO && e.classe == k) 1 else e.celle.count { it.classe == k } }
                aggiorna(k, st.nome)
                avviso("«${st.nome}» changed on $quanti text" + if (quanti == 1) "" else "s")
            })
            addView(pillola("+ New", false, stretta = true) {
                scriviNome("Name of the text style", Grafiche.nomeNuovoStile(storia.adesso)) { nome ->
                    nuovo(nome)
                    avviso("New style «$nome»: tap it on any text to give it the same look")
                }
            })
        }
    }

    private fun menuStile(k: Int) {
        val st = storia.adesso.stili.getOrNull(k - 1) ?: return
        val quanti = storia.adesso.elementi.sumOf { e -> if (e.tipo == Elemento.TESTO && e.classe == k) 1 else e.celle.count { it.classe == k } }
        MaterialAlertDialogBuilder(this).setTitle("Style «${st.nome}» · $quanti text" + if (quanti == 1) "" else "s")
            .setItems(arrayOf("Rename", "Delete style (the texts keep their look)")) { _, i ->
                if (i == 0) scriviNome("Name of the text style", st.nome) { fai(Grafiche.rinominaStile(storia.adesso, k, it)) }
                else { stileScelto = null; fai(Grafiche.eliminaStile(storia.adesso, k)) }
            }.setNegativeButton("Cancel", null).show()
    }

    /** La tastiera si apre sul testo, col cursore in [i] (il pezzo scelto resta scelto: si scrive sopra). */
    private fun scriviIn(i: Int) {
        vista.pezzo = pezzo
        vista.cursoreA(i)
        ispettore()
    }

    /** Sopra la tastiera: la stessa barra di stile e «Done». */
    private fun barraSopraTastiera(g: Grafica, sc: Scritto) {
        val e = sc.e
        barraTastiera.removeAllViews()
        barraStile(barraTastiera, g, sc)
        // «Done» solo come segno di spunta: la barra è stretta e la misura deve leggersi intera
        barraTastiera.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_fatto); contentDescription = "Done"
            setColorFilter(ContextCompat.getColor(context, R.color.accento))
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
            setOnClickListener { smettiDiScrivere(e.id) }
        })
        sopraTastiera()
    }

    /** Plain / Shadow / Outline / Box, e sotto il colore e lo spessore dell'effetto. */
    private fun sceltaEffetto() {
        val e = storia.adesso.trova(scelto) ?: return
        val d = Dialog(this, R.style.Theme_Art)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.carta))
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 20 * dp, 20 * dp, 0f, 0f, 0f, 0f)
            }
            isClickable = true
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Effect"; textSize = 15f; setTextColor(ContextCompat.getColor(context, R.color.testo))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((4 * dp).toInt(), 0, 0, 0)
            })
            addView(Ui.fatto(context) { d.dismiss() })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
        val opzioni = OpzioniStile(this) { sec, sp, fine ->
            val ora = storia.adesso.trova(scelto) ?: return@OpzioniStile
            val n = Grafiche.sostituisci(storia.adesso, ora.copy(secondo = sec ?: ora.secondo, spessoreTesto = sp ?: ora.spessoreTesto))
            if (fine) fai(n) else { vivo = n; vista.mostra(n) }
        }.apply { mostra(e.stile, e.secondo, e.spessoreTesto) }
        val pillole = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun segna(st: Int) { for (i in 0 until pillole.childCount) Ui.segna(pillole.getChildAt(i), Ui.STILI[i].first == st) }
        Ui.STILI.forEach { (st, nome) ->
            pillole.addView(Ui.pillola(this, nome, 44) {
                cambia { it.copy(stile = st) }
                segna(st)
                storia.adesso.trova(scelto)?.let { opzioni.mostra(it.stile, it.secondo, it.spessoreTesto) }
            }.apply { layoutParams = LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() } })
        }
        segna(e.stile)
        box.addView(pillole); box.addView(opzioni)
        d.setContentView(FrameLayout(this).apply {
            setOnClickListener { d.dismiss() }
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x66000000))
        d.show()
    }

    // ---------------------------------------------------------------- salvare

    private fun apriSalva() {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) { permessoVecchio.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE); return }
        scegli(null)
        var g = storia.adesso
        val pag = pagina
        val d = Dialog(this, R.style.Theme_Art)
        d.setContentView(R.layout.dialog_salva_grafica)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val img = d.findViewById<ImageView>(R.id.finita)
        val stato = d.findViewById<TextView>(R.id.stato)
        val sinistra = d.findViewById<MaterialButton>(R.id.sinistra)
        val destra = d.findViewById<MaterialButton>(R.id.destra)
        d.findViewById<View>(R.id.chiudi).setOnClickListener { d.dismiss() }
        // l'anteprima: stessa ricetta del file; le foto non ancora in memoria si leggono qui, fuori dal filo principale.
        // Col foglio A4 si vede il primo foglio con le sue carte e i segni.
        val lato = (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceIn(320, 1080)
        fun anteprima() {
            val gg = g
            lettore.execute {
                val leggi = { u: String -> foto.get(u) ?: try { SalvaFoto.anteprima(this, Uri.parse(u), lato / 2) } catch (_: Exception) { null } }
                val b = try {
                    val fg = if (gg.formato.stampa && gg.perStampa.foglio) Impagina.foglio(gg.formato.pdfW, gg.formato.pdfH, gg.abbondanzaPt) else null
                    if (fg != null) {
                        val z = lato / maxOf(fg.w, fg.h)
                        android.graphics.Bitmap.createBitmap((fg.w * z).toInt(), (fg.h * z).toInt(), android.graphics.Bitmap.Config.ARGB_8888).also { bm ->
                            val c = android.graphics.Canvas(bm); c.drawColor(android.graphics.Color.WHITE); c.scale(z, z)
                            SalvaGrafica.disegnaFoglio(this, c, gg, fg, 0) { leggi }
                        }
                    } else GraficaDisegno.immagine(this, gg, lato, pagina = pag, foto = leggi, editor = false)
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                runOnUiThread { if (!isDestroyed && b != null) img.setImageBitmap(b) }
            }
        }
        anteprima()
        // da stampa: abbondanza, segni di taglio, carte su A4 — scelti qui e ricordati nel progetto
        if (g.formato.stampa) {
            val radice = stato.parent as LinearLayout
            val scelte = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), 0) }
            radice.addView(scelte, radice.indexOfChild(stato))
            fun righe() {
                scelte.removeAllViews()
                val fuori = pannello
                pannello = scelte
                try {
                    riga().apply {
                        addView(etichetta("Bleed"))
                        listOf(0f to "None", 3f to "3 mm", 5f to "5 mm").forEach { (mm, t) ->
                            addView(pillola(t, g.abbondanzaMm == mm, uguale = true) { fai(storia.adesso.copy(sceltaAbbondanza = mm)); g = storia.adesso; righe(); anteprima() })
                        }
                    }
                    riga().apply {
                        addView(etichetta("Cut marks"))
                        addView(pillola("Off", !g.perStampa.segni, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(segni = false))); g = storia.adesso; righe(); anteprima() })
                        addView(pillola("On", g.perStampa.segni, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(segni = true))); g = storia.adesso; righe(); anteprima() })
                    }
                    val fg = Impagina.foglio(g.formato.pdfW, g.formato.pdfH, g.abbondanzaPt)
                    if (fg != null && fg.perFoglio >= 2) riga().apply {
                        addView(etichetta("Paper"))
                        addView(pillola("Page size", !g.perStampa.foglio, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(foglio = false))); g = storia.adesso; righe(); anteprima() })
                        addView(pillola("A4 · ${fg.perFoglio} per sheet", g.perStampa.foglio, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(foglio = true))); g = storia.adesso; righe(); anteprima() })
                    }
                    // fronte e retro: le pagine a coppie; sui fogli A4 il retro va sul foglio dopo, a specchio
                    // solo per le carte (stanno più d'una su un A4): un libro non ha «fronte e retro»
                    if (g.pagine >= 2 && (fg?.perFoglio ?: 0) >= 2) riga().apply {
                        addView(etichetta("Sides"))
                        addView(pillola("One", !g.perStampa.retro, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(retro = false))); g = storia.adesso; righe(); anteprima() })
                        addView(pillola("Front + back", g.perStampa.retro, uguale = true) { fai(storia.adesso.copy(perStampa = storia.adesso.perStampa.copy(retro = true))); g = storia.adesso; righe(); anteprima() })
                    }
                    if (g.perStampa.retro && g.pagine >= 2 && (fg?.perFoglio ?: 0) >= 2) scelte.addView(TextView(this@GraficaActivity).apply {
                        textSize = 13f; setTextColor(ContextCompat.getColor(context, R.color.testo_piano)); setPadding((14 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                        text = if (fg != null && fg.perFoglio >= 2 && g.perStampa.foglio)
                            "Pages 1, 3, 5… are fronts, 2, 4, 6… their backs. Each sheet of fronts is followed by its sheet of backs: print on both sides, ${Impagina.giro(fg)}."
                        else "Pages 1, 3, 5… are fronts, 2, 4, 6… their backs."
                    })
                } finally { pannello = fuori }
            }
            righe()
        }
        // due tasti: il pieno a destra è quello del formato (stampa → PDF, post e storie → immagine)
        val pdfADestra = g.formato.stampa
        val tastoPdf = if (pdfADestra) destra else sinistra
        val tastoImg = if (pdfADestra) sinistra else destra
        tastoPdf.text = "Save PDF"
        tastoImg.text = "Save image"
        fun salva(pdf: Boolean) {
            destra.isEnabled = false; sinistra.isEnabled = false
            stato.visibility = View.VISIBLE
            stato.text = "Saving…"
            salvataggio.execute {
                val esito: Any = try {
                    if (pdf) SalvaGrafica.pdf(this, g) else SalvaGrafica.immagine(this, g, pag)
                } catch (e: OutOfMemoryError) { "the design is too big for this phone's memory" }
                catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    sinistra.isEnabled = true; destra.isEnabled = true
                    @Suppress("UNCHECKED_CAST")
                    val fatto = esito as? Pair<Uri, String>
                    if (fatto == null) { stato.text = "Not saved: $esito"; return@runOnUiThread }
                    esportata = true
                    stato.text = if (pdf) "Saved in Documents › 151 Editor" else "Saved in Pictures › 151 Editor"
                    if (fatto.first.scheme == "content") {
                        sinistra.text = "Share"
                        sinistra.setOnClickListener {
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = if (pdf) "application/pdf" else "image/png"
                                putExtra(Intent.EXTRA_STREAM, fatto.first)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "Share design"))
                        }
                    } else sinistra.visibility = View.GONE
                    destra.text = "Done"
                    destra.setOnClickListener { d.dismiss() }
                }
            }
        }
        tastoPdf.setOnClickListener { salva(true) }
        tastoImg.setOnClickListener { salva(false) }
        d.show()
    }
}
