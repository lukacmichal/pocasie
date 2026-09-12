package sk.lukac.pocasie

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.util.Locale

/**
 * Samotne kreslenie grafu — jedno miesto pre APPKU aj WIDGET.
 *
 * Widget kresli launcher a ten `View` nevie zobrazit; do `RemoteViews` sa da
 * poslat jedine hotovy obrazok ([GrafObrazok]). Keby kazdy kreslil po svojom,
 * boli by to dva grafy, ktore sa pri prvej zmene rozidu — a clovek by na
 * ploche videl iny tvar pocasia nez v appke. Preto sa tu kresli na `Canvas`,
 * ktory je raz z `View` a raz z `Bitmap`u.
 *
 * **Co je kde:**
 *
 *     °C  ┌────────────────────────────┐  mm
 *     25  │      ___                   │
 *         │  ___/   \___               │  ciara = teplota (vlastna mierka)
 *     15  │ /           \______        │
 *         │ ▁▁   ▃▃          ▁▁  ▅▅▅   │  stlpce = zrazky (spodnych 45 %)
 *      5  └────────────────────────────┘  0
 *           Št 4.9.   Pi 5.9.   So 6.9.
 *
 * Stlpce maju spodnu tretinu az polovicu, nie celu vysku: keby siahali hore,
 * prekryli by ciaru teploty prave v dazdivy den, teda vtedy, ked je graf
 * najzaujimavejsi.
 */
object GrafKresba {

    /** Farby zvoleneho rezimu. Widget si ich musi podat sam — viz [Vzhlad]. */
    data class Farby(
        val dazd: Int,
        val sneh: Int,
        val teplota: Int,
        val ciara: Int,
        val text: Int,
        val vyber: Int,
    )

    /** Kolko z vysky grafu patri stlpcom zrazok. */
    private const val PODIEL_ZRAZOK = 0.45f

    private val SK: Locale = Locale("sk")

    private fun popisMm(mm: Double): String =
        if (mm == Math.floor(mm)) "%.0f".format(SK, mm) else "%.1f".format(SK, mm)

    private fun popisC(c: Double): String = "%.0f".format(SK, c)

    /**
     * Sirka lavej osi (teplota). Meria sa TEXT, nie odhad — pri zvacsenom
     * pisme by pevne cislo popisky orezalo.
     */
    fun odsadenieVlavo(stlpce: List<Graf.Stlpec>, hustota: Float, velkostTextu: Float): Float {
        val rozsah = Graf.rozsahTeplot(stlpce) ?: return 2f * hustota
        val stetec = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = velkostTextu }
        val siroky = maxOf(
            stetec.measureText(popisC(rozsah.first)),
            stetec.measureText(popisC(rozsah.second)),
        )
        return siroky + 5f * hustota
    }

    /** Sirka pravej osi (zrazky). */
    fun odsadenieVpravo(stlpce: List<Graf.Stlpec>, hustota: Float, velkostTextu: Float): Float {
        val stetec = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = velkostTextu }
        return stetec.measureText(popisMm(Graf.mierka(stlpce))) + 5f * hustota
    }

    /**
     * Vykresli graf do obdlznika [sirka] x [vyska].
     *
     * @param popisyHodin ma sa pod graf pisat aj hodina? KOLKO hodin sa napise
     *   (kazda, po dvoch, … po dvanast), rozhodne [Graf.krokHodin] podla sirky
     *   stlpca; ked sa nezmesti ani krok 12 h, nepise sa ziadna.
     * @param vybrany index stlpca pod prstom, alebo -1
     * @param suchoText co napisat do stredu, ked nikde nepadá ani kvapka
     */
    fun nakresli(
        c: Canvas,
        sirka: Float,
        vyska: Float,
        stlpce: List<Graf.Stlpec>,
        farby: Farby,
        hustota: Float,
        velkostTextu: Float,
        vybrany: Int = -1,
        popisyHodin: Boolean = false,
        popisyDni: Boolean = true,
        suchoText: String? = null,
    ) {
        if (stlpce.isEmpty() || sirka <= 0f || vyska <= 0f) return

        fun dp(v: Float) = v * hustota

        val stetec = Paint(Paint.ANTI_ALIAS_FLAG)
        val stetecMriezky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = farby.ciara
        }
        val stetecPopisu = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = farby.text
            textSize = velkostTextu
        }
        val stetecTeploty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.8f)
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = farby.teplota
        }

        // Hranica dna je vyraznejsia nez hodinova ciara — pri tyzdni je zvislych
        // ciar styrnast a oko sa ma chytit dni, nie poludni.
        val stetecDna = Paint(stetecMriezky).apply {
            color = farby.text
            alpha = 80
        }

        val vlavo = odsadenieVlavo(stlpce, hustota, velkostTextu)
        val vpravo = sirka - odsadenieVpravo(stlpce, hustota, velkostTextu)
        val sirkaGrafu = (vpravo - vlavo).coerceAtLeast(1f)
        val krok = sirkaGrafu / stlpce.size

        // Krok hodin podla toho, kolko miesta ma jedna hodina (viz Graf.krokHodin).
        val krokHodin = if (popisyHodin && stlpce.first().jeHodinovy) {
            Graf.krokHodin(krok, stetecPopisu.measureText("00") + dp(8f))
        } else null
        val piseHodiny = krokHodin != null

        val hore = dp(4f)
        // Pod grafom je pas na popisy: den, hodina, alebo oboje pod sebou.
        val pasDole = when {
            popisyDni && piseHodiny -> velkostTextu * 2.4f + dp(6f)
            popisyDni || piseHodiny -> velkostTextu * 1.3f + dp(4f)
            else -> dp(2f)
        }
        val dole = vyska - pasDole
        val vyskaGrafu = (dole - hore).coerceAtLeast(1f)
        val mierkaMm = Graf.mierka(stlpce)
        val rozsahC = Graf.rozsahTeplot(stlpce)
        val pasZrazok = vyskaGrafu * PODIEL_ZRAZOK

        // --- mriezka a popisky osi ---
        if (rozsahC != null) {
            val (tDole, tHore) = rozsahC
            val krokC = Graf.krokTeplot(rozsahC, vyskaGrafu, velkostTextu * 1.2f)
            stetecPopisu.textAlign = Paint.Align.RIGHT
            for (hodnota in Graf.ciaryTeplot(rozsahC, krokC)) {
                val y = dole - vyskaGrafu * ((hodnota - tDole) / (tHore - tDole)).toFloat()
                c.drawLine(vlavo, y, vpravo, y, stetecMriezky)
                c.drawText(popisC(hodnota), vlavo - dp(3f), y + velkostTextu / 3f, stetecPopisu)
            }
            // Spodna hrana je os — kresli sa, aj ked na nej nie je nasobok kroku.
            c.drawLine(vlavo, dole, vpravo, dole, stetecMriezky)
        } else {
            for (i in 0..2) {
                val y = dole - vyskaGrafu * i / 2f
                c.drawLine(vlavo, y, vpravo, y, stetecMriezky)
            }
        }

        // --- zvisle ciary hodin (pod stlpcami a ciarou, aby ich neprekryvali) ---
        if (krokHodin != null) {
            for ((i, s) in stlpce.withIndex()) {
                val h = s.hodina.take(2).toIntOrNull() ?: continue
                if (h % krokHodin != 0) continue
                // Polnoc ma svoju (vyraznejsiu) ciaru hranice dna.
                if (h == 0 && i > 0) continue
                val x = vlavo + krok * i + krok / 2f
                c.drawLine(x, hore, x, dole, stetecMriezky)
            }
        }
        // Zrazky maju vlastnu mierku: popisok je pri hornej hrane ICH pasu,
        // nie pri hornej hrane grafu — inak by cislo tvrdilo nieco ineho, nez
        // kde stlpce naozaj koncia.
        stetecPopisu.textAlign = Paint.Align.LEFT
        c.drawText(popisMm(mierkaMm), vpravo + dp(3f),
                   dole - pasZrazok + velkostTextu / 3f, stetecPopisu)
        c.drawText("0", vpravo + dp(3f), dole + velkostTextu / 3f, stetecPopisu)

        // --- stlpce zrazok ---
        val medzera = if (krok > dp(6f)) krok * 0.22f else 0f
        stlpce.forEachIndexed { i, s ->
            val x = vlavo + krok * i
            if (s.mm <= 0.0) return@forEachIndexed
            stetec.color = if (s.sneh) farby.sneh else farby.dazd
            // Aspon ciarka, aj ked je hodnota mala: 0,1 mm ma byt vidiet,
            // inak vyzera mrholenie ako sucho.
            val h = ((s.mm / mierkaMm) * pasZrazok).toFloat().coerceAtLeast(dp(1.5f))
            c.drawRect(x + medzera / 2f, dole - h, x + krok - medzera / 2f, dole, stetec)
        }

        // --- ciara teploty ---
        if (rozsahC != null) {
            val (tDole, tHore) = rozsahC
            val cesta = Path()
            var zacate = false
            stlpce.forEachIndexed { i, s ->
                if (!s.maTeplotu) return@forEachIndexed
                val x = vlavo + krok * i + krok / 2f
                val podiel = ((s.teplota - tDole) / (tHore - tDole)).toFloat()
                val y = dole - vyskaGrafu * podiel.coerceIn(0f, 1f)
                if (zacate) cesta.lineTo(x, y) else { cesta.moveTo(x, y); zacate = true }
            }
            if (zacate) c.drawPath(cesta, stetecTeploty)
        }

        // --- hranice dni a ich popisy ---
        stetecPopisu.textAlign = Paint.Align.CENTER
        for (i in Graf.hraniceDni(stlpce)) {
            val x = vlavo + krok * i
            c.drawLine(x, hore, x, dole + dp(3f), stetecDna)
        }
        if (popisyDni) {
            for (usek in Graf.useky(stlpce)) {
                val stred = vlavo + krok * (usek.first + usek.last + 1) / 2f
                val den = stlpce[usek.first].den
                val popis = "${Format.denVTyzdni(den)} ${Format.denKratko(den)}"
                // Popis, ktory sa do useku nezmesti, sa nekresli vobec:
                // prekryte datumy su horsie nez ziadne.
                if (stetecPopisu.measureText(popis) < krok * usek.count() - dp(2f)) {
                    c.drawText(popis, stred, dole + velkostTextu + dp(3f), stetecPopisu)
                }
            }
        }

        // --- hodiny ---
        if (krokHodin != null) {
            // Ked sa dni nepisu (widget), hodiny idu na ich riadok — inak by
            // pod grafom ostal prazdny pas a popisky by viseli mimo obrazku.
            val y = dole + velkostTextu + dp(3f) +
                if (popisyDni) velkostTextu * 1.2f else 0f
            for ((i, s) in stlpce.withIndex()) {
                val h = s.hodina.take(2).toIntOrNull() ?: continue
                if (h % krokHodin != 0) continue
                c.drawText(s.hodina.take(2), vlavo + krok * i + krok / 2f, y, stetecPopisu)
            }
        }

        // --- vybrany stlpec ---
        if (vybrany in stlpce.indices) {
            stetec.color = farby.vyber
            stetec.alpha = 90
            val x = vlavo + krok * vybrany
            c.drawRect(x, hore, x + krok.coerceAtLeast(dp(1.5f)), dole, stetec)
            stetec.alpha = 255
        }

        // --- suchy graf sa musi priznat ---
        if (suchoText != null && stlpce.none { it.mm > 0.0 }) {
            stetecPopisu.textAlign = Paint.Align.CENTER
            c.drawText(suchoText, vlavo + sirkaGrafu / 2f, dole - dp(3f), stetecPopisu)
        }
    }
}
