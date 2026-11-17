package com.the151universe.art

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * I bottoni della penna, gli stessi in ogni editor: Pen · Marker · Eraser, quattro misure, i colori.
 * Comanda una [LavagnaVista]. Le scelte restano da un editor all'altro e da un giorno all'altro.
 * [fatto] non null = in alto «Draw» con «✓ Done» per finire (video e design); null = niente
 * testa (la foto ha già le sue schede in basso).
 */
class PannelloMano(private val ctx: Context, private val lavagna: LavagnaVista, private val fatto: (() -> Unit)?) {
    private val dp = ctx.resources.displayMetrics.density
    private val pref = ctx.getSharedPreferences("mano", Context.MODE_PRIVATE)
    private val strumenti = listOf(Mano.PENNA to "Pen", Mano.EVIDENZIATORE to "Marker", Mano.GOMMA to "Eraser")

    private val rigaStrumenti = riga()
    private val rigaMisure = riga()
    private val rigaColori = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
    }

    val vista: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    init {
        lavagna.strumento = pref.getInt("strumento", Mano.PENNA).coerceIn(Mano.PENNA, Mano.GOMMA)
        lavagna.colore = pref.getInt("colore", 0xFFFF5C8A.toInt())
        lavagna.spessore = Mano.SPESSORI[pref.getInt("misura", 1).coerceIn(0, Mano.SPESSORI.size - 1)]

        fatto?.let { via -> vista.addView(testa(via)) }
        for ((s, nome) in strumenti) rigaStrumenti.addView(Ui.pillola(ctx, nome, 40) { scegliStrumento(s) }.apply {
            layoutParams = LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (4 * dp).toInt() }
            setPadding(0, 0, 0, 0)
        })
        vista.addView(rigaStrumenti)
        Mano.SPESSORI.forEachIndexed { k, _ -> rigaMisure.addView(misura(k)) }
        vista.addView(rigaMisure)
        vista.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt())
            addView(rigaColori)
        })
        aggiorna()
    }

    private fun riga() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt())
    }

    private fun testa(via: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt())
        setPadding((14 * dp).toInt(), 0, 0, 0)
        addView(TextView(ctx).apply {
            text = "Draw"; textSize = 15f
            setTextColor(ContextCompat.getColor(ctx, R.color.testo))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(Ui.fatto(ctx, via).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * dp).toInt())
        })
    }

    /** Un bottone tondo con dentro un pallino grande quanto la punta. */
    private fun misura(k: Int): View = object : View(ctx) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            p.color = ContextCompat.getColor(ctx, if (scelta == k) R.color.accento else R.color.testo)
            c.drawCircle(width / 2f, height / 2f, (3f + k * 3.5f) * dp, p)
        }
    }.apply {
        contentDescription = listOf("Thin", "Medium", "Thick", "Very thick")[k]
        layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() }
        setOnClickListener { scegliMisura(k) }
    }

    private val scelta get() = Mano.SPESSORI.indexOfFirst { it == lavagna.spessore }

    private fun scegliStrumento(s: Int) { lavagna.strumento = s; pref.edit().putInt("strumento", s).apply(); aggiorna() }
    private fun scegliColore(c: Int) {
        lavagna.colore = c
        if (lavagna.strumento == Mano.GOMMA) lavagna.strumento = Mano.PENNA
        pref.edit().putInt("colore", c).putInt("strumento", lavagna.strumento).apply(); aggiorna()
    }
    private fun scegliMisura(k: Int) {
        lavagna.spessore = Mano.SPESSORI[k]
        if (lavagna.strumento == Mano.GOMMA) lavagna.strumento = Mano.PENNA
        pref.edit().putInt("misura", k).putInt("strumento", lavagna.strumento).apply(); aggiorna()
    }

    private fun aggiorna() {
        strumenti.forEachIndexed { k, (s, _) -> rigaStrumenti.getChildAt(k)?.let { Ui.segna(it, s == lavagna.strumento) } }
        // con la gomma misure e colori non contano: si vedono più spenti
        val spenti = lavagna.strumento == Mano.GOMMA
        rigaMisure.alpha = if (spenti) EditorActivity.SPENTO else 1f
        rigaColori.alpha = if (spenti) EditorActivity.SPENTO else 1f
        for (i in 0 until rigaMisure.childCount) rigaMisure.getChildAt(i).invalidate()
        // i colori comuni a tutti gli editor, coi fissati per primi e il «+»
        Ui.righeColori(ctx, rigaColori, Ui.COLORI) { scegliColore(it) }
        Ui.segnaColori(rigaColori, lavagna.colore)
    }
}
