package sk.lukac.pocasie

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Klient predpovede — Open-Meteo.
 *
 * **Preco Open-Meteo:** bez kluca, bez registracie, bez limitu, ktory by domaca
 * appka dosiahla. Ziadne tajomstvo v APK, ziadny ucet, ktory raz vyprsi.
 *
 * **Minimalizacia dat je hlavne v tomto subore** (zadanie z 2. 9. 2026 — plati
 * pre vsetky appky). Robi sa styrmi vecami naraz:
 *
 * 1. **Pyta sa len tie polia, ktore appka naozaj kresli.** Predvolena odpoved
 *    Open-Meteo ma desiatky premennych; tychto sest je vsetko, co appka pouzije.
 * 2. **Pocet dni je nastavitelny** (`dni`). Tri dni su zhruba polovica prenosu
 *    oproti siedmim.
 * 3. **Hodinovka sa da vypnut** (`hodinovka=false`). Je to zhruba dve tretiny
 *    odpovede — 168 hodnot na premennu proti siedmim.
 * 4. **`timeformat=unixtime` sa ZAMERNE nepouziva.** Bolo by to kratsie o par
 *    stovak bajtov, ale cas by sa musel prepocitat do zony miesta a to je
 *    presne ten druh vypoctu, ktory sa raz za rok pokazi pri zmene casu.
 *    Par sto bajtov za to nestoji.
 *
 * Namerane 2. 9. 2026 (Nitra), po drote teda po gzipe:
 *
 *     7 dni + hodinovka   1 337 B   (6 679 B nekomprimovane)
 *     3 dni bez hodinovky   361 B     (753 B nekomprimovane)
 *
 * Pri styroch miestach a obnove kazde 3 hodiny je to ~1,3 MB mesacne
 * s hodinovkou a ~0,35 MB bez nej. Pocitadlo v Nastaveniach rata
 * NEKOMPRIMOVANU velkost — je to horny odhad, nie prikraslenie.
 */
object OpenMeteo {

    private const val PREDPOVED = "https://api.open-meteo.com/v1/forecast"
    private const val GEOKODOVANIE = "https://geocoding-api.open-meteo.com/v1/search"

    /** Kolko bajtov sa doteraz prenieslo — cita to obrazovka Nastaveni. */
    @Volatile
    var poslednyPrenosBajtov: Int = 0
        private set

    class ChybaSiete(sprava: String) : Exception(sprava)

    /**
     * Stiahne predpoved pre miesto — surovy JSON tak, ako prisiel.
     *
     * @param dni kolko dni dopredu (1-16)
     * @param hodinovka stiahnut aj hodinovu predpoved
     */
    fun predpovedJson(miesto: Miesto, dni: Int, hodinovka: Boolean): String {
        val premenne = buildString {
            append("&current=temperature_2m,precipitation,weather_code")
            // `sunrise`/`sunset` su v tom istom dennom volani, takze nic navyse
            // nestoja — ziadny dalsi dopyt ani bajt navyse za polozku.
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,")
            append("precipitation_sum,sunrise,sunset")
            if (hodinovka) {
                append("&hourly=temperature_2m,precipitation,precipitation_probability,weather_code")
            }
        }
        val url = "$PREDPOVED?latitude=${miesto.lat}&longitude=${miesto.lon}" +
            premenne +
            "&forecast_days=${dni.coerceIn(1, 16)}" +
            "&timezone=auto"
        // Vracia sa SUROVA odpoved, nie rozparsovany model: do cache patri to,
        // co prislo zo servera. Vlastny format by znamenal druhy parser a druhe
        // miesto, kde sa da spravit chyba.
        return stiahni(url)
    }

    /**
     * Najde miesta podla nazvu. Prazdny vyraz nehlada — setri to jeden dopyt
     * pri kazdom zmazani pismena z pola.
     */
    fun najdiMiesta(vyraz: String, jazyk: String = "sk"): List<Miesto> {
        val q = vyraz.trim()
        if (q.length < 2) return emptyList()
        val url = "$GEOKODOVANIE?name=${URLEncoder.encode(q, "UTF-8")}" +
            "&count=8&language=$jazyk&format=json"
        return Miesto.zGeokodovania(stiahni(url))
    }

    /**
     * Jeden GET. Bez kniznice — je to jedine, co appka od siete potrebuje,
     * a OkHttp by do APK pridal stovky kilobajtov kvoli dvom volaniam.
     */
    private fun stiahni(url: String): String {
        val spojenie = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            // gzip si HttpURLConnection vypyta aj sam, ale ked sa hlavicka uvedie
            // rucne, musi sa aj rucne rozbalit. Preto sa neuvadza — transparentne
            // gzip funguje a odpoved je aj tak rádovo mensia.
            setRequestProperty("Accept", "application/json")
        }
        try {
            val kod = spojenie.responseCode
            if (kod !in 200..299) {
                throw ChybaSiete("HTTP $kod")
            }
            val telo = spojenie.inputStream.bufferedReader().use { it.readText() }
            poslednyPrenosBajtov = telo.toByteArray().size
            return telo
        } catch (e: ChybaSiete) {
            throw e
        } catch (e: Exception) {
            throw ChybaSiete(e.message ?: e.javaClass.simpleName)
        } finally {
            spojenie.disconnect()
        }
    }
}
