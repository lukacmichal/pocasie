package sk.lukac.pocasie

/**
 * Data pre graf pocasia — ciste cisla, bez Androidu, teda testovatelne.
 *
 * Kreslenie je v [GrafKresba] (spolocne pre appku aj widget), tu je len to,
 * co sa da pokazit ticho: vyber bodov, mierky oboch osi a hranice dni.
 *
 * **Preco jeden graf a nie dva.** Teplota a zrazky sa citaju NARAZ: otazka
 * nie je „ako bude teplo" ani „ci bude prsat", ale „ako sa oblect a ci brat
 * dazdnik". Dva grafy pod sebou nutia oko skakat hore-dole a hladat, ktora
 * hodina je ktora. Preto su v jednom: **stlpce su zrazky, ciara je teplota**,
 * kazda s vlastnou mierkou (mm vpravo, °C vlavo).
 *
 * **Preco stlpce a nie ciara pre zrazky.** Zrazky nie su spojita velicina ako
 * teplota: medzi dvoma dazdami je nula, nie plynuly prechod. Ciara by medzi
 * dvoma prehankami nakreslila dazd, ktory nebude.
 *
 * Do 3. 9. 2026 sa tento subor volal `ZrazkyGraf` a teplotu nekreslil.
 */
object Graf {

    /**
     * Jeden bod grafu.
     *
     * [cas] je "2026-09-03T14:00" pri hodinovom grafe a "2026-09-03" pri
     * dennom — z dlzky sa da poznat, o ktory ide, a nemusi sa to nosit
     * v druhom poli. [teplota] je `NaN`, ked ju predpoved nema.
     */
    data class Stlpec(
        val cas: String,
        val mm: Double,
        val sneh: Boolean,
        val teplota: Double = Double.NaN,
    ) {
        val den: String get() = cas.take(10)
        val hodina: String get() = if (cas.length >= 16) cas.substring(11, 16) else ""
        val jeHodinovy: Boolean get() = cas.length >= 16
        val maTeplotu: Boolean get() = !teplota.isNaN()
    }

    /**
     * Snezi pri tomto WMO kode?
     *
     * Rozdiel dazd/sneh je jediny, kvoli ktoremu ma zmysel farbit stlpce —
     * dva milimetre vody a dva milimetre snehu su rovnake cislo a uplne iny
     * den. (Mrznuci dazd, kody 66 a 67, je dazd: padá ako voda.)
     */
    fun jeSneh(kod: Int): Boolean = kod in intArrayOf(71, 73, 75, 77, 85, 86)

    /** Hodiny od „teraz" dalej; [hodin] je strop, nie poziadavka. */
    fun zHodin(hodiny: List<Hodina>, hodin: Int = Int.MAX_VALUE): List<Stlpec> =
        hodiny.take(hodin.coerceAtLeast(1)).map {
            Stlpec(it.cas, it.zrazkyMm.coerceAtLeast(0.0), jeSneh(it.kod), it.teplota)
        }

    /**
     * Denne uhrny — nahradnik, ked je hodinovka v Nastaveniach vypnuta.
     *
     * Teplota je denne MAXIMUM: z dvoch cisel (min/max) sa da do jednej ciary
     * dat len jedno a „ako bude cez den teplo" je to, na co sa clovek pyta.
     */
    fun zDni(dni: List<Den>, poctuDni: Int = Int.MAX_VALUE): List<Stlpec> =
        dni.take(poctuDni.coerceAtLeast(1)).map {
            Stlpec(it.datum, it.zrazkyMm.coerceAtLeast(0.0), jeSneh(it.kod), it.maxTeplota)
        }

    /**
     * Horna hranica osi zrazok — najblizsie „pekne" cislo nad najvacsim
     * stlpcom.
     *
     * Preco nie presne maximum: os by potom mala popisky typu „1,7 / 3,4 / 5,1"
     * a z grafu by sa nedalo odhadnut nic bez citania cisel. Rad 1 / 2 / 5
     * drzi popisky na hodnotach, ktore ma clovek v hlave.
     *
     * Prazdny (alebo cely nulovy) graf ma os do 1 mm, nie do nuly: suchy tyzden
     * ma vyzerat ako prazdny graf s mierkou, nie ako pokazene kreslenie.
     */
    fun mierka(stlpce: List<Stlpec>): Double {
        val max = stlpce.maxOfOrNull { it.mm } ?: 0.0
        if (max <= 0.0) return 1.0
        var jednotka = 0.1
        while (jednotka < 10_000) {
            for (nasobok in doubleArrayOf(1.0, 2.0, 5.0)) {
                val kandidat = jednotka * nasobok
                if (max <= kandidat + 1e-9) return kandidat
            }
            jednotka *= 10
        }
        return max
    }

    /**
     * Rozsah teplotnej osi — zaokruhleny von na cele patky.
     *
     * Preco patky: os s popiskami „9,3 / 18,1 / 26,9" sa necita, os
     * „5 / 15 / 25" ano. Zaokruhluje sa VON, takze ciara nikdy nevyjde
     * z grafu, a rozsah ma vzdy aspon 5 °C — pri dni, kde je 19 az 20 °C, by
     * inak ciara skakala cez celu vysku a robila z jedneho stupna drámu.
     *
     * Vrati null, ked v grafe nie je ani jedna teplota (stara odpoved
     * v cache) — vtedy sa ciara nekresli a stlpce dostanu celu vysku.
     */
    fun rozsahTeplot(stlpce: List<Stlpec>): Pair<Double, Double>? {
        val teploty = stlpce.filter { it.maTeplotu }.map { it.teplota }
        if (teploty.isEmpty()) return null
        var dole = Math.floor(teploty.min() / 5.0) * 5.0
        var hore = Math.ceil(teploty.max() / 5.0) * 5.0
        if (hore - dole < 5.0) {
            hore = dole + 5.0
        }
        // Ciara presne na hornej hrane splyva s ramcekom grafu.
        if (teploty.max() >= hore - 0.5) hore += 5.0
        if (teploty.min() <= dole + 0.5) dole -= 5.0
        // Rozsah delitelny desiatimi = STREDNY popisok osi vyjde na cele
        // cislo. Pri rozsahu 20-35 je stred 27,5 a os potom hlasi "28" tam,
        // kde ciara ukazuje 27,5 — mensia lož, ale zbytocna.
        if (((hore - dole) / 5.0).toInt() % 2 != 0) hore += 5.0
        return dole to hore
    }

    // ----------------------------- hustota mriezky ----------------------------

    /**
     * Kroky, po ktorych sa pod grafom pisu hodiny (a kreslia zvisle ciary).
     *
     * Vsetko su delitele 24, takze popisky padnu kazdy den na tie iste hodiny —
     * „00 06 12 18" v pondelok aj v utorok, nie „00 05 10 15 20 01 …".
     */
    val KROKY_HODIN = intArrayOf(1, 2, 3, 6, 12)

    /**
     * Najhustejsi krok hodin, pri ktorom sa popisky este neprekryju.
     *
     * Do 12. 9. 2026 bol krok pevnych sest hodin: na 24 h to boli styri cisla
     * na celu sirku displeja a pri siedmich dnoch sa hodiny nepisali vobec.
     * Teraz rozhoduje, kolko pixelov ma jedna hodina — 24 h vyjde po dvoch
     * hodinach, 3 dni po sest, tyzden po dvanast.
     *
     * @param pxNaHodinu sirka jedneho hodinoveho stlpca
     * @param sirkaPopisu kolko miesta potrebuje popisok aj s medzerou
     * @return null, ked sa nezmesti ani krok 12 h — vtedy sa hodiny nepisu
     */
    fun krokHodin(pxNaHodinu: Float, sirkaPopisu: Float): Int? =
        KROKY_HODIN.firstOrNull { it * pxNaHodinu >= sirkaPopisu }

    /** Kroky teplotnej mriezky; vsetky delia 10, teda aj rozsah z [rozsahTeplot]. */
    val KROKY_TEPLOT = doubleArrayOf(1.0, 2.0, 5.0, 10.0)

    /**
     * Najhustejsi krok vodorovnych ciar teploty, pri ktorom su popisky
     * od seba aspon [minOdstupPx].
     *
     * Do 12. 9. 2026 boli ciary vzdy tri (spodok, stred, vrch) — pri rozsahu
     * 20 °C to znamenalo odhadovat po desiatich stupnoch. Nizky widget dostane
     * riedsiu mriezku, vysoky graf v appke hustejsiu.
     *
     * Ked sa nezmesti ani krok 10, vrati polovicu rozsahu — teda povodne tri
     * ciary, lebo menej uz nema zmysel.
     */
    fun krokTeplot(rozsah: Pair<Double, Double>, vyskaPx: Float, minOdstupPx: Float): Double {
        val sirka = rozsah.second - rozsah.first
        if (sirka <= 0.0) return 1.0
        return KROKY_TEPLOT.firstOrNull { vyskaPx * it / sirka >= minOdstupPx } ?: (sirka / 2.0)
    }

    /**
     * Hodnoty, na ktorych je vodorovna ciara: nasobky [krok] v rozsahu
     * vratane okrajov. Popisok „14" ma byt pri 14 °C, nie pri 13,7.
     */
    fun ciaryTeplot(rozsah: Pair<Double, Double>, krok: Double): List<Double> {
        if (krok <= 0.0) return emptyList()
        val prva = Math.ceil(rozsah.first / krok - 1e-9) * krok
        return generateSequence(prva) { it + krok }
            .takeWhile { it <= rozsah.second + 1e-9 }
            .toList()
    }

    /**
     * Indexy, na ktorych zacina novy den — sem patri zvisla ciara a popis dna.
     *
     * Prvy stlpec sa MEDZI hranice neratá: ciara na lavom okraji grafu je len
     * ramcek navyse. Popis dna si zaciatok useku aj tak berie z tohto zoznamu
     * spolu s nulou.
     */
    fun hraniceDni(stlpce: List<Stlpec>): List<Int> =
        stlpce.indices.filter { i -> i > 0 && stlpce[i].den != stlpce[i - 1].den }

    /**
     * Useky jednotlivych dni ako (od, do) — `do` je uz mimo useku.
     * Popis dna sa kresli do stredu useku, takze useky treba, nie len hranice.
     */
    fun useky(stlpce: List<Stlpec>): List<IntRange> {
        if (stlpce.isEmpty()) return emptyList()
        val hranice = listOf(0) + hraniceDni(stlpce) + listOf(stlpce.size)
        return hranice.zipWithNext { od, doo -> od until doo }.filter { !it.isEmpty() }
    }

    /** Kolko spolu naprsi cez cely graf — do popisu nad grafom. */
    fun spolu(stlpce: List<Stlpec>): Double = stlpce.sumOf { it.mm }

    // ------------------------------ rozsah ----------------------------------

    /**
     * Rozsahy, ktore sa daju vybrat pod grafom v detaile.
     *
     * V hodinach, nie v dnoch: 24 h je iny pohlad nez „dnes a zajtra" a prave
     * najblizsi den je to, kvoli comu sa clovek na graf pozera najcastejsie.
     * Widget kresli vzdy [HODIN_WIDGET], teda prvu polozku tohto zoznamu.
     */
    val ROZSAHY_HODIN = intArrayOf(24, 48, 72, 96, 120, 144, 168)

    const val HODIN_WIDGET = 24

    /** Popis rozsahu na tlacidle: 24 h, 2 dni, … 7 dni. */
    fun popisRozsahu(hodin: Int): String = when {
        hodin <= 24 -> "24 h"
        hodin % 24 != 0 -> "$hodin h"
        hodin / 24 < 5 -> "${hodin / 24} dni"
        else -> "${hodin / 24} dní"
    }
}
