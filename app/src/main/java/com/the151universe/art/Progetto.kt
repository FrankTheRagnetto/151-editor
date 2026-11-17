package com.the151universe.art

/**
 * Il montaggio, senza Android: le clip in ordine, tagli, divisioni, conti, salvataggio.
 * Tutto qui dentro e' puro, cosi' si prova sul PC (app/src/test).
 */

/**
 * Una clip del montaggio. I tempi sono in millisecondi dentro il file originale.
 * [volume] in % (0..400), [velocita] (0.1..30), [entra]/[esce] = transizione all'inizio/alla fine
 * (verso il nero, immagine e suono), lunga [durEntra]/[durEsce] ms.
 */
data class Clip(
    val uri: String,
    val nome: String,
    val durataMs: Long,
    val inizioMs: Long = 0,
    val fineMs: Long = durataMs,
    val volume: Int = 100,
    val velocita: Float = 1f,
    val entra: Boolean = false,
    val esce: Boolean = false,
    val durEntra: Long = DISSOLVENZA_STD,
    val durEsce: Long = DISSOLVENZA_STD,
) {
    /** Quanto del FILE si usa. */
    val lunghezzaMs: Long get() = fineMs - inizioMs
    /** Quanto dura nel video finito (la velocita' accorcia o allunga). */
    val vistaMs: Long get() = Math.round(lunghezzaMs.coerceAtLeast(0) / velocita.toDouble())
}

/**
 * Un pezzo di musica sotto il video: [inizioMs]/[fineMs] nel file, [posMs] dove comincia nel video
 * finito.
 */
data class Musica(
    val uri: String,
    val nome: String,
    val durataMs: Long,
    val inizioMs: Long = 0,
    val fineMs: Long = durataMs,
    val posMs: Long = 0,
    val volume: Int = 100,
    val entra: Boolean = false,
    val esce: Boolean = false,
    /** la corsia nel progetto Audio (0 = in alto); nel video c'è una corsia sola e resta 0 */
    val corsia: Int = 0,
    /** quanto durano le dissolvenze (progetto Audio: si scelgono; nel video restano quelle fisse) */
    val durEntra: Long = DISSOLVENZA_STD,
    val durEsce: Long = DISSOLVENZA_STD,
    /** la velocità del suono (progetto Audio, chi lo usa 04/11); nel video resta 1× */
    val velocita: Float = 1f,
    /** il colore scelto per il blocco (progetto Audio, chi lo usa 04/11): 0 = quello di sempre, se no 1..[Suono.COLORI] */
    val colore: Int = 0,
) {
    /** Quanto del file si usa. */
    val lunghezzaMs: Long get() = fineMs - inizioMs
    /** Quanto dura nel progetto: la velocità accorcia o allunga. */
    val suonaMs: Long get() = if (velocita == 1f) lunghezzaMs else Math.round(lunghezzaMs.coerceAtLeast(0) / velocita.toDouble())
    val fineGlobaleMs: Long get() = posMs + suonaMs
    /** Il punto del file che suona al punto [globale] del progetto. */
    fun nelFile(globale: Long): Long = inizioMs + Math.round((globale - posMs) * velocita.toDouble())
}

/** La durata di una transizione se nessuno la cambia. */
const val DISSOLVENZA_STD = 500L

/**
 * Un testo sopra il video, da [inizioMs] a [fineMs] del video finito. Con dei [tratti] è un
 * DISEGNO a mano libera (stessa corsia, stesso tempo; il testo resta vuoto): i tratti stanno in
 * frazioni del fotogramma, come i testi (vedi Mano).
 */
data class Scritta(
    val testo: Testo, val inizioMs: Long, val fineMs: Long, val tratti: List<Tratto> = emptyList(),
    /** la riga scelta nella corsia = il livello: 0 = in cima, davanti a tutto */
    val riga: Int = 0,
) {
    val disegno get() = tratti.isNotEmpty()
}

/** Una bozza: nome, clip, musica, testi. */
data class Bozza(
    val nome: String,
    val clip: List<Clip>,
    val musica: List<Musica> = emptyList(),
    val scritte: List<Scritta> = emptyList(),
)

object Progetto {
    /** Il pezzo piu' corto che si puo' tenere: sotto, il taglio non ha senso. */
    const val MINIMO_MS = 300L

    /** Sposta la clip da [da] ad [a]; indici fuori dall'elenco = elenco uguale. */
    fun sposta(clip: List<Clip>, da: Int, a: Int): List<Clip> {
        if (da !in clip.indices || a !in clip.indices || da == a) return clip
        val m = clip.toMutableList()
        val c = m.removeAt(da)
        m.add(a, c)
        return m
    }

    /**
     * Taglia la clip fra [inizio] e [fine], dentro i limiti del file, e mai sotto
     * [MINIMO_MS] (o tutta la clip, se il file e' piu' corto del minimo).
     */
    fun taglia(c: Clip, inizio: Long, fine: Long): Clip {
        val dur = c.durataMs.coerceAtLeast(0)
        val min = MINIMO_MS.coerceAtMost(dur)
        var a = inizio.coerceIn(0, dur)
        var b = fine.coerceIn(0, dur)
        if (b < a) { val t = a; a = b; b = t }
        if (b - a < min) {
            b = (a + min).coerceAtMost(dur)
            a = b - min
        }
        return c.copy(inizioMs = a, fineMs = b)
    }

    /**
     * Divide la clip [i] in due al punto [puntoNelFile] (tempo dentro il file).
     * null se il punto e' troppo vicino a un bordo: ogni meta' deve restare >= [MINIMO_MS].
     */
    fun dividi(clip: List<Clip>, i: Int, puntoNelFile: Long): List<Clip>? {
        if (i !in clip.indices) return null
        val c = clip[i]
        if (puntoNelFile - c.inizioMs < MINIMO_MS || c.fineMs - puntoNelFile < MINIMO_MS) return null
        val m = clip.toMutableList()
        // il suono che entra piano resta alla prima meta', quello che esce piano alla seconda
        m[i] = c.copy(fineMs = puntoNelFile, esce = false)
        m.add(i + 1, c.copy(inizioMs = puntoNelFile, entra = false))
        return m
    }

    /** Una copia della clip [i], subito dopo di lei. */
    fun duplica(clip: List<Clip>, i: Int): List<Clip> {
        if (i !in clip.indices) return clip
        return clip.toMutableList().also { it.add(i + 1, clip[i]) }
    }

    fun totaleMs(clip: List<Clip>): Long = clip.sumOf { it.vistaMs }

    /** Dove comincia la clip [i] nel video finito. */
    fun inizioGlobale(clip: List<Clip>, i: Int): Long =
        clip.take(i.coerceIn(0, clip.size)).sumOf { it.vistaMs }

    /**
     * Un punto del video finito → (indice della clip, tempo dentro il FILE).
     * Oltre la fine = ultimo istante dell'ultima clip. Elenco vuoto = null.
     */
    fun dove(clip: List<Clip>, globaleMs: Long): Pair<Int, Long>? {
        if (clip.isEmpty()) return null
        var resto = globaleMs.coerceAtLeast(0)
        for ((i, c) in clip.withIndex()) {
            val l = c.vistaMs
            if (resto < l) return i to (c.inizioMs + Math.round(resto * c.velocita.toDouble())).coerceAtMost(c.fineMs)
            resto -= l
        }
        val u = clip.last()
        return clip.lastIndex to u.fineMs
    }

    /** «0:04.8» — minuti, secondi, decimi. Sopra l'ora: «1:02:03». */
    fun tempo(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val cent = (t % 1000) / 10
        val s = (t / 1000) % 60
        val m = (t / 60000) % 60
        val h = t / 3_600_000
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d.%02d".format(m, s, cent)
    }

    /**
     * La misura del video finito = quella della PRIMA clip (come la vede chi guarda,
     * rotazione applicata), senza chiedere niente. Lati pari (lo vuole il codificatore);
     * oltre 4K si rimpicciolisce tenendo la forma. Misura sconosciuta → 1080×1920.
     */
    fun misuraUscita(larghezza: Int, altezza: Int): Pair<Int, Int> {
        if (larghezza <= 0 || altezza <= 0) return 1080 to 1920
        var w = larghezza.toDouble(); var h = altezza.toDouble()
        val lungo = maxOf(w, h)
        if (lungo > 3840) { w = w * 3840 / lungo; h = h * 3840 / lungo }
        fun pari(x: Double) = (Math.round(x / 2.0) * 2).toInt().coerceAtLeast(2)
        return pari(w) to pari(h)
    }

    /** Nome del file esportato: «151 Editor 2026-10-11 16.42.07.mp4». */
    fun nomeFile(anno: Int, mese: Int, giorno: Int, ora: Int, minuto: Int, secondo: Int): String =
        "151 Editor %04d-%02d-%02d %02d.%02d.%02d.mp4".format(anno, mese, giorno, ora, minuto, secondo)

    // --- salvataggio di una bozza ---
    // Formato 3: «151art 3<TAB>nome», poi una riga per cosa: C (clip), M (musica), S (testo; un disegno
    // ha un 12° campo coi tratti, i testi restano a 11 e le versioni di prima li leggono uguale).
    // Si leggono anche i formati 1 e 2 (righe di clip a 5 campi, senza lettera) e le righe M a 12 campi
    // della 0.5.0 (gli ultimi due erano le voci che scendevano sotto la musica, tolte nella 0.5.1: si ignorano).

    private fun pulito(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    private fun b(x: Boolean) = if (x) "1" else "0"

    fun scrivi(bz: Bozza): String = buildString {
        append("151art 3\t").append(pulito(bz.nome)).append('\n')
        for (c in bz.clip) append(listOf("C", Foto.chiudi(c.uri), pulito(c.nome), c.durataMs, c.inizioMs, c.fineMs,
            c.volume, c.velocita, b(c.entra), b(c.esce), c.durEntra, c.durEsce).joinToString("\t")).append('\n')
        for (m in bz.musica) append(listOf("M", Foto.chiudi(m.uri), pulito(m.nome), m.durataMs, m.inizioMs, m.fineMs, m.posMs,
            m.volume, b(m.entra), b(m.esce)).joinToString("\t")).append('\n')
        for (sc in bz.scritte) { val t = sc.testo
            append(listOf("S", sc.inizioMs, sc.fineMs, t.id, t.x, t.y, t.misura, t.colore, Foto.chiudi(t.font), t.stile,
                Foto.chiudi(t.testo)).joinToString("\t"))
            // 12° campo i tratti (anche vuoto, se serve il 13°), 13° la riga, 14° e 15° secondo colore e spessore
            // del testo: un testo semplice resta a 11, ogni campo si scrive solo se serve lui o uno dopo
            val stile = t.secondo != 0 || t.spessore != Testo.SPESSORE_STD
            if (sc.disegno || sc.riga != 0 || stile) append('\t').append(Mano.scriviTutti(sc.tratti))
            if (sc.riga != 0 || stile) append('\t').append(sc.riga)
            if (stile) append('\t').append(t.secondo).append('\t').append(t.spessore)
            append('\n') }
    }

    /** Legge cio' che [scrivi] ha scritto; righe rotte si saltano, mai un crash. null = non e' una bozza. */
    fun leggi(testo: String): Bozza? {
        val righe = testo.split('\n')
        val testa = righe.firstOrNull()?.trimEnd('\r') ?: return null
        val nome = when {
            testa.trim() == "151art 1" -> ""
            testa.startsWith("151art 2\t") -> testa.removePrefix("151art 2\t")
            testa.startsWith("151art 3\t") -> testa.removePrefix("151art 3\t")
            testa.trim() == "151art 2" || testa.trim() == "151art 3" -> ""
            else -> return null
        }
        val clip = mutableListOf<Clip>(); val musica = mutableListOf<Musica>(); val scritte = mutableListOf<Scritta>()
        for (r in righe.drop(1)) {
            val p = r.split('\t')
            try {
                when {
                    p.size == 5 -> {
                        if (p[0].isBlank()) continue
                        clip += taglia(Clip(p[0], p[1], p[2].toLong()), p[3].toLong(), p[4].toLong())
                    }
                    p[0] == "C" && (p.size == 10 || p.size == 12) -> {
                        if (p[1].isBlank()) continue
                        val c = taglia(Clip(Foto.apri(p[1]), p[2], p[3].toLong()), p[4].toLong(), p[5].toLong())
                        clip += c.copy(volume = p[6].toInt().coerceIn(0, VOLUME_MAX),
                            velocita = vicina(p[7].toFloat()), entra = p[8] == "1", esce = p[9] == "1",
                            durEntra = if (p.size == 12) p[10].toLong().coerceIn(DISSOLVENZA_MIN, DISSOLVENZA_MAX) else DISSOLVENZA_STD,
                            durEsce = if (p.size == 12) p[11].toLong().coerceIn(DISSOLVENZA_MIN, DISSOLVENZA_MAX) else DISSOLVENZA_STD)
                    }
                    p[0] == "M" && (p.size == 10 || p.size == 12) -> {
                        if (p[1].isBlank()) continue
                        musica += Musica(Foto.apri(p[1]), p[2], p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong().coerceAtLeast(0),
                            p[7].toInt().coerceIn(0, VOLUME_MAX), p[8] == "1", p[9] == "1")
                    }
                    p[0] == "S" && (p.size in 11..13 || p.size == 15) -> {
                        val t = Testo(p[3].toLong(), Foto.apri(p[10]), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(),
                            p[7].toInt(), Foto.apri(p[8]), p[9].toInt(),
                            secondo = if (p.size == 15) p[13].toInt() else 0,
                            spessore = if (p.size == 15) Testo.spessoreGiusto(p[14].toFloat()) else Testo.SPESSORE_STD)
                        val tratti = if (p.size >= 12) Mano.leggiTutti(p[11]) else emptyList()
                        // un disegno coi tratti tutti rotti e senza testo non è niente
                        if (p.size >= 12 && tratti.isEmpty() && t.testo.isBlank()) continue
                        val riga = if (p.size >= 13) p[12].toInt().coerceIn(0, RIGHE_MAX - 1) else 0
                        scritte += Scritta(t, p[1].toLong(), p[2].toLong(), tratti, riga)
                    }
                }
            } catch (_: Exception) { /* riga rotta: si salta */ }
        }
        return Bozza(nome, clip, musica.sortedBy { it.posMs }, scritte.sortedBy { it.inizioMs })
    }

    // --- la calamita della timeline ---

    /**
     * Uno dei bordi [mobili], spostato di [delta], cade a meno di [soglia] ms da uno dei [bersagli]
     * (inizio e fine delle clip, degli altri blocchi, la riga bianca)? Allora il delta che ce lo porta
     * esatto, il più vicino se sono tanti; null = libero, il dito comanda.
     */
    fun calamita(mobili: LongArray, bersagli: LongArray, delta: Long, soglia: Long): Long? {
        var meglio: Long? = null
        var distanza = Long.MAX_VALUE
        for (m in mobili) for (b in bersagli) {
            val d = kotlin.math.abs(m + delta - b)
            if (d <= soglia && d < distanza) { distanza = d; meglio = b - m }
        }
        return meglio
    }

    // --- volume, velocita', dissolvenze ---

    /** Il volume scritto a mano arriva fin qui; il cursore va fino a [VOLUME_CURSORE]. */
    const val VOLUME_MAX = 400
    const val VOLUME_CURSORE = 200
    /**
     * La velocità: scritta a mano da 0.1× a 30×; il cursore va da 0.25× a 4× con 1× AL CENTRO
     * (scala a potenze di 2: [posizione] e [daPosizione]), come il volume ha 100% al centro.
     */
    const val VELOCITA_MIN = 0.1f
    const val VELOCITA_MAX = 30f
    const val CURSORE_VELOCITA_MIN = 0.25f
    const val CURSORE_VELOCITA_MAX = 4f
    /** I punti dove i cursori si attaccano (calamite): le velocità tonde, ogni 25% il volume. */
    val CALAMITE_VELOCITA = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
    val CALAMITE_VOLUME = FloatArray(VOLUME_CURSORE / 25 + 1) { it * 25f }
    /** Quanto deve avvicinarsi il cursore a una calamita per attaccarsi. */
    const val CALAMITA_VELOCITA = 0.04f
    const val CALAMITA_VOLUME = 4f
    /** Quanto dura il suono della musica che entra o esce piano. */
    const val DISSOLVENZA_MS = DISSOLVENZA_STD
    /** Una transizione dura fra questi due (si sceglie nel suo pannello). */
    const val DISSOLVENZA_MIN = 100L
    const val DISSOLVENZA_MAX = 3000L
    /** Quante righe (livelli) ha la corsia dei testi. */
    const val RIGHE_MAX = 3

    /** «1.40 s»: le durate coi centesimi, sempre col punto. */
    fun secondi(ms: Long): String = String.format(java.util.Locale.US, "%.2f s", ms / 1000f)

    // --- transizioni: un punto di giunzione k fra la clip k-1 e la clip k (0 = l'inizio, n = la fine) ---
    // «Fade through black» = la clip prima esce (immagine e suono verso il nero) e quella dopo entra.

    fun transizione(clip: List<Clip>, k: Int): Boolean = when {
        clip.isEmpty() || k < 0 || k > clip.size -> false
        k == 0 -> clip[0].entra
        k == clip.size -> clip.last().esce
        else -> clip[k - 1].esce && clip[k].entra
    }

    fun conTransizione(clip: List<Clip>, k: Int, si: Boolean, durata: Long = durataTransizione(clip, k)): List<Clip> {
        if (clip.isEmpty() || k < 0 || k > clip.size) return clip
        val d = durata.coerceIn(DISSOLVENZA_MIN, DISSOLVENZA_MAX)
        return clip.toMutableList().also { m ->
            if (k > 0) m[k - 1] = m[k - 1].copy(esce = si, durEsce = d)
            if (k < m.size) m[k] = m[k].copy(entra = si, durEntra = d)
        }
    }

    /** Quanto dura la transizione del punto k (metà nella clip prima, metà in quella dopo). */
    fun durataTransizione(clip: List<Clip>, k: Int): Long = when {
        clip.isEmpty() || k < 0 || k > clip.size -> DISSOLVENZA_STD
        k < clip.size -> clip[k].durEntra
        else -> clip.last().durEsce
    }

    /** Quanto è nero il video al punto [t] (0 = niente, 1 = tutto nero): le transizioni delle clip. */
    fun nero(clip: List<Clip>, t: Long): Float {
        val (i, _) = dove(clip, t) ?: return 0f
        val c = clip[i]
        val qui = t - inizioGlobale(clip, i)
        return (1f - dissolvenza(qui, c.vistaMs, c.entra, c.esce, c.durEntra, c.durEsce)).coerceIn(0f, 1f)
    }

    /** «0.25×», «1.35×», «2×» (sempre col punto, qualunque lingua abbia il telefono). */
    fun velocitaScritta(v: Float): String {
        val r = Math.round(v * 100) / 100.0
        return (if (r == Math.floor(r)) r.toInt().toString() else r.toString().trimEnd('0').trimEnd('.')) + "×"
    }

    /** Una velocità valida: dentro 0.1–30×, sotto 2× a passi di 0.05, sopra a passi di 0.5 (un file rotto non porta velocità strane). */
    fun vicina(v: Float): Float {
        if (!v.isFinite()) return 1f
        val c = v.coerceIn(VELOCITA_MIN, VELOCITA_MAX)
        val passo = if (c < 2f) 0.05f else 0.5f
        return (Math.round(c / passo) * passo).coerceIn(VELOCITA_MIN, VELOCITA_MAX)
    }

    /** Dove sta la velocità [v] sul cursore: -1 (0.25×) · 0 (1×) · +1 (4×). */
    fun posizione(v: Float): Float =
        (kotlin.math.ln(v.coerceIn(CURSORE_VELOCITA_MIN, CURSORE_VELOCITA_MAX)) / kotlin.math.ln(CURSORE_VELOCITA_MAX)).toFloat()

    /** La velocità a quel punto del cursore (il contrario di [posizione]). */
    fun daPosizione(p: Float): Float = Math.pow(CURSORE_VELOCITA_MAX.toDouble(), p.coerceIn(-1f, 1f).toDouble()).toFloat()

    /** Il passo dei bottoni − e +: piccolo vicino a 1×, più grande dove la velocità è alta. */
    fun passoVelocita(v: Float, verso: Int): Float {
        val passo = if (v < 2f || (v == 2f && verso < 0)) 0.05f else 0.5f
        return vicina(v + verso * passo)
    }

    /** Il valore del cursore attaccato alla calamita più vicina, se sta entro [soglia]; se no com'è. */
    fun calamita(v: Float, punti: FloatArray, soglia: Float): Float {
        val p = punti.minByOrNull { kotlin.math.abs(it - v) } ?: return v
        return if (kotlin.math.abs(p - v) <= soglia) p else v
    }

    /** 0..1: quanto si sente a [t] ms dall'inizio di un pezzo lungo [lunghezza], con le dissolvenze. */
    fun dissolvenza(t: Long, lunghezza: Long, entra: Boolean, esce: Boolean,
                    durEntra: Long = DISSOLVENZA_MS, durEsce: Long = DISSOLVENZA_MS): Float {
        val de = minOf(durEntra, lunghezza / 2).coerceAtLeast(1)
        val du = minOf(durEsce, lunghezza / 2).coerceAtLeast(1)
        var g = 1f
        if (entra && t < de) g = minOf(g, t.coerceAtLeast(0).toFloat() / de)
        if (esce && lunghezza - t < du) g = minOf(g, (lunghezza - t).coerceAtLeast(0).toFloat() / du)
        return g
    }

    /** Il guadagno della clip [i] a [t] ms dal suo inizio NEL VIDEO FINITO. */
    fun guadagnoClip(bz: Bozza, i: Int, t: Long): Float {
        val c = bz.clip[i]
        return c.volume / 100f * dissolvenza(t, c.vistaMs, c.entra, c.esce, c.durEntra, c.durEsce)
    }

    /** Il guadagno della musica [m] a [t] ms dal suo inizio. */
    fun guadagnoMusica(m: Musica, t: Long): Float = m.volume / 100f * dissolvenza(t, m.suonaMs, m.entra, m.esce, m.durEntra, m.durEsce)

    // --- le corsie di musica e testi: pezzi in fila che non si sovrappongono ---

    /** Lo spazio libero intorno al pezzo [i] di una corsia: (fine del precedente, inizio del seguente). */
    private fun spazio(pezzi: List<Pair<Long, Long>>, i: Int): Pair<Long, Long> {
        var prima = 0L; var dopo = Long.MAX_VALUE
        for ((k, p) in pezzi.withIndex()) {
            if (k == i) continue
            if (p.second <= pezzi[i].first) prima = maxOf(prima, p.second)
            else if (p.first >= pezzi[i].second) dopo = minOf(dopo, p.first)
        }
        return prima to dopo
    }

    /** Dove c'e' posto per un pezzo nuovo che parte da [t]: la fine libera (null = [t] cade su un altro pezzo). */
    fun postoDa(pezzi: List<Pair<Long, Long>>, t: Long): Long? {
        if (pezzi.any { t >= it.first && t < it.second }) return null
        return pezzi.filter { it.first > t }.minOfOrNull { it.first } ?: Long.MAX_VALUE
    }

    private fun corsiaMusica(m: List<Musica>) = m.map { it.posMs to it.fineGlobaleMs }

    /** La musica nuova [m] parte da [t]; se non c'e' posto fin dove finisce, si accorcia. null = nessun posto. */
    fun mettiMusica(musica: List<Musica>, m: Musica, t: Long): List<Musica>? {
        val pos = t.coerceAtLeast(0)
        val fine = postoDa(corsiaMusica(musica), pos) ?: return null
        val lun = minOf(m.durataMs - m.inizioMs, fine - pos)
        if (lun < MINIMO_MS) return null
        return (musica + m.copy(posMs = pos, fineMs = m.inizioMs + lun)).sortedBy { it.posMs }
    }

    /** Sposta la musica [i] a cominciare da [pos], senza sovrapporsi alle altre. */
    fun spostaMusica(musica: List<Musica>, i: Int, pos: Long): List<Musica> {
        if (i !in musica.indices) return musica
        val m = musica[i]
        val (prima, dopo) = spazio(corsiaMusica(musica), i)
        val p = pos.coerceAtLeast(prima).coerceAtMost(if (dopo == Long.MAX_VALUE) Long.MAX_VALUE / 2 else dopo - m.suonaMs)
        if (p < prima) return musica
        return musica.toMutableList().also { it[i] = m.copy(posMs = p) }.sortedBy { it.posMs }
    }

    /** Muove un bordo della musica [i] di [delta] ms: il sinistro taglia l'inizio e sposta, il destro taglia la fine. */
    fun bordoMusica(musica: List<Musica>, i: Int, sinistro: Boolean, delta: Long): List<Musica> {
        if (i !in musica.indices) return musica
        val m = musica[i]
        val (prima, dopo) = spazio(corsiaMusica(musica), i)
        // [delta] è tempo del progetto; nel file vale [delta] × velocità (a 1× è lo stesso)
        val v = m.velocita.toDouble()
        val minFile = Math.round(MINIMO_MS * v)
        val nuova = if (sinistro) {
            val d = delta.coerceAtLeast(maxOf(-Math.round(m.inizioMs / v), prima - m.posMs)).coerceAtMost(m.suonaMs - MINIMO_MS)
            m.copy(inizioMs = (m.inizioMs + Math.round(d * v)).coerceIn(0, m.fineMs - minFile), posMs = m.posMs + d)
        } else {
            val maxFine = minOf(m.durataMs, if (dopo == Long.MAX_VALUE) m.durataMs else m.inizioMs + Math.round((dopo - m.posMs) * v))
            m.copy(fineMs = (m.fineMs + Math.round(delta * v)).coerceIn(m.inizioMs + minFile, maxOf(m.inizioMs + minFile, maxFine)))
        }
        return musica.toMutableList().also { it[i] = nuova }
    }

    /** Divide la musica sotto [globale]; null se non c'e' musica li' o se un pezzo resterebbe troppo corto. */
    fun dividiMusica(musica: List<Musica>, globale: Long): List<Musica>? {
        val i = musica.indexOfFirst { globale > it.posMs && globale < it.fineGlobaleMs }
        if (i < 0) return null
        val m = musica[i]
        val nelFile = m.nelFile(globale)
        if (globale - m.posMs < MINIMO_MS || m.fineGlobaleMs - globale < MINIMO_MS) return null
        return musica.toMutableList().also {
            it[i] = m.copy(fineMs = nelFile, esce = false)
            it.add(i + 1, m.copy(inizioMs = nelFile, posMs = globale, entra = false))
        }
    }

    /**
     * Un testo (o disegno) nuovo che parte da [t] e dura [lunghezza]. Dal 0.10.0 i testi si
     * sovrappongono (più testi nello stesso momento, «chi lo usa 14/10»): la timeline li mette su righe.
     */
    fun mettiScritta(scritte: List<Scritta>, testo: Testo, t: Long, lunghezza: Long): List<Scritta> {
        val a = t.coerceAtLeast(0)
        return (scritte + Scritta(testo, a, a + lunghezza.coerceAtLeast(MINIMO_MS))).sortedBy { it.inizioMs }
    }

    fun spostaScritta(scritte: List<Scritta>, i: Int, inizio: Long): List<Scritta> {
        if (i !in scritte.indices) return scritte
        val s = scritte[i]
        val lun = s.fineMs - s.inizioMs
        val a = inizio.coerceAtLeast(0)
        return scritte.toMutableList().also { it[i] = s.copy(inizioMs = a, fineMs = a + lun) }.sortedBy { it.inizioMs }
    }

    fun bordoScritta(scritte: List<Scritta>, i: Int, sinistro: Boolean, delta: Long): List<Scritta> {
        if (i !in scritte.indices) return scritte
        val s = scritte[i]
        val nuova = if (sinistro) s.copy(inizioMs = (s.inizioMs + delta).coerceIn(0, s.fineMs - MINIMO_MS))
        else s.copy(fineMs = (s.fineMs + delta).coerceAtLeast(s.inizioMs + MINIMO_MS))
        return scritte.toMutableList().also { it[i] = nuova }
    }

    /**
     * Su che riga della corsia sta ogni testo: chi si sovrappone va sotto (la prima riga libera).
     * [scritte] in ordine d'inizio, come le tiene il progetto.
     */
    fun righeScritte(scritte: List<Scritta>): IntArray {
        // prima la riga che ognuno ha scelto; se lì c'è già qualcuno nello stesso tempo, la prima libera
        val out = IntArray(scritte.size) { -1 }
        fun libera(k: Int, r: Int) = scritte.indices.none { j ->
            out[j] == r && scritte[j].inizioMs < scritte[k].fineMs && scritte[k].inizioMs < scritte[j].fineMs
        }
        for (k in scritte.indices) {
            var r = scritte[k].riga.coerceAtLeast(0)
            if (!libera(k, r)) { r = 0; while (!libera(k, r)) r++ }
            out[k] = r
        }
        return out
    }

    /**
     * Il testo [i] va sulla riga [r] (= livello). Chi ci stava nello stesso tempo prende la riga
     * che il testo lascia: uno scambio, così nessuno finisce dove non l'ha messo nessuno.
     */
    fun cambiaRiga(scritte: List<Scritta>, i: Int, r: Int): List<Scritta> {
        if (i !in scritte.indices) return scritte
        val nuova = r.coerceIn(0, RIGHE_MAX - 1)
        val righe = righeScritte(scritte)
        val vecchia = righe[i]
        if (nuova == vecchia) return scritte
        val s = scritte[i]
        return scritte.mapIndexed { k, x ->
            when {
                k == i -> x.copy(riga = nuova)
                righe[k] == nuova && x.inizioMs < s.fineMs && s.inizioMs < x.fineMs -> x.copy(riga = vecchia)
                else -> x.copy(riga = righe[k])
            }
        }
    }

    /** I testi e disegni del momento [t] nell'ordine in cui si disegnano: prima quelli dietro (righe basse), per ultima la riga 0. */
    fun scritteDaDisegnare(scritte: List<Scritta>, t: Long): List<Scritta> {
        val righe = righeScritte(scritte)
        return scritte.indices.filter { t >= scritte[it].inizioMs && t < scritte[it].fineMs }
            .sortedByDescending { righe[it] }.map { scritte[it] }
    }

    /** I testi che si vedono al punto [t] del video. */
    fun scritteA(scritte: List<Scritta>, t: Long): List<Scritta> = scritte.filter { t >= it.inizioMs && t < it.fineMs }
}

/**
 * Annulla / Rifai: ogni stato che chi lo usa ha prodotto (il montaggio del video, le modifiche
 * della foto). [fai] aggiunge uno stato nuovo e butta i «rifai» (come ogni editor);
 * al massimo [tetto] passi indietro.
 */
class Storia<T>(iniziale: T, private val tetto: Int = 60) {
    private val indietro = ArrayDeque<T>()
    private val avanti = ArrayDeque<T>()
    var adesso: T = iniziale
        private set

    val puoiAnnullare get() = indietro.isNotEmpty()
    val puoiRifare get() = avanti.isNotEmpty()

    fun fai(nuovo: T) {
        if (nuovo == adesso) return
        indietro.addLast(adesso)
        while (indietro.size > tetto) indietro.removeFirst()
        avanti.clear()
        adesso = nuovo
    }

    fun annulla(): Boolean {
        val p = indietro.removeLastOrNull() ?: return false
        avanti.addLast(adesso); adesso = p; return true
    }

    fun rifai(): Boolean {
        val p = avanti.removeLastOrNull() ?: return false
        indietro.addLast(adesso); adesso = p; return true
    }
}
