package com.the151universe.art

import android.content.Context
import android.graphics.Typeface

/**
 * I font dei testi: due del telefono + 23 liberi (OFL / Apache) dentro l'app,
 * in assets/font, con le loro licenze in assets/font/licenze. Niente rete.
 */
object Caratteri {
    const val PRIMO = "sans"

    /** id, nome sul bottone, file in assets/font (null = font del telefono). */
    val TUTTI: List<Triple<String, String, String?>> = listOf(
        Triple("sans", "Sans", null),
        Triple("serif", "Serif", null),
        // le misure di Arial (il quaderno di InDesign), quattro file veri: grassetto e corsivo non sono finti
        Triple("liberation", "Liberation Sans", "LiberationSans-Regular.ttf"),
        Triple("anton", "Anton", "Anton-Regular.ttf"),
        Triple("bebas", "Bebas Neue", "BebasNeue-Regular.ttf"),
        Triple("archivo", "Archivo Black", "ArchivoBlack-Regular.ttf"),
        Triple("russo", "Russo One", "RussoOne-Regular.ttf"),
        Triple("righteous", "Righteous", "Righteous-Regular.ttf"),
        Triple("lilita", "Lilita One", "LilitaOne-Regular.ttf"),
        Triple("bangers", "Bangers", "Bangers-Regular.ttf"),
        Triple("shrikhand", "Shrikhand", "Shrikhand-Regular.ttf"),
        Triple("abril", "Abril Fatface", "AbrilFatface-Regular.ttf"),
        Triple("dmserif", "DM Serif", "DMSerifDisplay-Regular.ttf"),
        Triple("playfair", "Playfair SC", "PlayfairDisplaySC-Bold.ttf"),
        Triple("lobster", "Lobster", "Lobster-Regular.ttf"),
        Triple("pacifico", "Pacifico", "Pacifico-Regular.ttf"),
        Triple("satisfy", "Satisfy", "Satisfy-Regular.ttf"),
        Triple("greatvibes", "Great Vibes", "GreatVibes-Regular.ttf"),
        Triple("marker", "Marker", "PermanentMarker-Regular.ttf"),
        Triple("caveat", "Caveat Brush", "CaveatBrush-Regular.ttf"),
        Triple("amatic", "Amatic", "AmaticSC-Bold.ttf"),
        Triple("typewriter", "Typewriter", "SpecialElite-Regular.ttf"),
        Triple("spacemono", "Space Mono", "SpaceMono-Bold.ttf"),
        Triple("rubikmono", "Rubik Mono", "RubikMonoOne-Regular.ttf"),
        Triple("monoton", "Monoton", "Monoton-Regular.ttf"),
        Triple("pixel", "Pixel", "PressStart2P-Regular.ttf"),
    )

    private val cache = HashMap<String, Typeface>()

    /** I font che hanno i loro file per grassetto, corsivo e tutti e due (gli altri si fanno dal telefono). */
    private val FAMIGLIE = mapOf(
        "liberation" to listOf("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf", "LiberationSans-BoldItalic.ttf"),
    )

    /**
     * Il font [id] con [stile] ([Ricco.GRASSETTO] / [Ricco.CORSIVO]). Con i suoi file veri se li ha,
     * se no lo fa il telefono (più spesso, inclinato).
     */
    @Synchronized
    fun variante(ctx: Context, id: String, stile: Int): Typeface {
        val b = stile and Ricco.GRASSETTO != 0; val i = stile and Ricco.CORSIVO != 0
        if (!b && !i) return di(ctx, id)
        val k = "$id#${if (b) "b" else ""}${if (i) "i" else ""}"
        return cache.getOrPut(k) {
            val f = FAMIGLIE[id]?.get((if (b) 1 else 0) + (if (i) 2 else 0))
            val vero = f?.let { try { Typeface.createFromAsset(ctx.assets, "font/$it") } catch (_: Exception) { null } }
            vero ?: Typeface.create(di(ctx, id), (if (b) Typeface.BOLD else 0) or (if (i) Typeface.ITALIC else 0))
        }
    }

    /** Il font [id]; uno sconosciuto o un file illeggibile = il font del telefono in grassetto. */
    @Synchronized
    fun di(ctx: Context, id: String): Typeface = cache.getOrPut(id) {
        val voce = TUTTI.firstOrNull { it.first == id }
        when {
            voce == null || voce.first == "sans" -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            voce.first == "serif" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            else -> try { Typeface.createFromAsset(ctx.assets, "font/" + voce.third) }
                catch (_: Exception) { Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        }
    }
}
