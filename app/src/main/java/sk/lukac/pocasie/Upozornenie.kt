package sk.lukac.pocasie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pravidlo upozornenia a jeho vyhodnotenie.
 *
 * Bez jedineho odkazu na Android, aby sa dalo otestovat — samotne notifikacie
 * rieši [Alerty].
 *
 * **Vyhodnocuje sa z PREDPOVEDE, nie z aktualneho stavu.** Upozornenie "bude
 * mraz" ma cenu vecer pred tym, nie rano, ked uz mrzne. Preto sa prehladavaju
 * hodiny dopredu a hlada sa PRVA, ktora hranicu prekroci.
 *
 * **A vyhodnocuje sa z uz stiahnutych dat.** Ziadne pravidlo nespusti vlastne
 * stahovanie: bezi nad tym, co prislo pri poslednej obnove. Inak by kazde
 * pridane upozornenie znamenalo dalsi prenos — presne to, comu sa appka
 * vyhyba.
 */

enum class DruhUpozornenia {
    /** Teplota klesne POD hranicu. */
    TEPLOTA_POD,

    /** Teplota stupne NAD hranicu. */
    TEPLOTA_NAD,

    /** Akekolvek zrazky (hranica sa ignoruje). */
    ZRAZKY_AKEKOLVEK,

    /** Zrazky za hodinu prekrocia hranicu v mm. */
    ZRAZKY_NAD,

    /**
     * Uhrn zrazok za kalendarny den prekroci hranicu v mm (od 23. 9. 2026).
     *
     * "Nad 2 mm" sa da mysliet za hodinu aj za den a su to uplne ine veci:
     * 2 mm za hodinu je poriadny dazd, 2 mm za den mrholenie. Preto oboje.
     */
    ZRAZKY_DEN_NAD,
}

data class Upozornenie(
    val id: String,
    val druh: DruhUpozornenia,
    /** Prazdne = plati pre vsetky miesta. Inak id miesta. */
    val miestoId: String = "",
    val hranica: Double = 0.0,
    /** Kolko hodin dopredu sa sleduje. */
    val hodinDopredu: Int = 24,
    val zapnute: Boolean = true,
) {
    fun jePre(miesto: Miesto): Boolean = miestoId.isBlank() || miestoId == miesto.id

    fun doJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("druh", druh.name)
        put("miesto", miestoId)
        put("hranica", hranica)
        put("hodin", hodinDopredu)
        put("zapnute", zapnute)
    }

    companion object {
        fun zJson(o: JSONObject): Upozornenie? {
            val druh = runCatching { DruhUpozornenia.valueOf(o.optString("druh")) }
                .getOrNull() ?: return null
            return Upozornenie(
                id = o.optString("id").ifBlank { return null },
                druh = druh,
                miestoId = o.optString("miesto"),
                hranica = o.optDouble("hranica", 0.0),
                hodinDopredu = o.optInt("hodin", 24).coerceIn(1, 168),
                zapnute = o.optBoolean("zapnute", true),
            )
        }

        fun zoZoznamu(json: String): List<Upozornenie> = runCatching {
            val pole = JSONArray(json)
            (0 until pole.length()).mapNotNull { zJson(pole.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun doZoznamu(zoznam: List<Upozornenie>): String =
            JSONArray().apply { zoznam.forEach { put(it.doJson()) } }.toString()
    }
}

/** Co pravidlo nasla — hodina, ktora hranicu prekrocila. */
data class Zasah(
    val upozornenie: Upozornenie,
    val miesto: Miesto,
    val hodina: Hodina,
    /**
     * Prva hodina suvisleho useku, do ktoreho [hodina] patri — aj ked uz je
     * minulostou. Podla nej sa pozna, ze je to ten isty mraz alebo dazd.
     * Pri dennom uhrne je to datum dna ("2026-09-24").
     */
    val zaciatok: String = hodina.cas,
    /** Pri dennom uhrne sucet zrazok za den v mm, inak null. */
    val sucet: Double? = null,
)

object Pravidla {

    /**
     * Prva hodina, ktora pravidlo splni, alebo null.
     *
     * [odCasu] je "teraz" v lokalnom case miesta ("2026-09-03T14:00"); hodiny
     * pred nim sa preskakuju. Minule pocasie sa uz upozornit neda a hlasit ho
     * by znamenalo, ze appka zvoni na to, co clovek prave zazil.
     */
    fun zasah(u: Upozornenie, miesto: Miesto, p: Predpoved, odCasu: String): Zasah? {
        if (!u.zapnute || !u.jePre(miesto)) return null
        if (u.druh == DruhUpozornenia.ZRAZKY_DEN_NAD) return zasahDna(u, miesto, p, odCasu)
        val okno = p.hodinyOd(odCasu).take(u.hodinDopredu)
        val h = okno.firstOrNull { splna(u, it) } ?: return null
        // Zaciatok useku sa hlada aj v uz odzitych hodinach dneska (hodinova
        // predpoved zacina polnocou). Bez toho by mraz od 02:00 do 06:00 pri
        // obnove o 05:00 mal "prvu hodinu" 05:00, teda novy kluc — a appka by
        // na ten isty mraz zvonila pri kazdej obnove, kym trva (do 22. 9. 2026).
        var i = p.hodiny.indexOfFirst { it.cas == h.cas }
        while (i > 0 && splna(u, p.hodiny[i - 1])) i--
        return Zasah(u, miesto, h, if (i >= 0) p.hodiny[i].cas else h.cas)
    }

    /**
     * Prvy den v okne, ktoreho uhrn prekroci hranicu.
     *
     * Okno ([Upozornenie.hodinDopredu]) urcuje, KTORE dni sa sleduju — kazdy,
     * do ktoreho zasahuje aspon jedna hodina okna. Scitava sa ale cely den,
     * nie len jeho cast v okne: 24 h od 15:00 by zo zajtrajska videlo len
     * dopoludnie a popoludnajsia burka by sa nerátala. Dnesok sa scita len
     * od teraz: co uz spadlo, sa upozornit neda.
     *
     * Pocita sa z hodinovej predpovede, nie z denneho suctu, prave kvoli
     * dnesku — denny sucet by v sebe mal aj ranny dazd, ktory uz prestal.
     */
    private fun zasahDna(u: Upozornenie, miesto: Miesto, p: Predpoved, odCasu: String): Zasah? {
        val dni = p.hodinyOd(odCasu).take(u.hodinDopredu).map { it.den }.distinct()
        for (den in dni) {
            val hodiny = p.hodiny.filter { it.den == den && it.cas >= odCasu }
            val sucet = hodiny.sumOf { it.zrazkyMm }
            if (hodiny.isNotEmpty() && sucet > u.hranica) {
                return Zasah(u, miesto, hodiny.first(), zaciatok = den, sucet = sucet)
            }
        }
        return null
    }

    fun splna(u: Upozornenie, h: Hodina): Boolean = when (u.druh) {
        DruhUpozornenia.TEPLOTA_POD -> h.teplota < u.hranica
        DruhUpozornenia.TEPLOTA_NAD -> h.teplota > u.hranica
        // "Akekolvek zrazky" nie je "vacsie nez nula": Open-Meteo pri suchu
        // vracia 0.0 aj drobne zvysky ako 0.05 mm, co je rosa, nie dazd.
        // Desatina milimetra je najmensia hodnota, ktoru ma zmysel hlasit.
        DruhUpozornenia.ZRAZKY_AKEKOLVEK -> h.zrazkyMm >= 0.1
        DruhUpozornenia.ZRAZKY_NAD -> h.zrazkyMm > u.hranica
        // Denny uhrn sa neposudzuje po hodinach — viď [zasahDna].
        DruhUpozornenia.ZRAZKY_DEN_NAD -> false
    }

    /** Predvolena hranica noveho pravidla — nech pole nezacina nulou pri vsetkom. */
    fun predvolenaHranica(druh: DruhUpozornenia): Double = when (druh) {
        DruhUpozornenia.TEPLOTA_POD -> 0.0
        DruhUpozornenia.TEPLOTA_NAD -> 30.0
        DruhUpozornenia.ZRAZKY_AKEKOLVEK -> 0.0
        DruhUpozornenia.ZRAZKY_NAD -> 1.0
        DruhUpozornenia.ZRAZKY_DEN_NAD -> 5.0
    }

    fun jeTeplota(druh: DruhUpozornenia): Boolean =
        druh == DruhUpozornenia.TEPLOTA_POD || druh == DruhUpozornenia.TEPLOTA_NAD

    /**
     * Kluc jedneho zasahu — podla neho sa pozna, ze uz bol ohlaseny.
     *
     * Obsahuje ZACIATOK useku, nie cas vyhodnotenia ani prvu buducu hodinu:
     * to je cely rozdiel medzi "ozvem sa raz na kazdy mraz" a "ozvem sa kazde
     * tri hodiny na ten isty mraz". Bez toho by obnova na pozadi posielala tu
     * istu spravu dokola, kym mraz neprejde — a upozornenia by clovek vypol.
     */
    fun kluc(z: Zasah): String = "${z.upozornenie.id}|${z.miesto.id}|${z.zaciatok}"
}
