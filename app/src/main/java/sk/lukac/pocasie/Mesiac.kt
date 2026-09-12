package sk.lukac.pocasie

import java.time.LocalDate

/**
 * Faza mesiaca — spln, nov a to, co je medzi nimi.
 *
 * **Preco sa to pocita a nestahuje.** Open-Meteo fazu mesiaca nevracia a kvoli
 * jednemu udaju by pribudlo druhe API, dalsi kluc a dalsie miesto, kde sa da
 * prist o siet. Pritom je to obycajna aritmetika: synodicky mesiac je
 * 29,530588 dna a staci vediet, kedy bol jeden nov.
 *
 * **Ako presne to je.** Priemerny synodicky mesiac je priemer — skutocny sa
 * kvoli drahe Mesiaca lisi az o +-6 hodin. Na otazku „kedy je spln" to bohato
 * staci (chyba je nanajvys pol dna), na zatmenie nie. Ked bude raz treba
 * presnejsie, je na to Meeusov algoritmus s poruchovymi clenmi; dovtedy by to
 * bolo sto riadkov navyse pre presnost, ktoru nikto nevyuzije.
 *
 * Zamerne bez jedineho odkazu na Android, rovnako ako `Predpoved.kt` — da sa
 * to tak testovat bez emulatora.
 */
object Mesiac {

    /** Dlzka synodickeho mesiaca v dnoch (nov -> nov). */
    const val SYNODICKY_MESIAC = 29.530588853

    /**
     * Referencny nov: 6. 1. 2000, 18:14 UTC. V juliánskych dnoch 2451550.1.
     *
     * Ako epochovy den (od 1. 1. 1970) je to 10962. deň plus 0,76 dna
     * (18:14 z 24 h), teda 10962,76.
     */
    private const val REFERENCNY_NOV_DNI = 10962.76

    /** Vek mesiaca v dnoch od posledneho novu (0 = nov, ~14,77 = spln). */
    fun vek(datum: LocalDate): Double {
        val dni = datum.toEpochDay().toDouble()
        val od = (dni - REFERENCNY_NOV_DNI) % SYNODICKY_MESIAC
        return if (od < 0) od + SYNODICKY_MESIAC else od
    }

    /**
     * Osvetlena cast kotuca, 0,0 az 1,0.
     *
     * Kosinusovy priebeh: nov = 0, spln = 1. Nie je to linearne s vekom —
     * medzi „polmesiac" a „skoro spln" pribuda svetla pomalsie, nez by sa
     * z veku zdalo.
     */
    fun osvetlenie(datum: LocalDate): Double {
        val uhol = 2 * Math.PI * vek(datum) / SYNODICKY_MESIAC
        return (1 - Math.cos(uhol)) / 2
    }

    /** Nazov fazy po slovensky. */
    fun nazov(datum: LocalDate): String {
        val v = vek(datum)
        val diel = SYNODICKY_MESIAC / 8
        // Nov a spln su „presne" jeden den, ostatne fazy su siroke pasma —
        // preto sa neberie osminovy raster, ale uzsie okno okolo tych dvoch.
        return when {
            v < 1.0 || v >= SYNODICKY_MESIAC - 1.0 -> "nov"
            Math.abs(v - SYNODICKY_MESIAC / 2) < 1.0 -> "spln"
            v < diel * 2 -> "dorastajúci kosák"
            v < diel * 3 -> "prvá štvrť"
            v < SYNODICKY_MESIAC / 2 -> "dorastajúci mesiac"
            v < diel * 6 -> "cúvajúci mesiac"
            v < diel * 7 -> "posledná štvrť"
            else -> "cúvajúci kosák"
        }
    }

    /** Emoji ku faze — do widgetu, kde je miesto na jeden znak. */
    fun znak(datum: LocalDate): String {
        val v = vek(datum)
        val diel = SYNODICKY_MESIAC / 8
        return when {
            v < 1.0 || v >= SYNODICKY_MESIAC - 1.0 -> "🌑"   // nov
            Math.abs(v - SYNODICKY_MESIAC / 2) < 1.0 -> "🌕" // spln
            v < diel * 2 -> "🌒"
            v < diel * 3 -> "🌓"
            v < SYNODICKY_MESIAC / 2 -> "🌔"
            v < diel * 6 -> "🌖"
            v < diel * 7 -> "🌗"
            else -> "🌘"
        }
    }

    /**
     * Najblizsi den, kedy nastane nov (vratane dneska).
     *
     * Hlada sa po dnoch dopredu, nie vzorcom: den je aj tak najmensia jednotka,
     * v ktorej sa to zobrazuje, a cyklus cez najviac 30 hodnot je lacnejsi nez
     * dalsi vzorec, v ktorom sa da spravit chyba.
     */
    fun najblizsiNov(od: LocalDate): LocalDate = najdi(od) { vek(it) < 1.0 }

    /** Najblizsi den splnu (vratane dneska). */
    fun najblizsiSpln(od: LocalDate): LocalDate =
        najdi(od) { Math.abs(vek(it) - SYNODICKY_MESIAC / 2) < 1.0 }

    private fun najdi(od: LocalDate, podmienka: (LocalDate) -> Boolean): LocalDate {
        var d = od
        repeat(31) {
            if (podmienka(d)) return d
            d = d.plusDays(1)
        }
        return od
    }

    /** „spln 8. 9." — kratky text pod predpoved. */
    fun najblizsiaUdalost(od: LocalDate): String {
        val nov = najblizsiNov(od)
        val spln = najblizsiSpln(od)
        val (den, meno) = if (nov <= spln) nov to "nov" else spln to "spln"
        return "$meno ${den.dayOfMonth}. ${den.monthValue}."
    }
}
