package com.the151universe.art

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

/** La casa: gli editor (foto, video, audio, design) e TUTTE le bozze insieme, la piu' recente in alto. */
class MainActivity : AppCompatActivity() {
    /** video ([Archivio.Voce]), foto ([Archivio.VoceFoto]), audio ([Archivio.VoceSuono]) e design ([Archivio.VoceGrafica]) mescolati per data */
    private var voci: List<Any> = emptyList()
    private val lavoro = Executors.newSingleThreadExecutor()
    private val anteprime = LruCache<String, Bitmap>(40)
    private lateinit var elenco: RecyclerView

    // New project: prima si scelgono i video, poi nasce la bozza e si apre l'editor
    private val scegli = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lavoro.execute {
            val (clip, saltate) = Archivio.clipDa(this, uris)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (clip.isEmpty()) { avviso("These videos can't be read."); return@runOnUiThread }
                val id = Archivio.nuovoId()
                if (!Archivio.salva(this, id, Bozza("Video ${voci.count { it is Archivio.Voce } + 1}", clip))) {
                    avviso("The phone refused to save the project."); return@runOnUiThread
                }
                if (saltate > 0) avviso("$saltate video could not be read and were skipped.")
                apri(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Archivio.migra(this)
        elenco = findViewById(R.id.bozze)
        elenco.layoutManager = LinearLayoutManager(this)
        elenco.adapter = Adattatore()
        findViewById<View>(R.id.nuovo).setOnClickListener { sceltaTipo() }
    }

    override fun onResume() {
        super.onResume()
        ricarica()
        lavoro.execute { Archivio.pulisciMedia(this) }
    }

    override fun onDestroy() {
        lavoro.shutdownNow()
        super.onDestroy()
    }

    @Suppress("NotifyDataSetChanged")
    private fun ricarica() {
        voci = (Archivio.elenco(this) + Archivio.elencoFoto(this) + Archivio.elencoSuoni(this) + Archivio.elencoGrafica(this)).sortedByDescending { modificata(it) }
        elenco.adapter?.notifyDataSetChanged()
        findViewById<View>(R.id.vuoto).visibility = if (voci.isEmpty()) View.VISIBLE else View.GONE
    }

    /** «New project»: che cosa si crea. Solo gli editor che ci sono davvero, niente bottoni morti. */
    private fun sceltaTipo() {
        val d = BottomSheetDialog(this)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
        }
        fun riga(icona: Int, titolo: String, sotto: String, via: () -> Unit) {
            col.addView(LayoutInflater.from(this).inflate(R.layout.item_tipo, col, false).apply {
                findViewById<ImageView>(R.id.icona).setImageResource(icona)
                findViewById<TextView>(R.id.titolo).text = titolo
                findViewById<TextView>(R.id.sotto).text = sotto
                setOnClickListener { d.dismiss(); via() }
            })
        }
        riga(R.drawable.ic_foto, "Photo", "Crop, light, filters, text") { startActivity(Intent(this, FotoActivity::class.java)) }
        riga(R.drawable.ic_video, "Video", "Clips, cuts, music, text") {
            scegli.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        riga(R.drawable.ic_musica, "Audio", "Music, voice, sounds") { nuovoAudio() }
        riga(R.drawable.ic_grafica, "Design", "Flyers, posts, documents") { startActivity(Intent(this, GraficaActivity::class.java)) }
        d.setContentView(col)
        d.show()
    }

    /** Un progetto Audio nasce vuoto e si apre: i suoni si aggiungono dentro. */
    private fun nuovoAudio() {
        val id = Archivio.nuovoId()
        if (!Archivio.salvaSuono(this, id, ProgettoSuono("Audio ${voci.count { it is Archivio.VoceSuono } + 1}"))) {
            avviso("The phone refused to save the project."); return
        }
        startActivity(Intent(this, SuonoActivity::class.java).putExtra(SuonoActivity.ID, id))
    }

    /** «Just now» sotto il minuto, poi «5 minutes ago»… (Android scriverebbe «0 minutes ago»). */
    private fun quando(t: Long): CharSequence {
        val ora = System.currentTimeMillis()
        return if (ora - t in 0 until DateUtils.MINUTE_IN_MILLIS) "Just now"
        else DateUtils.getRelativeTimeSpanString(t, ora, DateUtils.MINUTE_IN_MILLIS)
    }

    private fun modificata(v: Any) = when (v) { is Archivio.Voce -> v.modificata; is Archivio.VoceFoto -> v.modificata; is Archivio.VoceGrafica -> v.modificata; is Archivio.VoceSuono -> v.modificata; else -> 0L }
    private fun nomeDi(v: Any) = when (v) { is Archivio.Voce -> v.bozza.nome; is Archivio.VoceFoto -> v.bozza.nome; is Archivio.VoceGrafica -> v.grafica.nome; is Archivio.VoceSuono -> v.suono.nome; else -> "" }

    private fun apriFoto(id: String) {
        startActivity(Intent(this, FotoActivity::class.java).putExtra(FotoActivity.ID, id))
    }

    private fun apri(id: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.ID, id))
    }

    private fun avviso(t: String) = Snackbar.make(findViewById(R.id.radice), t, Snackbar.LENGTH_LONG).show()

    private fun rinomina(v: Any) {
        val campo = EditText(this).apply { setText(nomeDi(v)); setSelection(text.length); setSingleLine() }
        val dp = resources.displayMetrics.density
        val box = android.widget.FrameLayout(this).apply { setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0); addView(campo) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = campo.text.toString().trim()
                if (n.isEmpty()) return@setPositiveButton
                when (v) {
                    is Archivio.Voce -> Archivio.salva(this, v.id, v.bozza.copy(nome = n))
                    is Archivio.VoceFoto -> Archivio.salvaFoto(this, v.id, v.bozza.copy(nome = n))
                    is Archivio.VoceGrafica -> Archivio.salvaGrafica(this, v.id, v.grafica.copy(nome = n))
                    is Archivio.VoceSuono -> Archivio.salvaSuono(this, v.id, v.suono.copy(nome = n))
                }
                ricarica()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun elimina(v: Any) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete “${nomeDi(v)}”?")
            .setMessage(when (v) {
                is Archivio.VoceFoto -> "Only the draft goes away. Your photos in the gallery stay where they are."
                is Archivio.VoceGrafica -> "Only the draft goes away. Images and PDFs you saved stay where they are."
                is Archivio.VoceSuono -> "Only the draft goes away. Sounds you saved stay where they are."
                else -> "Only the draft goes away. Your videos in the gallery stay where they are."
            })
            .setPositiveButton("Delete") { _, _ ->
                when (v) {
                    is Archivio.Voce -> Archivio.elimina(this, v.id)
                    is Archivio.VoceFoto -> Archivio.eliminaFoto(this, v.id)
                    is Archivio.VoceGrafica -> Archivio.eliminaGrafica(this, v.id)
                    is Archivio.VoceSuono -> Archivio.eliminaSuono(this, v.id)
                }
                ricarica()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun menu(a: View, v: Any) {
        PopupMenu(this, a).apply {
            menu.add("Rename").setOnMenuItemClickListener { rinomina(v); true }
            menu.add("Delete").setOnMenuItemClickListener { elimina(v); true }
        }.show()
    }

    private inner class Riga(v: View) : RecyclerView.ViewHolder(v) {
        val anteprima: ImageView = v.findViewById(R.id.anteprima)
        val nome: TextView = v.findViewById(R.id.nome)
        val dettagli: TextView = v.findViewById(R.id.dettagli)
        val menu: View = v.findViewById(R.id.menu)
    }

    private inner class Adattatore : RecyclerView.Adapter<Riga>() {
        override fun getItemCount() = voci.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            Riga(LayoutInflater.from(p.context).inflate(R.layout.item_bozza, p, false))

        override fun onBindViewHolder(h: Riga, pos: Int) {
            val v = voci[pos]
            val quando = quando(modificata(v))
            h.nome.text = nomeDi(v).ifBlank { "Untitled" }
            h.anteprima.setImageBitmap(null)
            h.anteprima.scaleType = ImageView.ScaleType.CENTER_CROP
            if (v is Archivio.VoceSuono) {
                val n = v.suono.pezzi.size
                h.dettagli.text = "Audio · $n sound${if (n == 1) "" else "s"} · ${Progetto.tempo(Suono.totaleMs(v.suono.pezzi))} · $quando"
                h.anteprima.tag = null
                h.anteprima.scaleType = ImageView.ScaleType.CENTER
                h.anteprima.setImageResource(R.drawable.ic_musica)
                h.itemView.setOnClickListener { startActivity(Intent(this@MainActivity, SuonoActivity::class.java).putExtra(SuonoActivity.ID, v.id)) }
                h.menu.setOnClickListener { a -> menu(a, v) }
                return
            }
            if (v is Archivio.VoceFoto) {
                h.dettagli.text = "Photo · $quando"
                // la miniatura e' la foto MODIFICATA: la chiave cambia con le modifiche
                val chiave = "foto:" + v.id + "@" + v.bozza.m.hashCode()
                h.anteprima.tag = chiave
                val gia = anteprime.get(chiave)
                if (gia != null) h.anteprima.setImageBitmap(gia)
                else lavoro.execute {
                    val b = SalvaFoto.miniatura(this@MainActivity, Uri.parse(v.bozza.uri), v.bozza.m, 240) ?: return@execute
                    anteprime.put(chiave, b)
                    runOnUiThread { if (h.anteprima.tag == chiave) h.anteprima.setImageBitmap(b) }
                }
                h.itemView.setOnClickListener { apriFoto(v.id) }
                h.menu.setOnClickListener { a -> menu(a, v) }
                return
            }
            if (v is Archivio.VoceGrafica) {
                val n = v.grafica.pagine
                h.dettagli.text = "Design · ${v.grafica.formato.nome}${if (n > 1) " · $n pages" else ""} · $quando"
                // la pagina intera, non un quadrato ritagliato in mezzo
                h.anteprima.scaleType = ImageView.ScaleType.FIT_CENTER
                val chiave = "grafica:" + v.id + "@" + v.grafica.hashCode()
                h.anteprima.tag = chiave
                val gia = anteprime.get(chiave)
                if (gia != null) h.anteprima.setImageBitmap(gia)
                else lavoro.execute {
                    val b = Anteprime.grafica(this@MainActivity, v.grafica, 240) ?: return@execute
                    anteprime.put(chiave, b)
                    runOnUiThread { if (h.anteprima.tag == chiave) h.anteprima.setImageBitmap(b) }
                }
                h.itemView.setOnClickListener { startActivity(Intent(this@MainActivity, GraficaActivity::class.java).putExtra(GraficaActivity.ID, v.id)) }
                h.menu.setOnClickListener { a -> menu(a, v) }
                return
            }
            v as Archivio.Voce
            val n = v.bozza.clip.size
            h.dettagli.text = "Video · $n clip${if (n == 1) "" else "s"} · ${Progetto.tempo(Progetto.totaleMs(v.bozza.clip))} · $quando"
            val primo = v.bozza.clip.firstOrNull()
            if (primo != null) {
                val chiave = "${primo.uri}@${primo.inizioMs}"
                h.anteprima.tag = chiave
                val gia = anteprime.get(chiave)
                if (gia != null) h.anteprima.setImageBitmap(gia)
                else lavoro.execute {
                    val b = Archivio.fotogramma(this@MainActivity, Uri.parse(primo.uri), primo.inizioMs) ?: return@execute
                    anteprime.put(chiave, b)
                    runOnUiThread { if (h.anteprima.tag == chiave) h.anteprima.setImageBitmap(b) }
                }
            }
            h.itemView.setOnClickListener { apri(v.id) }
            h.menu.setOnClickListener { a -> menu(a, v) }
        }
    }
}
