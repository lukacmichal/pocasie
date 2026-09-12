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
        val okno = p.hodinyOd(odCasu).take(u.hodinDopredu)
        val h = okno.firstOrNull { splna(u, it) } ?: return null
        return Zasah(u, miesto, h)
    }

    fun splna(u: Upozornenie, h: Hodina): Boolean = when (u.druh) {
        DruhUpozornenia.TEPLOTA_POD -> h.teplota < u.hranica
        DruhUpozornenia.TEPLOTA_NAD -> h.teplota > u.hranica
        // "Akekolvek zrazky" nie je "vacsie nez nula": Open-Meteo pri suchu
        // vracia 0.0 aj drobne zvysky ako 0.05 mm, co je rosa, nie dazd.
        // Desatina milimetra je najmensia hodnota, ktoru ma zmysel hlasit.
        DruhUpozornenia.ZRAZKY_AKEKOLVEK -> h.zrazkyMm >= 0.1
        DruhUpozornenia.ZRAZKY_NAD -> h.zrazkyMm > u.hranica
    }

    /**
     * Kluc jedneho zasahu — podla neho sa pozna, ze uz bol ohlaseny.
     *
     * Obsahuje HODINU zasahu, nie cas vyhodnotenia: to je cely rozdiel medzi
     * "ozvem sa raz na kazdy mraz" a "ozvem sa kazde tri hodiny na ten isty
     * mraz". Bez toho by obnova na pozadi posielala tu istu spravu dokola,
     * kym mraz neprejde — a upozornenia by clovek vypol.
     */
    fun kluc(z: Zasah): String = "${z.upozornenie.id}|${z.miesto.id}|${z.hodina.cas}"
}
