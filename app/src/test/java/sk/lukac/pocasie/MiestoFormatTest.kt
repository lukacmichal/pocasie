package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Miesta, formatovanie a cerstvost cache — drobnosti, ktore vidno na kazdom riadku. */
class MiestoFormatTest {

    // ───────────────────────────── miesta ───────────────────────────────────

    @Test
    fun `id je odvodene od suradnic, nie nahodne`() {
        // To iste mesto pridane dvakrat sa nesmie objavit v zozname dvakrat.
        val a = Miesto("Nitra", 48.30690, 18.08640)
        val b = Miesto("Nitra (znova)", 48.30690, 18.08640)
        assertEquals(a.id, b.id)
    }

    @Test
    fun `id zaokruhluje na styri desatinne miesta`() {
        assertEquals("48.3069,18.0864", Miesto("Nitra", 48.30691234, 18.08643210).id)
    }

    @Test
    fun `zaporne suradnice maju vlastne id`() {
        val juh = Miesto("Sydney", -33.8688, 151.2093)
        assertEquals("-33.8688,151.2093", juh.id)
    }

    @Test
    fun `geokodovanie vrati nazov aj popis`() {
        val json = """
        {"results":[
          {"name":"Nitra","latitude":48.30763,"longitude":18.08453,
           "admin1":"Nitriansky kraj","country":"Slovensko"},
          {"name":"Nitra","latitude":35.2,"longitude":25.1,"country":"Grécko"}
        ]}
        """.trimIndent()
        val najdene = Miesto.zGeokodovania(json)
        assertEquals(2, najdene.size)
        // Popis je jedine, cim sa dve rovnomenne mesta odlisia — musi tam byt.
        assertEquals("Nitriansky kraj, Slovensko", najdene[0].popis)
        assertEquals("Grécko", najdene[1].popis)
    }

    @Test
    fun `prazdna odpoved geokodovania nie je pad`() {
        assertTrue(Miesto.zGeokodovania("""{"generationtime_ms":0.1}""").isEmpty())
        assertTrue(Miesto.zGeokodovania("nezmysel").isEmpty())
    }

    @Test
    fun `miesta prezijú ulozenie a nacitanie`() {
        val povodne = listOf(
            Miesto("Nitra", 48.3069, 18.0864, "Nitriansky kraj, Slovensko"),
            Miesto("Košice", 48.7164, 21.2611, ""),
        )
        assertEquals(povodne, Miesto.zoZoznamu(Miesto.doZoznamu(povodne)))
    }

    // ──────────────────────────── formát ────────────────────────────────────

    @Test
    fun `teplota je na cele stupne`() {
        assertEquals("18", Format.teplota(18.3))
        assertEquals("-2", Format.teplota(-1.5))
        assertEquals("–", Format.teplota(null))
        assertEquals("–", Format.teplota(Double.NaN))
    }

    @Test
    fun `zrazky pod desat milimetrov maju desatinu`() {
        // Rozdiel medzi 0,2 a 2 mm je rozdiel medzi mrholenim a dazdom.
        assertEquals("0", Format.zrazky(0.0))
        // Desatinna ciarka, nie bodka: appka je po slovensky a "0.2 mm"
        // v slovenskej vete je preklep, nie cislo.
        assertEquals("0,2", Format.zrazky(0.2))
        assertEquals("9,9", Format.zrazky(9.9))
        assertEquals("12", Format.zrazky(12.4))
    }

    @Test
    fun `vek udaja sa zaokruhluje nadol`() {
        // "pred 2 h" pri 2 h 50 min je poctivejsie nez "pred 3 h": data su
        // starsie, nez sa zda, nie mladsie.
        assertEquals("teraz", Format.predAko(30_000))
        assertEquals("pred 5 min", Format.predAko(5 * 60_000L))
        assertEquals("pred 2 h", Format.predAko((2 * 60 + 50) * 60_000L))
        assertEquals("pred 3 d", Format.predAko(3 * 24 * 3600_000L))
    }

    @Test
    fun `datum sa skrati na den a mesiac`() {
        assertEquals("3.9.", Format.denKratko("2026-09-03"))
        assertEquals("12.11.", Format.denKratko("2026-11-12"))
    }

    @Test
    fun `pocasie slovom rozlisi dazd od snehu`() {
        assertEquals("jasno", Format.popisPocasia(0))
        assertEquals("dážď", Format.popisPocasia(63))
        assertEquals("sneženie", Format.popisPocasia(73))
        assertEquals("búrka", Format.popisPocasia(95))
        assertEquals("", Format.popisPocasia(999))
    }

    // ─────────────────────────── čerstvosť ──────────────────────────────────

    @Test
    fun `cerstva predpoved sa nestahuje znova`() {
        val teraz = 1_000_000_000L
        val cerstva = Predpoved(null, null, 0, null, emptyList(), emptyList(), teraz - 60_000)
        assertTrue(Ulozisko.jeCerstva(cerstva, obnovaHodin = 3, teraz = teraz))
    }

    @Test
    fun `stara predpoved sa stiahne`() {
        val teraz = 1_000_000_000L
        val stara = Predpoved(null, null, 0, null, emptyList(), emptyList(), teraz - 5 * 3600_000L)
        assertFalse(Ulozisko.jeCerstva(stara, obnovaHodin = 3, teraz = teraz))
    }

    @Test
    fun `prah nikdy neklesne pod pol hodinu`() {
        // Pri obnove kazdu hodinu by polovica bola 30 min — a kratsi prah by
        // znamenal, ze rucne potiahnutie zoznamu stiahne to iste znova.
        val teraz = 1_000_000_000L
        val p = Predpoved(null, null, 0, null, emptyList(), emptyList(), teraz - 20 * 60_000L)
        assertTrue(Ulozisko.jeCerstva(p, obnovaHodin = 1, teraz = teraz))
    }

    @Test
    fun `ziadna predpoved nie je cerstva`() {
        assertFalse(Ulozisko.jeCerstva(null, 3, 0L))
    }
}
