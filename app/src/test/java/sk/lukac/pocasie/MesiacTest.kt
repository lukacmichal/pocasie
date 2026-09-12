package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Faza mesiaca.
 *
 * Nekontroluje sa proti almanachu — ten by sa sem musel opisat a stal by sa
 * druhym zdrojom pravdy. Kontroluje sa, ze vypocet sedi sam so sebou: nov je
 * tam, kde ma byt vzhladom na referencny nov, spln je v polovici cyklu,
 * osvetlenie ide od nuly do jednotky a dva po sebe iduce novy delí synodicky
 * mesiac. Ked sa v konstantach niekto pomyli, padne to.
 */
class MesiacTest {

    @Test
    fun `referencny nov ma vek skoro nulu`() {
        // 6. 1. 2000 bol nov o 18:14 UTC. Vek sa pocita k polnoci, takze
        // v ten den je tesne pred novom — teda ku koncu cyklu.
        val v = Mesiac.vek(LocalDate.of(2000, 1, 6))
        assertTrue("vek bol $v", v > Mesiac.SYNODICKY_MESIAC - 1.0 || v < 1.0)
    }

    @Test
    fun `vek je vzdy v rozsahu jedneho cyklu`() {
        var d = LocalDate.of(2026, 1, 1)
        repeat(400) {
            val v = Mesiac.vek(d)
            assertTrue("vek $v mimo rozsahu pre $d", v >= 0.0 && v < Mesiac.SYNODICKY_MESIAC)
            d = d.plusDays(1)
        }
    }

    @Test
    fun `dva po sebe iduce novy deli synodicky mesiac`() {
        val prvy = Mesiac.najblizsiNov(LocalDate.of(2026, 9, 1))
        val druhy = Mesiac.najblizsiNov(prvy.plusDays(3))
        val rozdiel = druhy.toEpochDay() - prvy.toEpochDay()
        // Zaokruhlenie na cele dni: 29 alebo 30, nic ine.
        assertTrue("rozdiel bol $rozdiel dni", rozdiel in 29..30)
    }

    @Test
    fun `spln je zhruba v polovici medzi dvoma novmi`() {
        val nov = Mesiac.najblizsiNov(LocalDate.of(2026, 9, 1))
        val spln = Mesiac.najblizsiSpln(nov)
        val rozdiel = spln.toEpochDay() - nov.toEpochDay()
        assertTrue("spln prisiel $rozdiel dni po nove", rozdiel in 14..16)
    }

    @Test
    fun `osvetlenie je v nove nula a v splne jedna`() {
        val nov = Mesiac.najblizsiNov(LocalDate.of(2026, 9, 1))
        val spln = Mesiac.najblizsiSpln(LocalDate.of(2026, 9, 1))
        assertTrue(Mesiac.osvetlenie(nov) < 0.06)
        assertTrue(Mesiac.osvetlenie(spln) > 0.94)
    }

    @Test
    fun `nazov sedi s fazou`() {
        assertEquals("nov", Mesiac.nazov(Mesiac.najblizsiNov(LocalDate.of(2026, 9, 1))))
        assertEquals("spln", Mesiac.nazov(Mesiac.najblizsiSpln(LocalDate.of(2026, 9, 1))))
    }

    @Test
    fun `znak je vzdy jeden mesiacovy emoji`() {
        val povolene = setOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")
        var d = LocalDate.of(2026, 1, 1)
        repeat(60) {
            assertTrue("neznamy znak pre $d", Mesiac.znak(d) in povolene)
            d = d.plusDays(1)
        }
    }

    @Test
    fun `najblizsia udalost je bud nov alebo spln a nikdy nie z minulosti`() {
        val dnes = LocalDate.of(2026, 9, 6)
        val text = Mesiac.najblizsiaUdalost(dnes)
        assertTrue("text bol '$text'", text.startsWith("nov ") || text.startsWith("spln "))
        val nov = Mesiac.najblizsiNov(dnes)
        val spln = Mesiac.najblizsiSpln(dnes)
        assertTrue(!nov.isBefore(dnes))
        assertTrue(!spln.isBefore(dnes))
    }
}
