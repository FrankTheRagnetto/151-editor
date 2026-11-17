package com.the151universe.art

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * «Clean noise».
 * Il rumore si impara dai pezzi più quieti della registrazione (le pause); poi in ogni fotogramma ogni
 * frequenza ha un guadagno di Wiener col rapporto segnale/rumore «decision-directed» (Ephraim-Malah):
 * liscio nel tempo, quindi niente «acqua» metallica. Mai più di −20 dB ([PAVIMENTO]): la voce resta
 * naturale. Parametri scelti su prove numpy (15/11): pause −19 dB, tono −0,1 dB, voce finta −0,3 dB.
 * Solo conti: le prove girano sul PC.
 */
object Rumore {
    const val N = 1024
    const val H = N / 4
    const val BIN = N / 2 + 1
    /** il guadagno più basso: −20 dB */
    const val PAVIMENTO = 0.1f
    /** quanto conta il fotogramma prima nel rapporto segnale/rumore (0,98 classico) */
    const val MEMORIA = 0.98f

    /** Hann periodica: con H = N/4 analisi × sintesi somma a 1,5 costante. */
    val FINESTRA = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / N)).toFloat() }

    /** FFT radice 2 sul posto. [inversa]: senza la divisione per n (la fa chi chiama). */
    fun fft(re: FloatArray, im: FloatArray, inversa: Boolean = false) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inversa) 1 else -1)
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
                    val t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** La potenza per frequenza di un fotogramma di N campioni (con la finestra). */
    fun potenza(x: FloatArray, da: Int = 0): FloatArray {
        val re = FloatArray(N) { if (da + it < x.size) x[da + it] * FINESTRA[it] else 0f }
        val im = FloatArray(N)
        fft(re, im)
        return FloatArray(BIN) { re[it] * re[it] + im[it] * im[it] }
    }

    /** Da qui in su (≈170 Hz a 44,1 kHz) si cercano le pause: i bassi del rumore vanno e vengono. */
    const val DA_BIN = 4

    /** L'energia sopra [DA_BIN] di un blocco di N campioni. */
    fun energiaSopra(p: FloatArray): Float { var e = 0f; for (k in DA_BIN until BIN) e += p[k]; return e }

    /**
     * L'energia dei blocchi di N campioni (senza sovrapposizione), per trovare le pause. Contata sopra
     * i bassi: con l'energia intera (15/11) si sceglievano i momenti in cui il rombo era basso, il rumore
     * risultava sottostimato e sotto i 100 Hz restava quasi tutto (pausa −0,7 dB invece di −15).
     */
    fun energie(x: FloatArray): FloatArray =
        FloatArray(maxOf(1, x.size / N)) { b -> energiaSopra(potenza(x, b * N)) }

    /** I blocchi più quieti: il 10% più basso, almeno 3 (se ci sono). */
    fun quieti(energie: FloatArray): List<Int> {
        val quanti = maxOf(3, energie.size / 10).coerceAtMost(energie.size)
        return energie.indices.sortedBy { energie[it] }.take(quanti)
    }

    /**
     * Ci sono pause vere? I blocchi quieti devono stare almeno 6 dB sotto i blocchi forti (il 90° percentile:
     * la mediana sbaglia quando la registrazione è quasi tutta pausa).
     * Senza pause (musica continua) il «rumore» imparato sarebbe il suono stesso e verrebbe tolto tutto:
     * telefono di prova 15/11, 3 minuti di rumore + tono → tutto −20 dB. Allora non si tocca niente.
     */
    fun pauseChiare(energie: FloatArray, quieti: List<Int>): Boolean {
        if (energie.size < 4 || quieti.isEmpty()) return false
        val forte = energie.sorted()[energie.size * 9 / 10].toDouble()
        val quiete = quieti.sumOf { energie[it].toDouble() } / quieti.size
        return forte >= 4 * quiete && forte > 0
    }

    /** Il rumore: la potenza media dei blocchi quieti. */
    fun profilo(x: FloatArray): FloatArray {
        val q = quieti(energie(x))
        val p = FloatArray(BIN)
        for (b in q) { val s = potenza(x, b * N); for (k in 0 until BIN) p[k] += s[k] / q.size }
        return p
    }

    /** Tutto in una volta (prove e suoni brevi): [Cancello] su [x] con il suo stesso profilo. */
    fun pulisci(x: FloatArray, profilo: FloatArray = profilo(x)): FloatArray {
        val c = Cancello(profilo)
        return c.tutto(x)
    }
}

/**
 * La pulizia a pezzi (un canale): si spingono campioni, escono campioni puliti, in ritardo di N − H.
 * [tutto] fa ingresso + coda + taglio del ritardo, così l'uscita è lunga e allineata come l'ingresso.
 */
class Cancello(private val rumore: FloatArray) {
    private val n = Rumore.N
    private val h = Rumore.H
    private val ingresso = FloatArray(n)
    private val accumulo = FloatArray(n)
    private var pieni = 0
    private val re = FloatArray(n)
    private val im = FloatArray(n)
    /** del fotogramma prima: il guadagno e la potenza */
    private val gPrima = FloatArray(Rumore.BIN) { 1f }
    private val pPrima = FloatArray(Rumore.BIN)

    /** Spinge [x]; ritorna i campioni pronti (multipli di H). */
    fun spingi(x: FloatArray, quanti: Int = x.size): FloatArray {
        val out = FloatArray(((pieni + quanti) / h) * h)
        var o = 0
        for (i in 0 until quanti) {
            ingresso[n - h + pieni] = x[i]
            pieni++
            if (pieni == h) {
                fotogramma()
                System.arraycopy(accumulo, 0, out, o, h); o += h
                System.arraycopy(accumulo, h, accumulo, 0, n - h); java.util.Arrays.fill(accumulo, n - h, n, 0f)
                System.arraycopy(ingresso, h, ingresso, 0, n - h)
                pieni = 0
            }
        }
        return out
    }

    private fun fotogramma() {
        val w = Rumore.FINESTRA
        for (k in 0 until n) { re[k] = ingresso[k] * w[k]; im[k] = 0f }
        Rumore.fft(re, im)
        val a = Rumore.MEMORIA
        for (k in 0 until Rumore.BIN) {
            val p = re[k] * re[k] + im[k] * im[k]
            val r = maxOf(rumore[k], 1e-20f)
            val prio = a * gPrima[k] * gPrima[k] * pPrima[k] / r + (1 - a) * maxOf(p / r - 1f, 0f)
            gPrima[k] = maxOf(prio / (1f + prio), Rumore.PAVIMENTO)
            pPrima[k] = p
        }
        for (k in 0 until Rumore.BIN) {
            re[k] *= gPrima[k]; im[k] *= gPrima[k]
            if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
        }
        Rumore.fft(re, im, inversa = true)
        val s = 1f / (n * 1.5f)
        for (k in 0 until n) accumulo[k] += re[k] * w[k] * s
    }

    /** Ingresso intero → uscita della stessa lunghezza, allineata. */
    fun tutto(x: FloatArray): FloatArray {
        // l'uscita j è l'ingresso j − (N − H): la coda di zeri fa uscire gli ultimi, il ritardo si taglia
        val tutto = spingi(x) + spingi(FloatArray(n))
        return tutto.copyOfRange(n - h, n - h + x.size)
    }
}
