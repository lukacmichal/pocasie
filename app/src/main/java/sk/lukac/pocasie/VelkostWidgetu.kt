package sk.lukac.pocasie

/**
 * Skutocna velkost widgetu (dp) z toho, co posiela launcher.
 *
 * Launcher neposiela jednu velkost, ale rozsah cez obe orientacie plochy:
 * na vysku je widget `MIN_WIDTH × MAX_HEIGHT`, na sirku `MAX_WIDTH ×
 * MIN_HEIGHT` (bunky su na sirku sirsie a nizsie). Do 26. 10. 2026 sa
 * vyska grafu ratala z `MIN_HEIGHT`, teda z plochy otocenej na sirku — na
 * S26 Ultra tak grafy vysli nizsie, nez sa do widgetu zmestilo, a pod nimi
 * ostalo prazdno. Rovnaka chyba skryvala hlasitost vo widgete LAN Music
 * (docs/shared-standard.md 2.10/3).
 *
 * Nula = launcher hodnotu neposlal; vtedy sa vezme ta druha, a ked nie je
 * ani ona, ostane nula (volajuci ma pre nu vlastnu predvolbu).
 *
 * Oddelene od widgetu kvoli testom — tie bezia na JVM bez Androidu.
 */
object VelkostWidgetu {

    fun sirkaDp(minSirka: Int, maxSirka: Int, naVysku: Boolean): Int =
        vyber(if (naVysku) minSirka else maxSirka, if (naVysku) maxSirka else minSirka)

    fun vyskaDp(minVyska: Int, maxVyska: Int, naVysku: Boolean): Int =
        vyber(if (naVysku) maxVyska else minVyska, if (naVysku) minVyska else maxVyska)

    private fun vyber(spravna: Int, nahradna: Int): Int =
        if (spravna > 0) spravna else nahradna.coerceAtLeast(0)
}
