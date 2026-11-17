package com.the151universe.art

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService

/**
 * «Pages»: tutte le pagine in miniatura, 3 per fila, la scelta
 * con l'anello rosa, quella aperta col pallino. Sotto: Add after · Duplicate · Move · Delete.
 * Ogni gesto passa da [fai] (entra in Annulla/Rifai e nella bozza); [apri] porta all'editor.
 */
class PagineSchermo(
    private val a: AppCompatActivity,
    private val lettore: ExecutorService,
    private val adesso: () -> Grafica,
    private val fai: (Grafica) -> Unit,
    private val annulla: () -> Unit,
    private val foto: GraficaDisegno.Immagini,
    aperta: Int,
    private val apri: (Int) -> Unit,
) {
    private val dp = a.resources.displayMetrics.density
    private val d = Dialog(a, R.style.Theme_Art)
    private var scelta = aperta
    /** la pagina aperta nell'editor: segue i suoi spostamenti, e all'uscita l'editor torna su di lei */
    private var aperta = aperta
    private val radice = FrameLayout(a)
    private val titolo = TextView(a)
    private val griglia = RecyclerView(a)
    private val azioni = LinearLayout(a)
    private val mini = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: Int, value: Bitmap) = value.byteCount }
    private val inArrivo = HashSet<Int>()
    /** cambia a ogni gesto: una miniatura vecchia non entra nella memoria nuova */
    private var giro = 0
    private val largo = ((a.resources.displayMetrics.widthPixels - 56 * dp) / 3f).toInt()

    private fun c(id: Int) = ContextCompat.getColor(a, id)

    fun mostra() {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(c(R.color.fondo)) }
        // la barra: chiudi · «Pages · 72» · Open
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt())
            setPadding((4 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            addView(ImageButton(a).apply {
                setImageResource(R.drawable.ic_chiudi); contentDescription = "Close"
                background = ContextCompat.getDrawable(a, android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
                setOnClickListener { d.dismiss() }
            })
            addView(titolo.apply {
                textSize = 18f; setTextColor(c(R.color.testo)); setTypeface(typeface, Typeface.BOLD)
                setPadding((8 * dp).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(MaterialButton(a).apply {
                text = "Open"; textSize = 16f; insetTop = 0; insetBottom = 0
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }
                setOnClickListener { aperta = scelta; d.dismiss() }
            })
        })
        griglia.apply {
            layoutManager = GridLayoutManager(a, 3)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            clipToPadding = false
            adapter = Fogli()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        col.addView(griglia)
        col.addView(View(a).apply {
            setBackgroundColor(c(R.color.riga))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1))
        })
        col.addView(azioni.apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (72 * dp).toInt())
        })
        radice.addView(col)
        d.setContentView(radice)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // chiudi, indietro, Open, doppio tocco: l'editor va sempre sulla pagina aperta (spostata o no)
        d.setOnDismissListener { apri(aperta.coerceIn(0, adesso().pagine - 1)) }
        aggiorna()
        griglia.scrollToPosition(scelta)
        d.show()
    }

    private fun aggiorna() {
        val g = adesso()
        scelta = scelta.coerceIn(0, g.pagine - 1)
        titolo.text = android.text.SpannableStringBuilder("Pages").apply {
            val i = length; append(" · ${g.pagine}")
            setSpan(android.text.style.ForegroundColorSpan(c(R.color.testo_piano)), i, length, 0)
            setSpan(android.text.style.StyleSpan(Typeface.NORMAL), i, length, 0)
        }
        azioni.removeAllViews()
        val sola = g.pagine <= 1
        azione(R.drawable.ic_piu, "Add after", false, g.pagine >= Grafica.PAGINE_MAX) {
            val (n, p) = Grafiche.aggiungiPagina(adesso(), scelta); cambiato(n, p) { if (it >= p) it + 1 else it }
        }
        azione(R.drawable.ic_duplica, "Duplicate", false, g.pagine >= Grafica.PAGINE_MAX) {
            val s0 = scelta
            val (n, p) = Grafiche.duplicaPagina(adesso(), s0); cambiato(n, p) { if (it > s0) it + 1 else it }
        }
        azione(R.drawable.ic_sposta, "Move", false, sola) { sposta() }
        azione(R.drawable.ic_cestino, "Delete", true, sola) {
            val via = scelta
            val prima = aperta
            cambiato(Grafiche.eliminaPagina(adesso(), via), via.coerceAtMost(adesso().pagine - 2)) { if (it > via) it - 1 else it }
            // sopra la fila delle azioni: un tocco in ritardo su Undo non cade su Delete
            Ui.tinta(Snackbar.make(radice, "Page ${via + 1} deleted", 6000)).setAnchorView(azioni)
                .setAction("Undo") { annulla(); scelta = via; aperta = prima; rifai() }.show()
        }
    }

    /** Dopo un annulla da fuori: le miniature si rifanno. */
    private fun rifai() { giro++; mini.evictAll(); aggiorna(); griglia.adapter?.notifyDataSetChanged() }

    private fun cambiato(n: Grafica, nuova: Int, segui: (Int) -> Int) {
        aperta = segui(aperta).coerceIn(0, n.pagine - 1)
        fai(n); scelta = nuova; rifai(); griglia.scrollToPosition(scelta)
    }

    private fun azione(icona: Int, nome: String, rosso: Boolean, spenta: Boolean, via: () -> Unit) {
        val colore = c(if (rosso) R.color.accento else R.color.testo)
        azioni.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(a, android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            contentDescription = nome; isEnabled = !spenta; alpha = if (spenta) EditorActivity.SPENTO else 1f
            setOnClickListener { via() }
            addView(ImageView(a).apply {
                setImageResource(icona); setColorFilter(colore)
                layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt())
            })
            addView(TextView(a).apply { text = nome; textSize = 13f; setTextColor(colore); setSingleLine(); gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, 0) })
        })
    }

    /** Il foglio della posizione: − / numero / +, First, Last, Move here. */
    private fun sposta() {
        val g = adesso()
        val da = scelta
        var dove = da
        val fondo = Dialog(a, R.style.Theme_Art)
        val numero = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; textSize = 24f; gravity = Gravity.CENTER
            setTextColor(c(R.color.testo)); setText("${dove + 1}"); setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams((120 * dp).toInt(), (56 * dp).toInt())
        }
        fun metti(p: Int) { dove = p.coerceIn(0, g.pagine - 1); numero.setText("${dove + 1}") }
        fun tondo(icona: Int, nome: String, via: () -> Unit) = ImageButton(a).apply {
            setImageResource(icona); contentDescription = nome
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(R.color.riga)) }
            layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply { marginStart = (8 * dp).toInt(); marginEnd = (8 * dp).toInt() }
            setOnClickListener { via() }
        }
        fun bottone(t: String, pieno: Boolean, via: () -> Unit) = MaterialButton(a, null,
            if (pieno) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; insetTop = 0; insetBottom = 0; setSingleLine()
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            if (!pieno) setTextColor(c(R.color.testo))
            // «Move here» è il gesto vero: più largo, su una riga sola
            layoutParams = LinearLayout.LayoutParams(0, (48 * dp).toInt(), if (pieno) 1.5f else 1f).apply { marginStart = (4 * dp).toInt(); marginEnd = (4 * dp).toInt() }
            setOnClickListener { via() }
        }
        val corpo = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(c(R.color.carta)); val r = 14 * dp; cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(TextView(a).apply {
                text = "Move page ${da + 1} to"; textSize = 16f; setTextColor(c(R.color.testo)); gravity = Gravity.CENTER
                setPadding(0, 0, 0, (12 * dp).toInt())
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(tondo(R.drawable.ic_meno, "Earlier") { metti(dove - 1) })
                addView(numero)
                addView(tondo(R.drawable.ic_piu, "Later") { metti(dove + 1) })
            })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (16 * dp).toInt(), 0, 0)
                addView(bottone("First", false) { metti(0) })
                addView(bottone("Last", false) { metti(g.pagine - 1) })
                addView(bottone("Move here", true) {
                    val scritto = numero.text.toString().toIntOrNull()?.let { it - 1 } ?: dove
                    val a2 = scritto.coerceIn(0, g.pagine - 1)
                    fondo.dismiss()
                    if (a2 != da) cambiato(Grafiche.spostaPagina(adesso(), da, a2), a2) { Grafiche.posto(da, a2, it) }
                })
            })
        }
        fondo.setContentView(FrameLayout(a).apply {
            setOnClickListener { fondo.dismiss() }
            addView(corpo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            corpo.isClickable = true
        })
        fondo.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
        }
        fondo.show()
    }

    // ---------------------------------------------------------------- la griglia

    private inner class Cella(v: LinearLayout, val img: ImageView, val num: TextView) : RecyclerView.ViewHolder(v)

    private inner class Fogli : RecyclerView.Adapter<Cella>() {
        override fun getItemCount() = adesso().pagine

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cella {
            val g = adesso()
            val alto = (largo * (g.alto / Grafica.LARGO)).toInt()
            val img = ImageView(a).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setPadding((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(largo, alto + (6 * dp).toInt())
            }
            val num = TextView(a).apply { textSize = 13f; gravity = Gravity.CENTER; setPadding(0, (6 * dp).toInt(), 0, (10 * dp).toInt()) }
            val v = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(img); addView(num)
            }
            return Cella(v, img, num)
        }

        override fun onBindViewHolder(h: Cella, pos: Int) {
            val si = pos == scelta
            h.img.background = GradientDrawable().apply {
                cornerRadius = 6 * dp
                if (si) setStroke((2.5f * dp).toInt(), c(R.color.accento)) else setColor(0)
            }
            h.num.text = if (pos == aperta) "${pos + 1}  ●" else "${pos + 1}"
            h.num.setTextColor(c(if (si) R.color.accento else R.color.testo_piano))
            h.itemView.contentDescription = "Page ${pos + 1}"
            h.itemView.setOnClickListener {
                if (scelta == pos) { aperta = pos; d.dismiss(); return@setOnClickListener }
                val prima = scelta; scelta = pos
                notifyItemChanged(prima); notifyItemChanged(pos)
            }
            val b = mini.get(pos)
            h.img.setImageBitmap(b)
            if (b == null && inArrivo.add(pos)) {
                val g = adesso(); val mio = giro
                lettore.execute {
                    val bm = try { GraficaDisegno.immagine(a, g, largo, foto, editor = false, pagina = pos) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                    a.runOnUiThread {
                        inArrivo.remove(pos)
                        if (mio != giro || bm == null || a.isDestroyed) return@runOnUiThread
                        mini.put(pos, bm); notifyItemChanged(pos)
                    }
                }
            }
        }
    }
}
