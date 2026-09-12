package sk.lukac.pocasie

import org.json.JSONObject

/**
 * Predpoved pre jedno miesto — model a jeho parser.
 *
 * Zamerne bez jedineho odkazu na Android: vsetko dolezite (parsovanie odpovede,
 * hladanie minima/maxima, vyhodnotenie upozorneni) sa tak da otestovat bez
 * emulatora. Rovnaka delba ako `Kurzy.kt` v askener-i.
 *
 * **Casy su lokalne pre dane miesto.** Open-Meteo s `timezone=auto` vracia
 * "2026-09-03T14:00" bez zony a uz prepocitane do casu toho mesta. Neprevadzaju
 * sa preto na Instant: predpoved pre Sydney ma zmysel citat v sydneyskom case,
 * nie v nasom, a prepocet by tam vniesol chybu, ktoru by nikto nehladal.
 */

/** Jedna hodina predpovede. */
data class Hodina(
    /** "2026-09-03T14:00" — lokalny cas miesta. */
    val cas: String,
    val teplota: Double,
    val zrazkyMm: Double,
    val pravdepodobnostZrazok: Int,
    val kod: Int,
) {
    val den: String get() = cas.take(10)
    val hodina: String get() = cas.substring(11, 16)
}

/** Jeden den predpovede. */
data class Den(
    /** "2026-09-03" */
    val datum: String,
    val minTeplota: Double,
    val maxTeplota: Double,
    val zrazkyMm: Double,
    val kod: Int,
    /**
     * Vychod a zapad slnka ako lokalny cas miesta ("2026-09-03T06:24"),
     * rovnako ako ostatne casy v tomto subore. Prazdny retazec = server ich
     * neposlal (stara odpoved v cache, alebo polarny den/noc, kedy ich Open-Meteo
     * naozaj nevracia).
     */
    val vychod: String = "",
    val zapad: String = "",
) {
    /** "6:24" — len hodina a minuta, na zobrazenie. */
    val vychodHm: String get() = cas(vychod)
    val zapadHm: String get() = cas(zapad)

    /** Dlzka dna v minutach, alebo null ked chyba jeden z casov. */
    val dlzkaDnaMin: Int?
        get() {
            val v = minuty(vychod) ?: return null
            val z = minuty(zapad) ?: return null
            return if (z >= v) z - v else null
        }

    private fun cas(iso: String): String =
        if (iso.length >= 16) iso.substring(11, 16) else ""

    private fun minuty(iso: String): Int? {
        if (iso.length < 16) return null
        val h = iso.substring(11, 13).toIntOrNull() ?: return null
        val m = iso.substring(14, 16).toIntOrNull() ?: return null
        return h * 60 + m
    }
}

/**
 * Cela predpoved jedneho miesta.
 *
 * [stiahnuteMs] je cas stiahnutia (System.currentTimeMillis) — podla neho sa
 * rozhoduje, ci sa oplati stahovat znova. Bez neho by appka nevedela odlisit
 * cerstvu predpoved od tyzden starej a stahovala by pri kazdom otvoreni.
 */
data class Predpoved(
    val teplotaTeraz: Double?,
    val zrazkyTeraz: Double?,
    val kodTeraz: Int,
    /**
     * Aktualny cas V ZONE MIESTA ("2026-09-02T23:00"), tak ako ho hlasi server.
     *
     * Bez neho sa neda povedat, ktore hodiny su este pred nami — a to je pri
     * upozorneniach cely rozdiel medzi „bude mraz" a „bol mraz". **Hodinova
     * predpoved zacina polnocou dnesneho dna, nie aktualnou hodinou**, takze
     * brat jej prvu polozku ako „teraz" znamena prejst cely uz odzity den.
     * (Overene naživo 2. 9. 2026 o 23:00: prva hodina bola `2026-09-02T00:00`.)
     *
     * Systemovy cas telefonu sa na to pouzit neda: predpoved pre Sydney ma
     * vlastnu zonu a nas cas by v nej ukazoval na uplne inu hodinu.
     */
    val casTeraz: String?,
    val hodiny: List<Hodina>,
    val dni: List<Den>,
    val stiahnuteMs: Long,
) {
    val dnes: Den? get() = dni.firstOrNull()

    /**
     * Od ktorej hodiny sa ma citat dopredu.
     *
     * Ked server cas nedal (staršia odpoved v cache), padne sa na prvu hodinu
     * predpovede. Je to horsi odhad — zahrnie aj uz odzite hodiny dneska — ale
     * lepsi nez nevyhodnotit nic.
     */
    val odkedyDopredu: String get() = casTeraz ?: hodiny.firstOrNull()?.cas.orEmpty()

    /** Hodiny od `odCasu` (vratane) dalej — na vyhodnotenie upozorneni. */
    fun hodinyOd(odCasu: String): List<Hodina> = hodiny.filter { it.cas >= odCasu }

    companion object {

        /**
         * Rozparsuje odpoved Open-Meteo. Vrati null, ked odpoved nema ani
         * denny blok — vtedy nie je co ukazat a stara predpoved v cache je
         * lepsia nez prazdna obrazovka.
         *
         * Chybajuce pole nie je chyba, ale prazdny zoznam: hodinovka sa da
         * v nastaveniach vypnut kvoli datam a vtedy ju odpoved neobsahuje.
         */
        fun zJson(json: String, teraz: Long): Predpoved? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null

            val current = o.optJSONObject("current")
            val hodiny = mutableListOf<Hodina>()
            o.optJSONObject("hourly")?.let { h ->
                val casy = h.optJSONArray("time") ?: return@let
                val t = h.optJSONArray("temperature_2m")
                val z = h.optJSONArray("precipitation")
                val p = h.optJSONArray("precipitation_probability")
                val k = h.optJSONArray("weather_code")
                for (i in 0 until casy.length()) {
                    hodiny += Hodina(
                        cas = casy.optString(i),
                        teplota = t?.optDouble(i, Double.NaN) ?: Double.NaN,
                        zrazkyMm = z?.optDouble(i, 0.0) ?: 0.0,
                        pravdepodobnostZrazok = p?.optInt(i, 0) ?: 0,
                        kod = k?.optInt(i, 0) ?: 0,
                    )
                }
            }

            val dni = mutableListOf<Den>()
            o.optJSONObject("daily")?.let { d ->
                val datumy = d.optJSONArray("time") ?: return@let
                val min = d.optJSONArray("temperature_2m_min")
                val max = d.optJSONArray("temperature_2m_max")
                val z = d.optJSONArray("precipitation_sum")
                val k = d.optJSONArray("weather_code")
                val vych = d.optJSONArray("sunrise")
                val zap = d.optJSONArray("sunset")
                for (i in 0 until datumy.length()) {
                    dni += Den(
                        datum = datumy.optString(i),
                        minTeplota = min?.optDouble(i, Double.NaN) ?: Double.NaN,
                        maxTeplota = max?.optDouble(i, Double.NaN) ?: Double.NaN,
                        zrazkyMm = z?.optDouble(i, 0.0) ?: 0.0,
                        kod = k?.optInt(i, 0) ?: 0,
                        // Stara odpoved v cache tieto polia nema — vtedy ostane
                        // prazdny retazec a appka ich proste neukaze.
                        vychod = vych?.optString(i, "") ?: "",
                        zapad = zap?.optString(i, "") ?: "",
                    )
                }
            }

            if (dni.isEmpty() && hodiny.isEmpty()) return null

            return Predpoved(
                teplotaTeraz = current?.optDouble("temperature_2m", Double.NaN)
                    ?.takeIf { !it.isNaN() },
                zrazkyTeraz = current?.optDouble("precipitation", Double.NaN)
                    ?.takeIf { !it.isNaN() },
                kodTeraz = current?.optInt("weather_code", 0) ?: 0,
                casTeraz = current?.optString("time")?.ifBlank { null },
                hodiny = hodiny.filter { !it.teplota.isNaN() },
                dni = dni,
                stiahnuteMs = teraz,
            )
        }
    }
}
