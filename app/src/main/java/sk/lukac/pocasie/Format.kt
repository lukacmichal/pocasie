package sk.lukac.pocasie

import java.util.Locale

/**
 * Formatovanie cisel a casov. Bez Androidu — da sa testovat.
 *
 * Vsetko je zamerne struce: riadok miesta ma na sirku telefonu tri udaje
 * a dlhy zapis by ich rozhadzal do druheho riadku.
 */
object Format {

    /**
     * Cisla sa pisu po slovensky, teda s desatinnou CIARKOU.
     *
     * Nie `Locale.getDefault()`: ten zavisi od nastavenia telefonu a appka by
     * na anglickom telefone pisala "2.2 mm" a na slovenskom "2,2 mm" — pritom
     * vsetky texty okolo su tak ci tak po slovensky. Pevne nastavenie zaroven robi
     * testy nezavislymi od stroja, na ktorom bezia.
     */
    private val SK: Locale = Locale("sk")

    /** Teplota na cele stupne. Desatina stupna nikoho neoblecie inak. */
    fun teplota(c: Double?): String =
        if (c == null || c.isNaN()) "–" else "%.0f".format(SK, c)

    /**
     * Zrazky v mm. Pod 10 mm s desatinou (rozdiel medzi 0,2 a 2 mm je rozdiel
     * medzi mrholenim a dazdom), nad 10 mm uz na cele.
     */
    fun zrazky(mm: Double): String = when {
        mm <= 0.0 -> "0"
        mm < 10 -> "%.1f".format(SK, mm)
        else -> "%.0f".format(SK, mm)
    }

    /** "2026-09-03T14:00" -> "14:00" */
    fun hodina(cas: String): String =
        if (cas.length >= 16) cas.substring(11, 16) else cas

    /** "2026-09-03" -> "3.9." */
    fun denKratko(datum: String): String {
        val kusy = datum.split("-")
        if (kusy.size != 3) return datum
        val d = kusy[2].toIntOrNull() ?: return datum
        val m = kusy[1].toIntOrNull() ?: return datum
        return "$d.$m."
    }

    /** "2026-09-03" -> "St" (podla poradia dna v tyzdni) */
    fun denVTyzdni(datum: String): String = runCatching {
        val d = java.time.LocalDate.parse(datum)
        arrayOf("Po", "Ut", "St", "Št", "Pi", "So", "Ne")[d.dayOfWeek.value - 1]
    }.getOrDefault("")

    /**
     * Ako davno. Pouziva sa na "aktualizovane pred ...", takze zaokruhluje
     * nadol: "pred 2 h" pri 2 h 50 min je poctivejsie nez "pred 3 h", ked su
     * data starsie, nez sa zda.
     */
    fun predAko(msDozadu: Long): String {
        val minut = msDozadu / 60_000
        return when {
            minut < 1 -> "teraz"
            minut < 60 -> "pred ${minut} min"
            minut < 60 * 24 -> "pred ${minut / 60} h"
            else -> "pred ${minut / (60 * 24)} d"
        }
    }

    /** Objem prenesenych dat. */
    fun bajty(b: Long): String = when {
        b >= 1024L * 1024 -> "%.1f MB".format(SK, b / 1048576.0)
        b >= 1024 -> "${b / 1024} kB"
        else -> "$b B"
    }

    /**
     * Pocasie slovom podla WMO kodu, ktory Open-Meteo vracia.
     *
     * Kody sa zlucuju do skupin: rozdiel medzi "mierne mrholenie" a "husté
     * mrholenie" nikoho neoblecie inak, ale rozdiel medzi dazdom a snehom ano.
     */
    fun popisPocasia(kod: Int): String = when (kod) {
        0 -> "jasno"
        1, 2 -> "polojasno"
        3 -> "zamračené"
        45, 48 -> "hmla"
        51, 53, 55, 56, 57 -> "mrholenie"
        61, 63, 65, 66, 67, 80, 81, 82 -> "dážď"
        71, 73, 75, 77, 85, 86 -> "sneženie"
        95, 96, 99 -> "búrka"
        else -> ""
    }
}
