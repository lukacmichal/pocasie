package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dáta pre graf počasia.
 *
 * Testuje sa to, čo sa v grafe pokazí ticho: mierka zrážok (zlá mierka nakreslí
 * lejak aj z mrholenia), rozsah teplôt (zlý rozsah spraví z jedného stupňa
 * drámu alebo vytlačí čiaru mimo grafu), hranice dní a rozdiel dážď/sneh.
 */
class GrafTest {

    private fun h(cas: String, mm: Double, kod: Int = 61, teplota: Double = 12.0) =
        Hodina(cas = cas, teplota = teplota, zrazkyMm = mm,
               pravdepodobnostZrazok = 50, kod = kod)

    // ---------------------------- mierka zrážok -----------------------------

    @Test
    fun `suchy graf ma mierku do jedneho milimetra`() {
        // Nie do nuly: prázdny graf s mierkou vyzerá ako suchý týždeň,
        // graf bez mierky ako pokazené kreslenie.
        val stlpce = Graf.zHodin(listOf(h("2026-09-03T10:00", 0.0)))
        assertEquals(1.0, Graf.mierka(stlpce), 0.0001)
        assertEquals(1.0, Graf.mierka(emptyList()), 0.0001)
    }

    @Test
    fun `mierka je najblizsie pekne cislo nad maximom`() {
        fun mierkaPre(vararg mm: Double) = Graf.mierka(
            mm.mapIndexed { i, v -> Graf.Stlpec("2026-09-0${i + 1}", v, false) })

        assertEquals(0.2, mierkaPre(0.15), 0.0001)
        assertEquals(1.0, mierkaPre(0.6), 0.0001)
        assertEquals(2.0, mierkaPre(1.8), 0.0001)
        assertEquals(5.0, mierkaPre(4.2), 0.0001)
        assertEquals(20.0, mierkaPre(11.0), 0.0001)
    }

    @Test
    fun `mierka sadne presne na hodnotu, ktora uz pekna je`() {
        // 2 mm nesmie vytiahnuť os na 5 mm — stĺpec by potom končil v dvoch
        // pätinách výšky a graf by tvrdil, že "toto ešte nie je veľa".
        val stlpce = listOf(Graf.Stlpec("2026-09-03T10:00", 2.0, false))
        assertEquals(2.0, Graf.mierka(stlpce), 0.0001)
    }

    // ---------------------------- rozsah teplôt -----------------------------

    @Test
    fun `rozsah teplot je zaokruhleny von na patky`() {
        val stlpce = Graf.zHodin(listOf(
            h("2026-09-03T10:00", 0.0, teplota = 12.0),
            h("2026-09-03T11:00", 0.0, teplota = 23.0),
        ))
        assertEquals(10.0 to 30.0, Graf.rozsahTeplot(stlpce))
    }

    @Test
    fun `stredny popisok osi vyjde na cele cislo`() {
        // Rozsah 20–35 by mal stred 27,5 a os by hlásila "28" tam, kde čiara
        // ukazuje 27,5. Preto je rozsah vždy deliteľný desiatimi.
        for (teplota in listOf(3.0, 12.0, 21.5, 29.0, -7.0)) {
            val stlpce = Graf.zHodin(listOf(
                h("2026-09-03T10:00", 0.0, teplota = teplota),
                h("2026-09-03T11:00", 0.0, teplota = teplota + 9.0),
            ))
            val (dole, hore) = Graf.rozsahTeplot(stlpce)!!
            assertEquals(0.0, (hore - dole) % 10.0, 0.0001)
            assertTrue(dole < teplota && hore > teplota + 9.0)
        }
    }

    @Test
    fun `plocha teplota ma aspon pat stupnov rozsahu`() {
        // 19 až 20 °C je rovný deň. Bez minimálneho rozsahu by čiara skákala
        // cez celú výšku grafu a robila z jedného stupňa drámu.
        val stlpce = Graf.zHodin(listOf(
            h("2026-09-03T10:00", 0.0, teplota = 19.2),
            h("2026-09-03T11:00", 0.0, teplota = 19.8),
        ))
        val (dole, hore) = Graf.rozsahTeplot(stlpce)!!
        assertTrue(hore - dole >= 5.0)
        assertTrue(dole <= 19.2 && hore >= 19.8)
    }

    @Test
    fun `teplota na hrane rozsahu ho posunie`() {
        // Čiara presne na hornej hrane splýva s rámčekom grafu.
        val stlpce = Graf.zHodin(listOf(h("2026-09-03T10:00", 0.0, teplota = 25.0)))
        val (dole, hore) = Graf.rozsahTeplot(stlpce)!!
        assertTrue(hore > 25.0)
        assertTrue(dole < 25.0)
    }

    @Test
    fun `bez teplot nie je rozsah`() {
        // Stará odpoveď v cache môže mať zrážky bez teplôt; vtedy sa čiara
        // nekreslí a stĺpce dostanú celú výšku.
        val stlpce = listOf(Graf.Stlpec("2026-09-03", 1.0, false))
        assertNull(Graf.rozsahTeplot(stlpce))
    }

    // -------------------------------- dni -----------------------------------

    @Test
    fun `hranica dna je tam, kde sa meni datum`() {
        val stlpce = Graf.zHodin(listOf(
            h("2026-09-03T22:00", 0.0),
            h("2026-09-03T23:00", 0.0),
            h("2026-09-04T00:00", 0.0),
            h("2026-09-04T01:00", 0.0),
        ))
        assertEquals(listOf(2), Graf.hraniceDni(stlpce))
    }

    @Test
    fun `prvy stlpec hranicou nie je`() {
        // Čiara na ľavom okraji je len rámček navyše.
        val stlpce = Graf.zHodin(listOf(h("2026-09-03T22:00", 0.0)))
        assertTrue(Graf.hraniceDni(stlpce).isEmpty())
        assertEquals(listOf(0 until 1), Graf.useky(stlpce))
    }

    @Test
    fun `useky pokryvaju vsetky stlpce`() {
        val stlpce = Graf.zHodin(listOf(
            h("2026-09-03T23:00", 0.0),
            h("2026-09-04T00:00", 0.0),
            h("2026-09-04T01:00", 0.0),
            h("2026-09-05T00:00", 0.0),
        ))
        val useky = Graf.useky(stlpce)
        assertEquals(listOf(0 until 1, 1 until 3, 3 until 4), useky)
        assertEquals(stlpce.size, useky.sumOf { it.count() })
    }

    @Test
    fun `prazdny zoznam nema ziadny usek`() {
        assertTrue(Graf.useky(emptyList()).isEmpty())
    }

    // ------------------------------ rozsah grafu -----------------------------

    @Test
    fun `graf berie len tolko hodin, kolko sa pyta`() {
        val hodiny = (0..47).map { h("2026-09-03T%02d:00".format(it % 24), 0.0) }
        assertEquals(24, Graf.zHodin(hodiny, 24).size)
        assertEquals(48, Graf.zHodin(hodiny, 168).size)
        // Kratšia predpoveď než rozsah nie je chyba — nakreslí sa, čo je.
        assertEquals(48, Graf.zHodin(hodiny, Int.MAX_VALUE).size)
    }

    @Test
    fun `popis rozsahu sklonuje dni`() {
        assertEquals("24 h", Graf.popisRozsahu(24))
        assertEquals("2 dni", Graf.popisRozsahu(48))
        assertEquals("4 dni", Graf.popisRozsahu(96))
        assertEquals("5 dní", Graf.popisRozsahu(120))
        assertEquals("7 dní", Graf.popisRozsahu(168))
    }

    @Test
    fun `widget kresli prvy rozsah zo zoznamu`() {
        assertEquals(Graf.HODIN_WIDGET, Graf.ROZSAHY_HODIN.first())
    }

    // --------------------------- hustota mriežky -----------------------------

    @Test
    fun `krok hodin sa riedi so sirkou stlpca`() {
        // Popisok „00" aj s medzerou ~ 60 px. 24 h na ~900 px = 37 px na hodinu.
        assertEquals(2, Graf.krokHodin(37f, 60f))
        // 3 dni: 12 px na hodinu → po šiestich.
        assertEquals(6, Graf.krokHodin(12f, 60f))
        // Týždeň: 5 px na hodinu → po dvanástich, nie nič.
        assertEquals(12, Graf.krokHodin(5.4f, 60f))
        // Široký tablet: každá hodina.
        assertEquals(1, Graf.krokHodin(80f, 60f))
    }

    @Test
    fun `ked sa nezmesti ani 12 hodin, hodiny sa nepisu`() {
        assertNull(Graf.krokHodin(2f, 60f))
    }

    @Test
    fun `kroky hodin delia den`() {
        // Inak by popisky v utorok padli na iné hodiny než v pondelok.
        for (k in Graf.KROKY_HODIN) assertEquals(0, 24 % k)
    }

    @Test
    fun `krok teplot sa riedi s vyskou grafu`() {
        val rozsah = 10.0 to 30.0
        assertEquals(2.0, Graf.krokTeplot(rozsah, 400f, 35f), 0.0001)
        assertEquals(5.0, Graf.krokTeplot(rozsah, 200f, 35f), 0.0001)
        assertEquals(10.0, Graf.krokTeplot(rozsah, 80f, 35f), 0.0001)
        // Nižší widget než na krok 10 → pôvodné tri čiary.
        assertEquals(10.0, Graf.krokTeplot(10.0 to 30.0, 40f, 35f), 0.0001)
        assertEquals(20.0, Graf.krokTeplot(-10.0 to 30.0, 40f, 35f), 0.0001)
    }

    @Test
    fun `ciary teplot su na nasobkoch kroku vnutri rozsahu`() {
        assertEquals(listOf(6.0, 8.0, 10.0, 12.0, 14.0), Graf.ciaryTeplot(5.0 to 15.0, 2.0))
        assertEquals(listOf(-10.0, -5.0, 0.0, 5.0), Graf.ciaryTeplot(-10.0 to 5.0, 5.0))
    }

    @Test
    fun `kroky teplot delia rozsah z rozsahTeplot`() {
        // Rozsah je vždy deliteľný desiatimi → horná aj spodná hrana pri
        // kroku 1, 5, 10 padne na čiaru.
        for (k in Graf.KROKY_TEPLOT) assertEquals(0.0, 10.0 % k, 0.0001)
    }

    // ---------------------------- dážď a sneh -------------------------------

    @Test
    fun `sneh sa pozna podla WMO kodu`() {
        assertTrue(Graf.jeSneh(71))
        assertTrue(Graf.jeSneh(85))
        assertFalse(Graf.jeSneh(61))
        // Mrznúci dážď (66, 67) padá ako voda, nie ako sneh.
        assertFalse(Graf.jeSneh(66))
        assertFalse(Graf.jeSneh(0))
    }

    @Test
    fun `stlpec z hodiny vie, ze je hodinovy`() {
        val hodinovy = Graf.zHodin(listOf(h("2026-09-03T14:00", 1.0))).first()
        assertTrue(hodinovy.jeHodinovy)
        assertEquals("14:00", hodinovy.hodina)
        assertEquals("2026-09-03", hodinovy.den)

        val denny = Graf.zDni(listOf(Den("2026-09-03", 5.0, 15.0, 3.2, 71))).first()
        assertFalse(denny.jeHodinovy)
        assertEquals("", denny.hodina)
        assertTrue(denny.sneh)
        // Denný stĺpec nesie MAXIMÁLNU teplotu dňa — z min/max sa dá do jednej
        // čiary dať len jedno a "ako bude cez deň teplo" je tá otázka.
        assertEquals(15.0, denny.teplota, 0.0001)
    }

    @Test
    fun `zaporne zrazky sa nekreslia pod nulu`() {
        // Open-Meteo zápornú hodnotu neposiela, ale stĺpec pod osou by graf
        // rozbil natoľko, že sa to oplatí ošetriť na vstupe.
        val stlpce = Graf.zHodin(listOf(h("2026-09-03T10:00", -1.0)))
        assertEquals(0.0, stlpce.first().mm, 0.0001)
    }

    @Test
    fun `spolu scitava vsetky stlpce`() {
        val stlpce = Graf.zHodin(listOf(
            h("2026-09-03T10:00", 0.4),
            h("2026-09-03T11:00", 1.1),
            h("2026-09-04T10:00", 0.5),
        ))
        assertEquals(2.0, Graf.spolu(stlpce), 0.0001)
    }
}
