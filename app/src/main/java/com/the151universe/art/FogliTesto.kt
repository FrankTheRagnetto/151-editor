package com.the151universe.art

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * I due fogli che salgono dal fondo per il testo del Design:
 * il colore («On this page» prima, poi la tavolozza del quaderno, «+» per uno a mano) e il font
 * (quelli della pagina, poi per il testo, poi per i titoli), ognuno scritto col suo carattere.
 * Niente colori di base del telefono: fondo carta, accento rosa solo su quello scelto.
 */
object FogliTesto {
    /** La tavolozza del quaderno: il nero della stampa di InDesign (#231F20) per primo. */
    val QUADERNO = listOf(
        0xFF231F20.toInt() to "Print black", 0xFF58595B.toInt() to "Dark grey", 0xFF939598.toInt() to "Grey",
        0xFFFFFFFF.toInt() to "White", 0xFFD7263D.toInt() to "Red", 0xFFE8772E.toInt() to "Orange",
        0xFFF2B705.toInt() to "Yellow", 0xFF2E9E5B.toInt() to "Green", 0xFF1F6FB2.toInt() to "Blue",
        0xFF7A3FA0.toInt() to "Purple",
    )

    /** I font per leggere (il quaderno, il dizionario); gli altri sono per i titoli. */
    private val PER_TESTO = listOf("liberation", "sans", "serif", "typewriter", "spacemono")

    fun nomeColore(c: Int): String = QUADERNO.firstOrNull { it.first == c }?.second
        ?: String.format("#%06X", c and 0xFFFFFF)

    private fun foglio(ctx: Context, titolo: String, piano: String, corpo: (LinearLayout, Dialog) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val d = Dialog(ctx, R.style.Theme_Art)
        val dentro = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        val testa = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt())
            addView(TextView(ctx).apply {
                text = android.text.SpannableStringBuilder(titolo).apply {
                    val a = length; append(piano)
                    setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.testo_piano)), a, length, 0)
                }
                textSize = 15f; setTextColor(ContextCompat.getColor(ctx, R.color.testo))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Ui.fatto(ctx) { d.dismiss() })
        }
        dentro.addView(testa)
        corpo(dentro, d)
        val scorre = ScrollView(ctx).apply { addView(dentro) }
        val fondo = FrameLayout(ctx).apply {
            // un tocco sopra il foglio lo chiude
            setOnClickListener { d.dismiss() }
            addView(LinearLayout(ctx).apply {
                isClickable = true
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(ctx, R.color.carta))
                    cornerRadii = floatArrayOf(20 * dp, 20 * dp, 20 * dp, 20 * dp, 0f, 0f, 0f, 0f)
                }
                addView(scorre, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        d.setContentView(fondo)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x66000000))
        d.show()
    }

    private fun sezione(ctx: Context, t: String) = TextView(ctx).apply {
        val dp = ctx.resources.displayMetrics.density
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.06f
        setTextColor(ContextCompat.getColor(ctx, R.color.testo_piano))
        setPadding(0, (12 * dp).toInt(), 0, (6 * dp).toInt())
    }

    /** Righe di tondi colorati, 5 per riga, 52 dp l'uno: quello [attuale] con l'anello rosa. */
    private fun tondi(ctx: Context, dentro: LinearLayout, colori: List<Int>, attuale: Int, extra: View? = null, scegli: (Int) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val tutti: List<View> = colori.map { c ->
            FrameLayout(ctx).apply {
                contentDescription = nomeColore(c)
                setOnClickListener { scegli(c) }
                if (c == attuale) background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setStroke((2.5f * dp).toInt(), ContextCompat.getColor(ctx, R.color.accento))
                }
                addView(View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke((1 * dp).toInt(), 0x33FFFFFF) }
                }, FrameLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt(), Gravity.CENTER))
            }
        } + listOfNotNull(extra)
        tutti.chunked(5).forEach { fila ->
            dentro.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                fila.forEach { addView(it, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() }) }
            })
        }
    }

    fun colore(ctx: Context, titolo: String, attuale: Int, sullaPagina: List<Int>, scegli: (Int) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        foglio(ctx, titolo, " · " + nomeColore(attuale)) { dentro, d ->
            fun via(c: Int) { d.dismiss(); scegli(c) }
            if (sullaPagina.isNotEmpty()) {
                dentro.addView(sezione(ctx, "On this page"))
                tondi(ctx, dentro, sullaPagina.distinct().take(10), attuale) { via(it) }
            }
            dentro.addView(sezione(ctx, "Notebook"))
            val piu = TextView(ctx).apply {
                text = "+"; textSize = 22f; gravity = Gravity.CENTER; contentDescription = "Another colour"
                setTextColor(ContextCompat.getColor(ctx, R.color.testo))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((1.5f * dp).toInt(), ContextCompat.getColor(ctx, R.color.testo_piano)) }
                setOnClickListener { d.dismiss(); Ui.sceltaColore(ctx, attuale) { scegli(it) } }
            }
            tondi(ctx, dentro, QUADERNO.map { it.first }, attuale, piu) { via(it) }
        }
    }

    fun font(ctx: Context, attuale: String, sullaPagina: List<String>, scegli: (String) -> Unit) {
        val dp = ctx.resources.displayMetrics.density
        val nome = Caratteri.TUTTI.firstOrNull { it.first == attuale }?.second ?: attuale
        foglio(ctx, "Font", " · $nome") { dentro, d ->
            fun riga(id: String) = TextView(ctx).apply {
                text = Caratteri.TUTTI.firstOrNull { it.first == id }?.second ?: id
                typeface = Caratteri.di(ctx, id); textSize = 20f; gravity = Gravity.CENTER_VERTICAL
                setTextColor(ContextCompat.getColor(ctx, R.color.testo)); setSingleLine()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(ContextCompat.getColor(ctx, if (id == attuale) R.color.riga else R.color.carta))
                    if (id == attuale) setStroke((2 * dp).toInt(), ContextCompat.getColor(ctx, R.color.accento))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply { bottomMargin = (4 * dp).toInt() }
                setOnClickListener { d.dismiss(); scegli(id) }
            }
            val pagina = sullaPagina.distinct().filter { id -> Caratteri.TUTTI.any { it.first == id } }
            if (pagina.isNotEmpty()) { dentro.addView(sezione(ctx, "On this page")); pagina.forEach { dentro.addView(riga(it)) } }
            dentro.addView(sezione(ctx, "For text"))
            PER_TESTO.forEach { dentro.addView(riga(it)) }
            dentro.addView(sezione(ctx, "For titles"))
            Caratteri.TUTTI.map { it.first }.filter { it !in PER_TESTO }.forEach { dentro.addView(riga(it)) }
        }
    }
}
