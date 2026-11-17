package com.the151universe.art

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** I pezzi d'interfaccia comuni ai tre editor: pillole, cerchi di colore, «✓ Done», le righe di font e colori fissati. */
object Ui {
    /** I messaggi in basso coi colori dell'app (fondo carta, azione rosa), non quelli di Android. */
    fun tinta(s: com.google.android.material.snackbar.Snackbar): com.google.android.material.snackbar.Snackbar {
        val c = s.context
        s.setBackgroundTint(androidx.core.content.ContextCompat.getColor(c, R.color.riga))
        s.setTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.testo))
        s.setActionTextColor(androidx.core.content.ContextCompat.getColor(c, R.color.accento))
        return s
    }

    /** «✓ Done», lo stesso bottone in ogni pannello (mai una X, che si legge «annulla»). */
    fun fatto(ctx: Context, via: () -> Unit): View =
        android.view.LayoutInflater.from(ctx).inflate(R.layout.bottone_fatto, null, false).apply { setOnClickListener { via() } }

    val COLORI = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF5C8A.toInt(), 0xFFFFD60A.toInt(), 0xFFFF8A00.toInt(),
        0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF32D2F5.toInt(), 0xFF0A84FF.toInt(), 0xFFAF52DE.toInt(),
    )
    val STILI = listOf(Testo.SEMPLICE to "Plain", Testo.OMBRA to "Shadow", Testo.CONTORNO to "Outline", Testo.RIQUADRO to "Box")

    /** Una «pillola» da toccare, alta [alto] dp; scelta = bordo e testo accento. */
    fun pillola(ctx: Context, testo: String, alto: Int = 44, alTocco: () -> Unit): TextView = TextView(ctx).apply {
        val dp = resources.displayMetrics.density
        text = testo
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (alto * dp).toInt()).apply {
            marginEnd = (6 * dp).toInt()
        }
        setOnClickListener { alTocco() }
        segna(this, false)
    }

    fun segna(v: View, si: Boolean) {
        val ctx = v.context
        val dp = ctx.resources.displayMetrics.density
        v.background = GradientDrawable().apply {
            cornerRadius = 22 * dp
            setColor(ContextCompat.getColor(ctx, if (si) R.color.riga else R.color.fondo))
            setStroke((1.5f * dp).toInt(), ContextCompat.getColor(ctx, if (si) R.color.accento else R.color.riga))
        }
        if (v is TextView) v.setTextColor(ContextCompat.getColor(ctx, if (si) R.color.accento else R.color.testo))
    }

    /** Un cerchio di colore da 32 dp in un bersaglio da 44. */
    fun cerchio(ctx: Context, c: Int, alTocco: () -> Unit): FrameLayout {
        val dp = ctx.resources.displayMetrics.density
        return FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginEnd = (2 * dp).toInt() }
            contentDescription = "Colour"
            setOnClickListener { alTocco() }
            addView(View(ctx).apply { layoutParams = FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt(), Gravity.CENTER) })
            segnaCerchio(this, c, false)
        }
    }

    fun segnaCerchio(f: FrameLayout, c: Int, si: Boolean) {
        val ctx = f.context
        val dp = ctx.resources.displayMetrics.density
        f.background = if (si) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke((2.5f * dp).toInt(), ContextCompat.getColor(ctx, R.color.accento))
        } else null
        f.getChildAt(0).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(c); setStroke((1 * dp).toInt(), 0x55FFFFFF)
        }
    }

    // ---------------------------------------------------------------- font e colori fissati (Preferiti)

    private fun colpetto(v: View) = v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

    /**
     * La riga dei font: prima i fissati (★). Tocco = sceglie; tenuto = fissa o toglie, in ogni editor.
     * Si rifà solo se i preferiti sono cambiati da quando è stata fatta.
     */
    fun righeFont(ctx: Context, riga: LinearLayout, alto: Int = 40, scegli: (String) -> Unit) {
        if (riga.tag == Preferiti.versione) return
        riga.removeAllViews(); riga.tag = Preferiti.versione
        val fissati = Preferiti.font(ctx)
        for ((id, nome, _) in Preferiti.ordinaFont(ctx)) riga.addView(pillola(ctx, (if (id in fissati) "★ " else "") + nome, alto) { scegli(id) }.apply {
            typeface = Caratteri.di(ctx, id); textSize = 15f
            setTag(R.id.chiave, id)
            setOnLongClickListener {
                colpetto(it)
                Preferiti.cambiaFont(ctx, id)
                righeFont(ctx, riga, alto, scegli)
                true
            }
        })
    }

    fun segnaFont(riga: LinearLayout, id: String) {
        for (k in 0 until riga.childCount) riga.getChildAt(k).let { if (it.getTag(R.id.chiave) is String) segna(it, it.getTag(R.id.chiave) == id) }
    }

    /**
     * La riga dei colori: prima i fissati (con un puntino), poi quelli di [base], in fondo «+» per
     * un colore nuovo (entra fissato). Tenuto = fissa o toglie. [tieni] = quanti figli iniziali
     * della riga non sono colori (un'etichetta, «nessuno») e restano.
     */
    fun righeColori(ctx: Context, riga: LinearLayout, base: IntArray, tieni: Int = 0, scegli: (Int) -> Unit) {
        if (riga.getTag(R.id.chiave) == Preferiti.versione) return
        while (riga.childCount > tieni) riga.removeViewAt(riga.childCount - 1)
        riga.setTag(R.id.chiave, Preferiti.versione)
        val fissati = Preferiti.colori(ctx)
        val dp = ctx.resources.displayMetrics.density
        for (c in Preferiti.ordinaColori(ctx, base)) riga.addView(cerchio(ctx, c) { scegli(c) }.apply {
            setTag(R.id.chiave, c)
            if (c in fissati) addView(View(ctx).apply {
                // il puntino dei fissati
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(ctx, R.color.accento)) }
                layoutParams = FrameLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt(), Gravity.TOP or Gravity.END)
            })
            setOnLongClickListener {
                colpetto(it)
                Preferiti.cambiaColore(ctx, c)
                righeColori(ctx, riga, base, tieni, scegli)
                true
            }
        })
        riga.addView(FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
            contentDescription = "New colour"
            addView(TextView(ctx).apply {
                text = "+"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(ctx, R.color.testo))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(ContextCompat.getColor(ctx, R.color.riga)); setStroke((1 * dp).toInt(), 0x55FFFFFF)
                }
                layoutParams = FrameLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt(), Gravity.CENTER)
            })
            setOnClickListener {
                sceltaColore(ctx, fissati.firstOrNull() ?: 0xFFFF5C8A.toInt()) { c ->
                    Preferiti.aggiungiColore(ctx, c)
                    righeColori(ctx, riga, base, tieni, scegli)
                    scegli(c)
                }
            }
        })
    }

    fun segnaColori(riga: LinearLayout, attuale: Int) {
        for (k in 0 until riga.childCount) (riga.getChildAt(k) as? FrameLayout)?.let { f ->
            val c = f.getTag(R.id.chiave) as? Int ?: return@let
            segnaCerchio(f, c, c == attuale)
        }
    }

    /** Un colore nuovo: tinta, forza e luce, ognuna col cursore di 151 Editor. */
    fun sceltaColore(ctx: Context, iniziale: Int, fatto: (Int) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(iniziale, hsv)
        val prova = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()) }
        fun ridipingi() { prova.background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(android.graphics.Color.HSVToColor(hsv)) } }
        ridipingi()
        val l = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(prova)
        }
        for ((k, nome, fine) in listOf(Triple(0, "Hue", 360f), Triple(1, "Strength", 1f), Triple(2, "Light", 1f))) {
            l.addView(TextView(ctx).apply {
                text = nome; textSize = 12f; setTextColor(ContextCompat.getColor(ctx, R.color.testo_piano)); setPadding(0, (10 * dp).toInt(), 0, 0)
            })
            l.addView(CursoreVista(ctx).apply {
                da = 0f; a = fine; valore = hsv[k]
                passo = { v, verso -> v + verso * fine / 50f }
                ascoltatore = CursoreVista.Ascoltatore { v, _ -> hsv[k] = v; ridipingi() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt())
            })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("New colour")
            .setView(l)
            .setPositiveButton("Add") { _, _ -> fatto(android.graphics.Color.HSVToColor(hsv)) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * Sotto gli stili di un testo, uguale nei tre editor: con «Outline» il colore del bordo
 * e lo spessore, con «Box» il colore del fondo; con gli altri stili non si vede. «Auto» = bianco o
 * nero, il contrario del testo (com'era prima). Non decide niente: [cambia] riceve il colore
 * ([secondo], null = non toccato) o lo spessore ([spessore]), e [fine] quando il gesto è finito.
 */
class OpzioniStile(ctx: Context, private val cambia: (secondo: Int?, spessore: Float?, fine: Boolean) -> Unit) : LinearLayout(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val etichetta = TextView(ctx).apply {
        textSize = 14f; setTextColor(ContextCompat.getColor(ctx, R.color.testo_piano))
        setPadding((6 * dp).toInt(), 0, (8 * dp).toInt(), 0)
    }
    private val auto = Ui.pillola(ctx, "Auto", 40) { cambia(0, null, true) }
    private val colori = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        addView(etichetta); addView(auto)
    }
    private val rigaSpessore = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((14 * dp).toInt(), 0, (8 * dp).toInt(), 0)
    }
    val cursore = CursoreVista(ctx).apply {
        da = Testo.SPESSORE_MIN; a = Testo.SPESSORE_MAX; normale = Testo.SPESSORE_STD
        passo = { v, verso -> v + verso * 0.01f }
        ascoltatore = CursoreVista.Ascoltatore { v, fine -> cambia(null, Testo.spessoreGiusto(v), fine) }
        layoutParams = LayoutParams(0, (44 * dp).toInt(), 1f)
    }

    init {
        orientation = VERTICAL
        addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(colori, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        rigaSpessore.addView(TextView(ctx).apply {
            text = "Thickness"; textSize = 14f; setTextColor(ContextCompat.getColor(ctx, R.color.testo_piano))
            setPadding(0, 0, (8 * dp).toInt(), 0)
        })
        rigaSpessore.addView(cursore)
        addView(rigaSpessore, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
    }

    /** Si mostra per lo stile [stile], col secondo colore [secondo] (0 = Auto) e lo [spessore] di adesso. */
    fun mostra(stile: Int, secondo: Int, spessore: Float) {
        visibility = if (stile == Testo.CONTORNO || stile == Testo.RIQUADRO) View.VISIBLE else View.GONE
        if (visibility != View.VISIBLE) return
        etichetta.text = if (stile == Testo.CONTORNO) "Outline" else "Box"
        Ui.righeColori(context, colori, Ui.COLORI, tieni = 2) { c -> cambia(c, null, true) }
        Ui.segna(auto, secondo == 0)
        Ui.segnaColori(colori, secondo)
        rigaSpessore.visibility = if (stile == Testo.CONTORNO) View.VISIBLE else View.GONE
        cursore.valore = spessore
    }
}
