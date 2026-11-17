package com.the151universe.art

/**
 * Una carta + una lista = tutte le carte (come «Data Merge» di InDesign, chi lo usa 05/11: «the design for some cards…
 * of all the different cards»). Sulla pagina modello un testo con «{Title}» dice dove va il primo campo; ogni riga della
 * lista diventa una pagina, con al posto di {Title}, {Text}… i pezzi di quella riga, nell'ordine dei campi.
 * Logica pura: niente Android.
 */
object Carte {
    private val CAMPO = Regex("\\{([^{}\\n]{1,30})\\}")

    /** I campi della pagina [p] (e del suo [retro]: prima il fronte), testi e celle, nell'ordine in cui si leggono. */
    fun campi(g: Grafica, p: Int, retro: Int? = null): List<String> {
        fun di(q: Int) = g.elementi.filter { it.pagina == q || (g.madre(q) > 0 && it.pagina == -g.madre(q)) }
            .sortedWith(compareBy<Elemento> { it.y }.thenBy { it.x })
        val testi = (di(p) + (retro?.let { di(it) } ?: emptyList()))
            .flatMap { e -> if (e.tipo == Elemento.TABELLA) e.celle.map { it.testo } else if (e.tipo == Elemento.TESTO) listOf(e.testo) else emptyList() }
        return testi.flatMap { t -> CAMPO.findAll(t).map { it.groupValues[1].trim() } }.filter { it.isNotEmpty() }.distinct()
    }

    /** Il riquadro che riceve la foto di ogni carta: il primo vuoto della pagina [p] (dall'alto), se no la prima foto; null = nessuno. */
    fun cornice(g: Grafica, p: Int): Elemento? {
        val foto = g.di(p).filter { it.tipo == Elemento.FOTO }.sortedWith(compareBy<Elemento> { it.y }.thenBy { it.x })
        return foto.firstOrNull { it.uri.isEmpty() } ?: foto.firstOrNull()
    }

    /** Un nome di file o una parola, pronti per il confronto: senza cartella né estensione, minuscolo, «_» «-» come spazi. */
    fun chiave(s: String): String =
        s.substringAfterLast('/').trim().replace(Regex("\\.(jpe?g|png|webp|heic|heif|gif|bmp)$", RegexOption.IGNORE_CASE), "")
            .lowercase().replace(Regex("[_\\-]+"), " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Quale foto va su ogni carta: per nome se almeno una foto ha il nome di un pezzo di una riga (cat.jpg ↔ «cat»;
     * le carte senza la loro foto restano col riquadro com'è), se no in fila (prima foto → prima carta).
     * Indici in [nomi]; null = nessuna foto per quella carta.
     */
    fun abbina(righe: List<List<String>>, nomi: List<String>): List<Int?> {
        val perNome = HashMap<String, Int>()
        nomi.forEachIndexed { i, n -> chiave(n).takeIf { it.isNotEmpty() }?.let { perNome.putIfAbsent(it, i) } }
        val trovate = righe.map { r -> r.firstNotNullOfOrNull { perNome[chiave(it)] } }
        return if (trovate.any { it != null }) trovate else righe.indices.map { it.takeIf { i -> i < nomi.size } }
    }

    /**
     * Le righe della lista, ognuna divisa nei suoi pezzi: tab, poi «;», poi « - » / « – » / « = ». Righe vuote
     * saltate; una prima riga uguale ai nomi dei [campi] è l'intestazione e si salta.
     */
    fun righe(testo: String, campi: List<String>): List<List<String>> {
        val tutte = testo.replace("\r", "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val divise = tutte.map { r ->
            val sep = when {
                '\t' in r -> Regex("\\t")
                ';' in r -> Regex(";")
                campi.size > 1 && Regex("\\s[-–=]\\s").containsMatchIn(r) -> Regex("\\s[-–=]\\s")
                else -> null
            }
            (if (sep == null) listOf(r) else r.split(sep, limit = maxOf(campi.size, 1))).map { it.trim() }
        }
        val prima = divise.firstOrNull()
        return if (prima != null && campi.isNotEmpty() && prima.map { it.lowercase() } == campi.map { it.lowercase() }) divise.drop(1) else divise
    }

    /** Il testo [t] coi suoi segni, ogni {campo} sostituito: i segni seguono le lettere ([Ricco.dopoScrittura]). */
    private fun riempi(t: String, segni: List<Segno>, valori: Map<String, String>): Pair<String, List<Segno>> {
        var testo = t; var sg = segni
        while (true) {
            val m = CAMPO.findAll(testo).firstOrNull { it.groupValues[1].trim() in valori } ?: break
            val nuovo = testo.substring(0, m.range.first) + valori.getValue(m.groupValues[1].trim()) + testo.substring(m.range.last + 1)
            sg = Ricco.dopoScrittura(testo, nuovo, sg)
            testo = nuovo
        }
        return testo to sg
    }

    /**
     * Un progetto NUOVO con una carta per riga: gli oggetti della pagina modello [p] copiati (id nuovi), i campi
     * riempiti; madri, stili, margini e formato uguali. Col [retro] (un'altra pagina) ogni carta è due pagine,
     * fronte e retro, e il progetto stampa fronte/retro. [foto] (uri, una per riga o null) va nel [cornice] del fronte,
     * se no in quello del retro. Al massimo [Grafica.PAGINE_MAX] pagine.
     * Le altezze dei testi le rimisura chi disegna ([GraficaDisegno.misura]).
     */
    fun unisci(g: Grafica, p: Int, righe: List<List<String>>, nome: String, retro: Int? = null, foto: List<String?> = emptyList()): Grafica {
        val campi = campi(g, p, retro)
        val facce = if (retro != null && retro != p) listOf(p, retro) else listOf(p)
        val usate = righe.take(Grafica.PAGINE_MAX / facce.size)
        val dove = cornice(g, p)?.id ?: retro?.let { cornice(g, it)?.id }
        val madri = g.elementi.filter { it.pagina < 0 }
        var id = (g.elementi.maxOfOrNull { it.id } ?: 0L) + 1
        val nuovi = ArrayList<Elemento>()
        usate.forEachIndexed { i, riga ->
            val valori = campi.mapIndexed { k, c -> c to riga.getOrElse(k) { "" } }.toMap()
            val suaFoto = foto.getOrNull(i)
            facce.forEachIndexed { lato, q ->
                for (e in g.di(q)) {
                    val n = when (e.tipo) {
                        Elemento.TESTO -> riempi(e.testo, e.segni, valori).let { (t, s) -> e.copy(testo = t, segni = s) }
                        Elemento.TABELLA -> e.copy(celle = e.celle.map { c -> riempi(c.testo, c.segni, valori).let { (t, s) -> c.copy(testo = t, segni = s) } })
                        Elemento.FOTO -> if (e.id == dove && suaFoto != null) e.copy(uri = suaFoto, zoomFoto = 1f, fuocoX = 0.5f, fuocoY = 0.5f) else e
                        else -> e
                    }
                    nuovi += n.copy(id = id++, pagina = i * facce.size + lato)
                }
            }
        }
        val pagine = maxOf(1, usate.size * facce.size)
        return g.copy(nome = nome, elementi = madri + nuovi, pagine = pagine, madreDi = List(pagine) { g.madre(facce[it % facce.size]) },
            perStampa = g.perStampa.copy(retro = facce.size == 2))
    }
}
