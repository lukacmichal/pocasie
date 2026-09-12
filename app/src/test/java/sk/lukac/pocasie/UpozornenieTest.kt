package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kedy sa appka ozve.
 *
 * Dve chyby sa tu daju spravit ticho a obe koncia tym, ze clovek upozornenia
 * vypne: ozvat sa na to, co uz sa stalo, a ozvat sa na to iste dokola.
 */
class UpozornenieTest {

    private val nitra = Miesto("Nitra", 48.3069, 18.0864, "Nitriansky kraj, Slovensko")
    private val kosice = Miesto("Košice", 48.7164, 21.2611, "Košický kraj, Slovensko")

    private fun hodina(cas: String, teplota: Double, mm: Double = 0.0) =
        Hodina(cas, teplota, mm, 0, 0)

    private fun predpoved(vararg hodiny: Hodina, casTeraz: String? = null) =
        Predpoved(null, null, 0, casTeraz, hodiny.toList(), emptyList(), 0L)

    // ───────────────────────────── teplota ──────────────────────────────────

    @Test
    fun `mraz sa najde v predpovedi, nie az ked mrzne`() {
        val p = predpoved(
            hodina("2026-09-03T20:00", 6.0),
            hodina("2026-09-03T23:00", 2.0),
            hodina("2026-09-04T05:00", -1.5),
        )
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        val z = Pravidla.zasah(u, nitra, p, "2026-09-03T20:00")
        assertNotNull(z)
        assertEquals("2026-09-04T05:00", z!!.hodina.cas)
    }

    @Test
    fun `minule hodiny sa preskocia`() {
        // Ozvat sa na mraz, ktory uz clovek zazil, je horsie nez mlcat.
        val p = predpoved(
            hodina("2026-09-03T05:00", -3.0),
            hodina("2026-09-03T12:00", 14.0),
        )
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        assertNull(Pravidla.zasah(u, nitra, p, "2026-09-03T12:00"))
    }

    @Test
    fun `okno hodin dopredu sa dodrzi`() {
        val p = predpoved(
            hodina("2026-09-03T12:00", 14.0),
            hodina("2026-09-03T13:00", 14.0),
            hodina("2026-09-03T14:00", -5.0),
        )
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0, hodinDopredu = 2)
        assertNull(Pravidla.zasah(u, nitra, p, "2026-09-03T12:00"))
        assertNotNull(Pravidla.zasah(u.copy(hodinDopredu = 3), nitra, p, "2026-09-03T12:00"))
    }

    @Test
    fun `horuca teplota nad hranicou`() {
        val p = predpoved(hodina("2026-09-03T14:00", 31.5))
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_NAD, hranica = 30.0)
        assertNotNull(Pravidla.zasah(u, nitra, p, "2026-09-03T00:00"))
        assertNull(Pravidla.zasah(u.copy(hranica = 32.0), nitra, p, "2026-09-03T00:00"))
    }

    // ───────────────────────────── zrážky ───────────────────────────────────

    @Test
    fun `akekolvek zrazky su az od desatiny milimetra`() {
        // Open-Meteo pri suchu vracia aj 0.05 mm — to je rosa, nie dazd,
        // a upozornenie na nu by chodilo takmer kazdy den.
        val u = Upozornenie("u1", DruhUpozornenia.ZRAZKY_AKEKOLVEK)
        assertFalse(Pravidla.splna(u, hodina("x", 10.0, 0.0)))
        assertFalse(Pravidla.splna(u, hodina("x", 10.0, 0.05)))
        assertTrue(Pravidla.splna(u, hodina("x", 10.0, 0.1)))
    }

    @Test
    fun `zrazky nad hranicou`() {
        val u = Upozornenie("u1", DruhUpozornenia.ZRAZKY_NAD, hranica = 2.0)
        assertFalse(Pravidla.splna(u, hodina("x", 10.0, 2.0)))
        assertTrue(Pravidla.splna(u, hodina("x", 10.0, 2.1)))
    }

    // ───────────────────────────── miesta ───────────────────────────────────

    @Test
    fun `pravidlo bez miesta plati pre vsetky`() {
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        assertTrue(u.jePre(nitra))
        assertTrue(u.jePre(kosice))
    }

    @Test
    fun `pravidlo s miestom plati len tam`() {
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD,
                            miestoId = nitra.id, hranica = 0.0)
        assertTrue(u.jePre(nitra))
        assertFalse(u.jePre(kosice))
    }

    @Test
    fun `vypnute pravidlo nezasahuje`() {
        val p = predpoved(hodina("2026-09-03T05:00", -5.0))
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0, zapnute = false)
        assertNull(Pravidla.zasah(u, nitra, p, "2026-09-03T00:00"))
    }

    // ───────────────────────────── kľúč ─────────────────────────────────────

    @Test
    fun `kluc obsahuje hodinu, nie cas vyhodnotenia`() {
        // Bez toho by obnova kazde tri hodiny zvonila na ten isty mraz dokola.
        val p = predpoved(hodina("2026-09-04T05:00", -2.0))
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        val prve = Pravidla.zasah(u, nitra, p, "2026-09-03T20:00")!!
        val druhe = Pravidla.zasah(u, nitra, p, "2026-09-03T23:00")!!
        assertEquals(Pravidla.kluc(prve), Pravidla.kluc(druhe))
    }

    @Test
    fun `kluc rozlisi miesta aj pravidla`() {
        val p = predpoved(hodina("2026-09-04T05:00", -2.0))
        val a = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        val b = a.copy(id = "u2")
        val zA = Pravidla.zasah(a, nitra, p, "2026-09-03T00:00")!!
        val zB = Pravidla.zasah(b, nitra, p, "2026-09-03T00:00")!!
        val zInde = Pravidla.zasah(a, kosice, p, "2026-09-03T00:00")!!
        assertTrue(Pravidla.kluc(zA) != Pravidla.kluc(zB))
        assertTrue(Pravidla.kluc(zA) != Pravidla.kluc(zInde))
    }

    // ───────────────────────── ukladanie ────────────────────────────────────

    @Test
    fun `pravidla prezijú ulozenie a nacitanie`() {
        val povodne = listOf(
            Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, nitra.id, -5.0, 12, true),
            Upozornenie("u2", DruhUpozornenia.ZRAZKY_NAD, "", 3.0, 48, false),
        )
        assertEquals(povodne, Upozornenie.zoZoznamu(Upozornenie.doZoznamu(povodne)))
    }

    @Test
    fun `pokazene pravidlo sa preskoci, zvysok ostane`() {
        val json = """[{"id":"u1","druh":"TEPLOTA_POD","hranica":0.0},
                       {"id":"u2","druh":"NEEXISTUJE"},
                       {"druh":"TEPLOTA_NAD"}]"""
        val nacitane = Upozornenie.zoZoznamu(json)
        assertEquals(1, nacitane.size)
        assertEquals("u1", nacitane[0].id)
    }

    // ─────────────────── "teraz" je čas MIESTA ──────────────────────────────

    @Test
    fun `odkedyDopredu berie cas miesta, nie prvu hodinu`() {
        // Hodinova predpoved zacina POLNOCOU dnesneho dna. Kto berie jej prvu
        // polozku ako "teraz", prejde cely uz odzity den — a appka o jedenastej
        // vecer zvoni na rannu hmlu, ktora davno presla.
        val p = predpoved(
            hodina("2026-09-02T00:00", -4.0),
            hodina("2026-09-02T23:00", 20.0),
            casTeraz = "2026-09-02T23:00",
        )
        assertEquals("2026-09-02T23:00", p.odkedyDopredu)
        val u = Upozornenie("u1", DruhUpozornenia.TEPLOTA_POD, hranica = 0.0)
        assertNull(Pravidla.zasah(u, nitra, p, p.odkedyDopredu))
    }

    @Test
    fun `bez casu miesta padne na prvu hodinu`() {
        // Starsia odpoved v cache `current.time` nema. Horsi odhad je lepsi
        // nez nevyhodnotit nic.
        val p = predpoved(hodina("2026-09-02T00:00", -4.0))
        assertEquals("2026-09-02T00:00", p.odkedyDopredu)
    }
}
