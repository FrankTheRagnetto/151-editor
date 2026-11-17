package com.the151universe.art

import kotlin.math.abs

/** Quando un dito sul vuoto della pagina intera è una sfogliata (logica pura, misure in pixel; [dp] = pixel per dp). */
object Sfoglia {
    /** +1 = pagina dopo (il dito va a sinistra), -1 = pagina prima, 0 = niente: corto, storto o lento. */
    fun verso(dx: Float, dy: Float, ms: Long, dp: Float): Int {
        val lungo = abs(dx) >= 60f * dp || (abs(dx) >= 30f * dp && ms in 1..250)
        if (!lungo || abs(dx) < 1.5f * abs(dy) || ms > 1200) return 0
        return if (dx < 0) 1 else -1
    }
}
