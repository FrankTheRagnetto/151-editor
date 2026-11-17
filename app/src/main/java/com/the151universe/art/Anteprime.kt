package com.the151universe.art

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

/** Le miniature dei design per la casa: stessa ricetta del file, foto lette piccole. */
object Anteprime {
    fun grafica(ctx: Context, g: Grafica, lato: Int): Bitmap? = try {
        val foto = HashMap<String, Bitmap?>()
        val larga = if (g.alto > Grafica.LARGO) (lato * Grafica.LARGO / g.alto).toInt().coerceAtLeast(1) else lato
        GraficaDisegno.immagine(ctx, GraficaDisegno.misura(ctx, g), larga, { u ->
            foto.getOrPut(u) { try { SalvaFoto.anteprima(ctx, Uri.parse(u), lato) } catch (_: Exception) { null } }
        }, editor = false)
    } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
}
