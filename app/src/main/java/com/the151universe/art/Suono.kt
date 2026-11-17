package com.the151universe.art

/**
 * Il progetto Audio: solo suoni, su più corsie una sopra
 * l'altra; nella stessa corsia i pezzi non si sovrappongono, fra corsie diverse sì (musica sotto
 * la voce). Ogni pezzo è una [Musica] con la sua [Musica.corsia]: taglio, bordi, divisione e
 * dissolvenze sono gli stessi del video ([Progetto]), fatti dentro la corsia del pezzo.
 * Logica pura: si prova sul PC (SuonoTest).
 */
data class ProgettoSuono(val nome: String, val pezzi: List<Musica> = emptyList(), val corsie: List<Corsia> = emptyList(),
    /** i segnalini sul tempo: ms del progetto, in ordine; i suoni ci si attaccano */
    val segni: List<Long> = emptyList())

/** Le regolazioni di una corsia: il suo volume (sopra quello dei pezzi), muta, solo. */
data class Corsia(val volume: Int = 100, val muto: Boolean = false, val solo: Boolean = false)

object Suono {
    const val CORSIE_MAX = 6
    /** Le dissolvenze si scelgono fra 0 (niente) e 5 s. */
    const val FADE_MAX = 5000L
    /** «Even out»: il livello a cui arriva ogni suono (picchi alti tipici, 0..1). */
    const val LIVELLO_PARI = 0.5f
    /**
     * I colori che si scelgono per un suono: scuri abbastanza perché nome e onda bianchi si
     * leggano (≥4,5:1), diversi fra loro. Indice 1..size; 0 = il blocco di sempre.
     */
    val COLORI = intArrayOf(0xFF8E2C4F.toInt(), 0xFF8A4B12.toInt(), 0xFF6B5A00.toInt(), 0xFF2F6B2A.toInt(),
        0xFF0F6B6B.toInt(), 0xFF2A4F9A.toInt(), 0xFF5B3A9A.toInt(), 0xFF5E5E5E.toInt())
    val NOMI_COLORI = arrayOf("Berry", "Rust", "Olive", "Green", "Teal", "Blue", "Violet", "Grey")
    fun colore(m: Musica): Int? = COLORI.getOrNull(m.colore - 1)

    fun corsia(s: ProgettoSuono, c: Int): Corsia = s.corsie.getOrNull(c) ?: Corsia()

    /** Cambia le regolazioni della corsia [c] (l'elenco si allunga se serve). */
    fun conCorsia(s: ProgettoSuono, c: Int, f: (Corsia) -> Corsia): ProgettoSuono {
        val l = s.corsie.toMutableList()
        while (l.size <= c) l += Corsia()
        l[c] = f(l[c])
        return s.copy(corsie = l)
    }

    /** Quanto si sente la corsia [c]: muta, o un'altra è in «solo» e lei no → 0. */
    fun guadagnoCorsia(s: ProgettoSuono, c: Int): Float {
        val k = corsia(s, c)
        if (k.muto || (s.corsie.any { it.solo } && !k.solo)) return 0f
        return k.volume / 100f
    }

    /** Il guadagno del pezzo [m] a [t] ms dal suo inizio: corsia × pezzo × dissolvenze. Anteprima ed export. */
    fun guadagno(s: ProgettoSuono, m: Musica, t: Long): Float = guadagnoCorsia(s, m.corsia) * Progetto.guadagnoMusica(m, t)

    /** La dissolvenza scelta col cursore: 0 = niente, se no da 0,1 a 5 s. */
    fun conEntra(m: Musica, ms: Long): Musica = if (ms < 100) m.copy(entra = false) else m.copy(entra = true, durEntra = ms.coerceAtMost(FADE_MAX))
    fun conEsce(m: Musica, ms: Long): Musica = if (ms < 100) m.copy(esce = false) else m.copy(esce = true, durEsce = ms.coerceAtMost(FADE_MAX))

    /**
     * Quanto è forte un suono nel pezzo usato: il 90° percentile dei picchi dell'onda ([Onde],
     * uno ogni 20 ms). Così un colpo isolato non conta e una voce bassa si riconosce. null = muto.
     */
    fun livello(onda: FloatArray, daMs: Long, aMs: Long): Float? {
        if (onda.isEmpty()) return null
        val a = (daMs / Onde.PASSO_MS).toInt().coerceIn(0, onda.size - 1)
        val b = (aMs / Onde.PASSO_MS).toInt().coerceIn(a, onda.size - 1)
        val p = onda.copyOfRange(a, b + 1).sorted()
        val v = p[((p.size - 1) * 0.9f).toInt()]
        return if (v < 0.005f) null else v
    }

    /** Il volume che porta un suono di [livello] al livello comune (fra 10% e 400%). */
    fun volumePari(livello: Float): Int = Math.round(100f * LIVELLO_PARI / livello).coerceIn(10, Progetto.VOLUME_MAX)

    fun totaleMs(p: List<Musica>): Long = p.maxOfOrNull { it.fineGlobaleMs } ?: 0L

    /** Le corsie da mostrare: quelle usate più una vuota sotto (per il prossimo), al massimo [CORSIE_MAX]. */
    fun corsieVisibili(p: List<Musica>): Int = ((p.maxOfOrNull { it.corsia } ?: -1) + 2).coerceIn(1, CORSIE_MAX)

    /**
     * L'altezza di una corsia: lo [spazio] diviso per le corsie visibili (almeno 3
     * di posto), mai sotto [minimo] (48 dp, un pollice): oltre, le corsie scorrono su e giù.
     */
    fun altezzaCorsia(spazio: Float, corsie: Int, minimo: Float): Float = maxOf(spazio / maxOf(corsie, 3), minimo)

    /** Quanto le corsie possono scorrere in su: 0 se ci stanno tutte. */
    fun scorrimentoMax(spazio: Float, corsie: Int, minimo: Float): Float = maxOf(0f, corsie * altezzaCorsia(spazio, corsie, minimo) - spazio)

    /** Una scritta del righello da [a] a [b] (px) si salta se tocca una maniglia del Loop (centri [maniglie], larghe 2×[mezza]). */
    fun scrittaLibera(a: Float, b: Float, maniglie: FloatArray, mezza: Float): Boolean = maniglie.none { b > it - mezza && a < it + mezza }

    fun corsia(p: List<Musica>, c: Int): List<Musica> = p.filter { it.corsia == c }

    /** L'ordine fisso dell'elenco: corsia, poi tempo. */
    private fun ordina(p: List<Musica>) = p.sortedWith(compareBy({ it.corsia }, { it.posMs }))

    private fun conIndice(p: List<Musica>, n: Musica): Pair<List<Musica>, Int> {
        val l = ordina(p)
        return l to l.indexOfFirst { it === n }
    }

    /**
     * Un suono nuovo (o una copia) che parte da [t]: va nella prima corsia dove ci sta intero,
     * provando prima [preferita]; se non ci sta intero da nessuna parte, nella prima dove c'è
     * posto, accorciato. null = nessun posto. Ritorna l'elenco e l'indice del pezzo nuovo.
     */
    fun metti(p: List<Musica>, m: Musica, t: Long, preferita: Int = -1): Pair<List<Musica>, Int>? {
        val pos = t.coerceAtLeast(0)
        val ordine = ((if (preferita in 0 until CORSIE_MAX) listOf(preferita) else emptyList()) + (0 until CORSIE_MAX)).distinct()
        var accorciato: Musica? = null
        for (c in ordine) {
            val fine = Progetto.postoDa(corsia(p, c).map { it.posMs to it.fineGlobaleMs }, pos) ?: continue
            // la lunghezza nel progetto (la velocità conta); nel file si taglia quanto serve
            val lun = minOf(m.suonaMs, fine - pos)
            if (lun < Progetto.MINIMO_MS) continue
            val nuovo = if (lun == m.suonaMs) m.copy(posMs = pos, corsia = c)
                else m.copy(posMs = pos, fineMs = minOf(m.fineMs, m.inizioMs + Math.round(lun * m.velocita.toDouble())), corsia = c)
            if (lun == m.suonaMs) return conIndice(p + nuovo, nuovo)
            if (accorciato == null) accorciato = nuovo
        }
        return accorciato?.let { conIndice(p + it, it) }
    }

    /**
     * Un'operazione di [Progetto] sulla corsia del pezzo [i]; ritorna l'elenco e l'indice del pezzo
     * da tenere scelto (l'ultimo nuovo: dopo una divisione, la seconda metà). null = rifiutata.
     */
    private fun inCorsia(p: List<Musica>, i: Int, f: (List<Musica>, Int) -> List<Musica>?): Pair<List<Musica>, Int>? {
        if (i !in p.indices) return null
        val m = p[i]
        val l = corsia(p, m.corsia)
        val nl = f(l, l.indexOfFirst { it === m }) ?: return null
        val scelto = nl.lastOrNull { x -> l.none { it === x } } ?: m
        return conIndice(p.filter { it.corsia != m.corsia } + nl, scelto)
    }

    /**
     * Sposta il pezzo [i] a cominciare da [pos] nella corsia [c]. In un'altra corsia va solo se lì
     * c'è posto; se no resta nella sua e si sposta solo nel tempo, senza sovrapporsi ai vicini.
     */
    fun sposta(p: List<Musica>, i: Int, pos: Long, c: Int): Pair<List<Musica>, Int> {
        if (i !in p.indices) return p to i
        val m = p[i]
        val dest = c.coerceIn(0, CORSIE_MAX - 1)
        if (dest != m.corsia) {
            val a = pos.coerceAtLeast(0); val b = a + m.suonaMs
            if (corsia(p, dest).none { a < it.fineGlobaleMs && b > it.posMs }) {
                val n = m.copy(posMs = a, corsia = dest)
                return conIndice(p.filterIndexed { k, _ -> k != i } + n, n)
            }
        }
        return inCorsia(p, i) { l, k -> Progetto.spostaMusica(l, k, pos) } ?: (p to i)
    }

    /** Un bordo del pezzo [i] mosso di [delta] ms (il sinistro taglia l'inizio, il destro la fine). */
    fun bordo(p: List<Musica>, i: Int, sinistro: Boolean, delta: Long): Pair<List<Musica>, Int> =
        inCorsia(p, i) { l, k -> Progetto.bordoMusica(l, k, sinistro, delta) } ?: (p to i)

    /** Divide il pezzo [i] al punto [t] del progetto; null se [t] non è dentro o un pezzo resterebbe troppo corto. */
    fun dividi(p: List<Musica>, i: Int, t: Long): Pair<List<Musica>, Int>? {
        val m = p.getOrNull(i) ?: return null
        if (t <= m.posMs || t >= m.fineGlobaleMs) return null
        return inCorsia(p, i) { l, _ -> Progetto.dividiMusica(l, t) }
    }

    /** «Remove silences»: sotto il 12% del livello del suono… */
    const val SILENZIO_FRAZ = 0.12f
    /** …per almeno 0,7 s è un silenzio; restano 0,15 s per lato, così le parole non si mangiano. */
    const val SILENZIO_MIN_MS = 700L
    const val SILENZIO_BORDO_MS = 150L

    /** I pezzi del file (ms) dove c'è suono, fra [daMs] e [aMs]: i silenzi lunghi tolti, coi bordi. */
    fun parlato(onda: FloatArray, daMs: Long, aMs: Long): List<LongRange> {
        val liv = livello(onda, daMs, aMs) ?: return emptyList()
        val soglia = maxOf(liv * SILENZIO_FRAZ, 0.004f)
        val passo = Onde.PASSO_MS
        val muti = mutableListOf<LongRange>()
        var inizio = -1L
        var t = daMs
        while (t < aMs) {
            val k = (t / passo).toInt()
            val muto = k >= onda.size || onda[k] < soglia
            if (muto && inizio < 0) inizio = t
            if (!muto && inizio >= 0) { muti += inizio until t; inizio = -1 }
            t += passo
        }
        if (inizio >= 0) muti += inizio until aMs
        // un silenzio vale solo se lungo; i bordi restano (all'inizio e alla fine del pezzo niente bordo da tenere fuori)
        val tagli = muti.filter { it.last + 1 - it.first >= SILENZIO_MIN_MS }.map { r ->
            val a = if (r.first <= daMs) daMs else r.first + SILENZIO_BORDO_MS
            val b = if (r.last + 1 >= aMs) aMs else r.last + 1 - SILENZIO_BORDO_MS
            a until b
        }.filter { it.last >= it.first }
        val fuori = mutableListOf<LongRange>()
        var da = daMs
        for (c in tagli) { if (c.first > da) fuori += da until c.first; da = c.last + 1 }
        if (da < aMs) fuori += da until aMs
        return fuori.filter { it.last + 1 - it.first >= Progetto.MINIMO_MS }
    }

    /**
     * Toglie i silenzi lunghi dal pezzo [i] e chiude i buchi: i pezzi rimasti si mettono in fila
     * dove stava lui, e quello che viene dopo nella sua corsia si sposta indietro di quanto tolto.
     * Le altre corsie non si toccano. null = niente da togliere (o tutto muto).
     */
    fun togliSilenzi(p: List<Musica>, i: Int, onda: FloatArray): Pair<List<Musica>, Int>? {
        val m = p.getOrNull(i) ?: return null
        val parti = parlato(onda, m.inizioMs, m.fineMs)
        if (parti.isEmpty()) return null
        if (parti.size == 1 && parti[0].first == m.inizioMs && parti[0].last + 1 == m.fineMs) return null
        var pos = m.posMs
        val nuovi = parti.mapIndexed { k, r ->
            val n = m.copy(inizioMs = r.first, fineMs = r.last + 1, posMs = pos,
                entra = m.entra && k == 0, esce = m.esce && k == parti.size - 1)
            pos += n.suonaMs
            n
        }
        val tolto = m.fineGlobaleMs - pos
        val resto = p.filterIndexed { k, _ -> k != i }.map {
            if (it.corsia == m.corsia && it.posMs >= m.fineGlobaleMs) it.copy(posMs = it.posMs - tolto) else it
        }
        val l = ordina(resto + nuovi)
        return l to l.indexOfFirst { it === nuovi.last() }
    }

    /** Il buco più grande fra due suoni perché il passaggio morbido li consideri «uno dopo l'altro». */
    const val PASSAGGIO_BUCO_MS = 500L

    /**
     * Passaggio morbido: il pezzo [i] sfuma in uscita
     * mentre il suono subito dopo di lui (stessa corsia, buco ≤ [PASSAGGIO_BUCO_MS]) entra sfumando.
     * Per sovrapporsi il secondo scende (o sale) nella corsia libera più vicina e parte [ms] prima della
     * fine del primo. [ms] si accorcia a metà del più corto. null = nessun suono dopo, o nessun posto.
     * Ritorna l'elenco e l'indice del secondo (resta scelto lui: si vede dove è andato).
     */
    fun passaggio(p: List<Musica>, i: Int, ms: Long): Pair<List<Musica>, Int>? {
        val m = p.getOrNull(i) ?: return null
        val j = p.indices.filter { k -> k != i && p[k].corsia == m.corsia && p[k].posMs >= m.fineGlobaleMs - 1 &&
            p[k].posMs - m.fineGlobaleMs <= PASSAGGIO_BUCO_MS }.minByOrNull { p[it].posMs } ?: return null
        val n = p[j]
        val d = minOf(ms, m.suonaMs / 2, n.suonaMs / 2)
        if (d < 100) return null
        val pos = m.fineGlobaleMs - d
        val vicine = (1 until CORSIE_MAX).flatMap { listOf(m.corsia + it, m.corsia - it) }.filter { it in 0 until CORSIE_MAX }
        val c = vicine.firstOrNull { c -> corsia(p, c).none { pos < it.fineGlobaleMs && pos + n.suonaMs > it.posMs } } ?: return null
        val primo = conEsce(m, d)
        val secondo = conEntra(n, d).copy(posMs = pos, corsia = c)
        return conIndice(p.filterIndexed { k, _ -> k != i && k != j } + primo + secondo, secondo)
    }

    /** Una copia del pezzo [i] subito dopo di lui (nella sua corsia, se c'è posto). */
    fun duplica(p: List<Musica>, i: Int): Pair<List<Musica>, Int>? {
        val m = p.getOrNull(i) ?: return null
        return metti(p, m, m.fineGlobaleMs, m.corsia)
    }

    /**
     * La velocità [v] del pezzo [i]. Rallentato si allunga
     * solo fin dove c'è posto nella sua corsia: la velocità si ferma lì. Le dissolvenze restano
     * lunghe uguali nel progetto.
     */
    fun conVelocita(p: List<Musica>, i: Int, v: Float): List<Musica> {
        val m = p.getOrNull(i) ?: return p
        var nv = Progetto.vicina(v)
        val dopo = corsia(p, m.corsia).filter { it !== m && it.posMs > m.posMs }.minOfOrNull { it.posMs }
        if (dopo != null && Math.round(m.lunghezzaMs / nv.toDouble()) > dopo - m.posMs) {
            // la più lenta che ci sta, arrotondata in su al passo delle velocità
            val minima = m.lunghezzaMs.toFloat() / (dopo - m.posMs).coerceAtLeast(1)
            nv = Progetto.vicina(minima)
            while (Math.round(m.lunghezzaMs / nv.toDouble()) > dopo - m.posMs && nv < Progetto.VELOCITA_MAX) nv = Progetto.passoVelocita(nv, 1)
        }
        return p.toMutableList().also { it[i] = m.copy(velocita = nv) }
    }

    /** Il pezzo della corsia [c] che suona al punto [t], o null. */
    fun a(p: List<Musica>, c: Int, t: Long): Musica? = p.firstOrNull { it.corsia == c && t >= it.posMs && t < it.fineGlobaleMs }

    /**
     * Registrare la voce ascoltando il progetto: si suona solo in cuffia
     * (filo, USB, Bluetooth), mai dall'altoparlante, che finirebbe nella registrazione.
     * [tipi] = AudioDeviceInfo.getType() delle uscite.
     */
    fun sonoCuffie(tipi: IntArray): Boolean = tipi.any { it in intArrayOf(3, 4, 8, 22, 26) }
    // 3 WIRED_HEADSET · 4 WIRED_HEADPHONES · 8 BLUETOOTH_A2DP · 22 USB_HEADSET · 26 BLE_HEADSET (2 = altoparlante: no)

    /** Un segnalino messo o tolto a [t]: se ce n'è uno a meno di [SEGNO_VICINO_MS] si toglie, se no si mette. */
    const val SEGNO_VICINO_MS = 250L
    fun segna(segni: List<Long>, t: Long): List<Long> {
        val vicino = segni.minByOrNull { kotlin.math.abs(it - t) }
        return if (vicino != null && kotlin.math.abs(vicino - t) <= SEGNO_VICINO_MS) segni - vicino
            else (segni + t.coerceAtLeast(0)).sorted()
    }

    /** I bordi a cui si attacca un pezzo trascinato: l'inizio, i bordi di tutti gli altri e i segnalini. */
    fun bordi(p: List<Musica>, escluso: Int, segni: List<Long> = emptyList()): LongArray = buildList {
        add(0L)
        addAll(segni)
        p.forEachIndexed { k, m -> if (k != escluso) { add(m.posMs); add(m.fineGlobaleMs) } }
    }.toLongArray()

    /**
     * Il nome del file salvato: quello del progetto davanti alla data dell'export
     * («Lesson 3 2026-11-04 09.14.07.m4a»); senza nome resta quello della data. Niente caratteri
     * che un telefono o un PC rifiutano.
     */
    fun nomeFile(progetto: String, conData: String): String {
        val pulito = progetto.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
        if (pulito.isEmpty()) return conData
        return pulito + " " + conData.removePrefix("151 Editor ")
    }

    // --- l'export: una fila per corsia, silenzi e pezzi, tutte lunghe uguali ---

    sealed class Passo {
        data class Silenzio(val ms: Long) : Passo()
        data class Pezzo(val m: Musica) : Passo()
    }

    /** Le file dell'export, una per corsia usata, dalla prima che comincia: ognuna arriva fino alla fine del progetto. */
    fun file(p: List<Musica>): List<List<Passo>> {
        val tot = totaleMs(p)
        return (0 until CORSIE_MAX).map { c -> corsia(p, c).sortedBy { it.posMs } }.filter { it.isNotEmpty() }
            .sortedBy { it.first().posMs }
            .map { l ->
                val out = mutableListOf<Passo>()
                var dove = 0L
                for (m in l) {
                    if (m.posMs > dove) out += Passo.Silenzio(m.posMs - dove)
                    out += Passo.Pezzo(m)
                    dove = m.fineGlobaleMs
                }
                if (tot > dove) out += Passo.Silenzio(tot - dove)
                out
            }
    }

    // --- la bozza su disco: «151suono 2<TAB>nome», poi una riga M per pezzo (come il video, + corsia, durate
    // delle dissolvenze e velocità) e una riga L per corsia regolata. Si legge anche il formato 1 (M senza durate). ---

    private fun pulito(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun b(x: Boolean) = if (x) "1" else "0"

    fun scrivi(s: ProgettoSuono): String = buildString {
        append("151suono 2\t").append(pulito(s.nome)).append('\n')
        for (m in s.pezzi) append(listOf("M", Foto.chiudi(m.uri), pulito(m.nome), m.durataMs, m.inizioMs, m.fineMs, m.posMs,
            m.volume, b(m.entra), b(m.esce), m.corsia, m.durEntra, m.durEsce, m.velocita, m.colore).joinToString("\t")).append('\n')
        s.corsie.forEachIndexed { c, k -> if (k != Corsia()) append(listOf("L", c, k.volume, b(k.muto), b(k.solo)).joinToString("\t")).append('\n') }
        for (t in s.segni) append("K\t").append(t).append('\n')
    }

    /** Legge cio' che [scrivi] ha scritto; righe rotte si saltano, mai un crash. null = non e' un progetto Audio. */
    fun leggi(testo: String): ProgettoSuono? {
        val righe = testo.split('\n')
        val testa = righe.firstOrNull()?.trimEnd('\r') ?: return null
        val nome = when {
            testa.startsWith("151suono 1\t") -> testa.removePrefix("151suono 1\t")
            testa.startsWith("151suono 2\t") -> testa.removePrefix("151suono 2\t")
            testa.trim() == "151suono 1" || testa.trim() == "151suono 2" -> ""
            else -> return null
        }
        val pezzi = mutableListOf<Musica>()
        val corsie = MutableList(CORSIE_MAX) { Corsia() }
        val segni = mutableListOf<Long>()
        for (r in righe.drop(1)) {
            val p = r.split('\t')
            if (p[0] == "K" && p.size == 2) { p[1].trim().toLongOrNull()?.let { if (it >= 0) segni += it }; continue }
            if (p[0] == "L" && p.size == 5) {
                try {
                    val c = p[1].toInt()
                    if (c in 0 until CORSIE_MAX) corsie[c] = Corsia(p[2].toInt().coerceIn(0, Progetto.VOLUME_MAX), p[3] == "1", p[4] == "1")
                } catch (_: Exception) {}
                continue
            }
            if (p[0] != "M" || (p.size != 11 && p.size != 13 && p.size != 14 && p.size != 15) || p[1].isBlank()) continue
            try {
                val dur = p[3].toLong()
                val inizio = p[4].toLong().coerceIn(0, dur)
                val fine = p[5].toLong().coerceIn(inizio, dur)
                if (fine - inizio <= 0) continue
                pezzi += Musica(Foto.apri(p[1]), p[2], dur, inizio, fine, p[6].toLong().coerceAtLeast(0),
                    p[7].toInt().coerceIn(0, Progetto.VOLUME_MAX), p[8] == "1", p[9] == "1", p[10].toInt().coerceIn(0, CORSIE_MAX - 1),
                    if (p.size >= 13) p[11].toLong().coerceIn(100, FADE_MAX) else DISSOLVENZA_STD,
                    if (p.size >= 13) p[12].toLong().coerceIn(100, FADE_MAX) else DISSOLVENZA_STD,
                    if (p.size >= 14) Progetto.vicina(p[13].toFloat()) else 1f,
                    if (p.size == 15) p[14].toInt().let { if (it in 1..COLORI.size) it else 0 } else 0)
            } catch (_: Exception) { /* riga rotta: si salta */ }
        }
        return ProgettoSuono(nome, ordina(pezzi), corsie.dropLastWhile { it == Corsia() }, segni.distinct().sorted())
    }
}
