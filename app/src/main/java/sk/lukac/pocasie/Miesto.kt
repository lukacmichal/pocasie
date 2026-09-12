package sk.lukac.pocasie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Jedno sledovane miesto.
 *
 * Suradnice sa drzia zaokruhlene na styri desatinne miesta (~11 m). Presnejsie
 * to nema zmysel — predpovedny model ma bunku v kilometroch — a kratsi zapis
 * znamena kratsiu URL pri kazdom stahovani. Zaroven je to menej presna poloha,
 * nez akou by sa dal identifikovat dom.
 *
 * [id] je odvodene od suradnic, nie nahodne: to iste mesto pridane dvakrat ma
 * rovnake id a v zozname sa neobjavi dvakrat.
 */
data class Miesto(
    val nazov: String,
    val lat: Double,
    val lon: Double,
    /** Krajina/kraj do druheho riadku — "Nitra" je v SR aj v Grecku. */
    val popis: String = "",
) {
    val id: String get() = "%.4f,%.4f".format(java.util.Locale.US, lat, lon)

    fun doJson(): JSONObject = JSONObject().apply {
        put("nazov", nazov)
        put("lat", lat)
        put("lon", lon)
        put("popis", popis)
    }

    companion object {
        fun zJson(o: JSONObject): Miesto? {
            val nazov = o.optString("nazov").ifBlank { return null }
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            return Miesto(nazov, lat, lon, o.optString("popis"))
        }

        fun zoZoznamu(json: String): List<Miesto> = runCatching {
            val pole = JSONArray(json)
            (0 until pole.length()).mapNotNull { zJson(pole.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        fun doZoznamu(zoznam: List<Miesto>): String =
            JSONArray().apply { zoznam.forEach { put(it.doJson()) } }.toString()

        /**
         * Rozparsuje odpoved geokodovania Open-Meteo.
         *
         * Duplicity sa nezahadzuju: "Nitra, Nitriansky kraj, Slovensko" a
         * "Nitra, Kréta, Grécko" su dve rozne miesta a rozdiel medzi nimi je
         * prave ten popis. Preto ho ponuka vzdy ukazuje.
         */
        fun zGeokodovania(json: String): List<Miesto> = runCatching {
            val vysledky = JSONObject(json).optJSONArray("results") ?: return emptyList()
            (0 until vysledky.length()).mapNotNull { i ->
                val o = vysledky.getJSONObject(i)
                val nazov = o.optString("name").ifBlank { return@mapNotNull null }
                val lat = o.optDouble("latitude", Double.NaN)
                val lon = o.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val popis = listOfNotNull(
                    o.optString("admin1").ifBlank { null },
                    o.optString("country").ifBlank { null },
                ).joinToString(", ")
                Miesto(nazov, lat, lon, popis)
            }
        }.getOrDefault(emptyList())
    }
}
