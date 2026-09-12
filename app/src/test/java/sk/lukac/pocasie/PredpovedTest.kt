package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsovanie odpovede Open-Meteo.
 *
 * Testuje sa hlavne to, co sa pokazi ticho: chybajuca hodinovka (da sa vypnut
 * v Nastaveniach kvoli datam) nesmie vyzerat ako chyba, a pokazena odpoved
 * nesmie prepisat poslednu funkcnu predpoved v cache.
 */
class PredpovedTest {

    private val plna = """
    {
      "current": {"time": "2026-09-03T12:00", "temperature_2m": 18.3,
                  "precipitation": 0.0, "weather_code": 3},
      "hourly": {
        "time": ["2026-09-03T12:00","2026-09-03T13:00","2026-09-03T14:00"],
        "temperature_2m": [18.3, 19.1, 20.4],
        "precipitation": [0.0, 0.2, 1.8],
        "precipitation_probability": [0, 30, 70],
        "weather_code": [3, 61, 63]
      },
      "daily": {
        "time": ["2026-09-03","2026-09-04"],
        "temperature_2m_max": [21.0, 17.5],
        "temperature_2m_min": [11.2, 9.9],
        "precipitation_sum": [2.0, 0.0],
        "weather_code": [61, 3]
      }
    }
    """.trimIndent()

    @Test
    fun `rozparsuje aktualny stav, hodiny aj dni`() {
        val p = Predpoved.zJson(plna, 1000L)!!
        assertEquals(18.3, p.teplotaTeraz!!, 0.001)
        assertEquals(3, p.kodTeraz)
        assertEquals(3, p.hodiny.size)
        assertEquals(2, p.dni.size)
        assertEquals(1000L, p.stiahnuteMs)
        assertEquals("2026-09-03T12:00", p.casTeraz)
    }

    @Test
    fun `hodina pozna svoj den a cas`() {
        val h = Predpoved.zJson(plna, 0)!!.hodiny[2]
        assertEquals("2026-09-03", h.den)
        assertEquals("14:00", h.hodina)
        assertEquals(1.8, h.zrazkyMm, 0.001)
        assertEquals(70, h.pravdepodobnostZrazok)
    }

    @Test
    fun `dnes je prvy den, nie najteplejsi`() {
        val p = Predpoved.zJson(plna, 0)!!
        assertEquals("2026-09-03", p.dnes!!.datum)
        assertEquals(11.2, p.dnes!!.minTeplota, 0.001)
    }

    @Test
    fun `bez hodinovky to nie je chyba, len prazdny zoznam`() {
        // Hodinovku sa da vypnut kvoli datam. Keby to parser bral ako chybu,
        // appka by po tom nastaveni prestala ukazovat cokolvek.
        val bezHodin = """
        {"daily": {"time": ["2026-09-03"], "temperature_2m_max": [21.0],
         "temperature_2m_min": [11.2], "precipitation_sum": [2.0],
         "weather_code": [61]}}
        """.trimIndent()
        val p = Predpoved.zJson(bezHodin, 0)!!
        assertTrue(p.hodiny.isEmpty())
        assertEquals(1, p.dni.size)
    }

    @Test
    fun `pokazena odpoved vrati null`() {
        // Volajuci to musi vediet PRED zapisom do cache — inak by pokazena
        // odpoved prepisala poslednu funkcnu predpoved.
        assertNull(Predpoved.zJson("toto nie je json", 0))
        assertNull(Predpoved.zJson("{}", 0))
        assertNull(Predpoved.zJson("""{"current":{"temperature_2m":5.0}}""", 0))
    }

    @Test
    fun `hodinyOd zahodi minulost`() {
        val p = Predpoved.zJson(plna, 0)!!
        assertEquals(2, p.hodinyOd("2026-09-03T13:00").size)
        assertEquals(0, p.hodinyOd("2026-09-04T00:00").size)
    }

    @Test
    fun `hodina bez teploty vypadne`() {
        // NaN v teplote by na obrazovke skoncil ako "NaN °C". Rovnaka chyba
        // ako SKORE-1 v akcie-obchodovanie, len na inom mieste.
        val diera = """
        {"hourly": {"time": ["2026-09-03T12:00","2026-09-03T13:00"],
         "temperature_2m": [18.3, null], "precipitation": [0.0, 0.0],
         "precipitation_probability": [0, 0], "weather_code": [3, 3]},
         "daily": {"time": ["2026-09-03"], "temperature_2m_max": [21.0],
         "temperature_2m_min": [11.2], "precipitation_sum": [0.0],
         "weather_code": [3]}}
        """.trimIndent()
        assertEquals(1, Predpoved.zJson(diera, 0)!!.hodiny.size)
    }

    // ─────────────────────── vychod a zapad slnka ───────────────────────────

    @Test
    fun `vychod a zapad sa precitaju z dennej predpovede`() {
        val json = """
            {"daily":{"time":["2026-09-07"],"weather_code":[1],
             "temperature_2m_max":[22.0],"temperature_2m_min":[11.0],
             "precipitation_sum":[0.0],
             "sunrise":["2026-09-07T06:24"],"sunset":["2026-09-07T19:31"]}}
        """.trimIndent()
        val p = Predpoved.zJson(json, 0L)!!
        assertEquals("06:24", p.dni[0].vychodHm)
        assertEquals("19:31", p.dni[0].zapadHm)
        assertEquals(13 * 60 + 7, p.dni[0].dlzkaDnaMin)
    }

    @Test
    fun `stara odpoved bez vychodu appku nezhodi`() {
        // Presne to, co lezi v cache z predchadzajucej verzie appky: denna
        // predpoved bez `sunrise`/`sunset`. Nesmie z toho byt vynimka ani
        // vymysleny cas — len prazdno.
        val json = """
            {"daily":{"time":["2026-09-07"],"weather_code":[1],
             "temperature_2m_max":[22.0],"temperature_2m_min":[11.0],
             "precipitation_sum":[0.0]}}
        """.trimIndent()
        val den = Predpoved.zJson(json, 0L)!!.dni[0]
        assertEquals("", den.vychodHm)
        assertEquals("", den.zapadHm)
        assertNull(den.dlzkaDnaMin)
    }

    @Test
    fun `polnocne slnko - zapad pred vychodom nedava dlzku dna`() {
        // Za polarnym kruhom vracia Open-Meteo casy, z ktorych rozdiel nedava
        // zmysel. Radsej nic nez zaporna dlzka dna.
        val den = Den("2026-06-21", 5.0, 12.0, 0.0, 1,
                      vychod = "2026-06-21T23:50", zapad = "2026-06-21T00:40")
        assertNull(den.dlzkaDnaMin)
    }
}
