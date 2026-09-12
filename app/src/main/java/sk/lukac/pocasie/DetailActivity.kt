package sk.lukac.pocasie

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Detail miesta: graf pocasia, pasik hodin a zoznam dni.
 *
 * **Nestahuje nic.** Kresli sa vylucne z toho, co uz je na disku — do detailu
 * sa vchadza zo zoznamu, ktory sa prave obnovil, takze dalsi dopyt by bol len
 * dalsi prenos za tie iste cisla.
 *
 * Ked je hodinovka v Nastaveniach vypnuta, pasik hodin **zmizne aj s nadpisom**
 * (nie prazdny riadok): prazdny pasik vyzera ako chyba, chybajuci nadpis ako
 * rozhodnutie. Graf vtedy prejde na denne uhrny — je to menej podrobne, ale
 * stale to odpoveda na „kedy tento tyzden".
 */
class DetailActivity : ZakladActivity() {

    private val hodiny = mutableListOf<Hodina>()
    private val dni = mutableListOf<Den>()

    /** Vsetky hodiny od „teraz" dalej. Graf si z nich berie zvoleny rozsah. */
    private var vsetkyHodiny: List<Hodina> = emptyList()

    private lateinit var graf: GrafView
    private lateinit var stavGrafu: TextView
    private lateinit var radRozsahov: LinearLayout

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        setContentView(R.layout.activity_detail)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val miesto = Prefs.miesta(this).firstOrNull { it.id == id }
        if (miesto == null) {
            finish()
            return
        }
        val p = Ulozisko.nacitaj(this, miesto)

        findViewById<ImageView>(R.id.btn_spat).setOnClickListener { finish() }
        findViewById<TextView>(R.id.detail_nazov).text = miesto.nazov
        findViewById<TextView>(R.id.detail_stav).text = listOfNotNull(
            miesto.popis.ifBlank { null },
            p?.let { Format.predAko(System.currentTimeMillis() - it.stiahnuteMs) }
                ?: getString(R.string.nikdy_nestiahnute),
        ).joinToString(" · ")

        // Od aktualnej hodiny dalej — hodiny, ktore uz presli, nikomu nic
        // nepovedia a len by odsunuli tie podstatne mimo obrazovku. Cas berie
        // zo `current.time`, teda zo zony MIESTA; hodinova predpoved zacina
        // polnocou a jej prva polozka by o dnesnom vecere ukazala rano.
        val odteraz = p?.odkedyDopredu.orEmpty()
        vsetkyHodiny = p?.hodinyOd(odteraz).orEmpty()
        // Pasik ukazuje 48 h, graf tolko, kolko si clovek vyberie pod nim.
        // Pasik sa cita po jednej hodine a stodvadsat stlpcekov by sa nim
        // neprerolovalo.
        hodiny += vsetkyHodiny.take(48)
        dni += p?.dni.orEmpty()

        // Faza mesiaca sa nesahuje — pocita sa (`Mesiac.kt`). Preto je tu aj
        // vtedy, ked predpoved v cache este vychod a zapad slnka nema.
        val dnes = java.time.LocalDate.now()
        findViewById<TextView>(R.id.detail_mesiac).text =
            "${Mesiac.znak(dnes)} ${Mesiac.nazov(dnes)} · ${Mesiac.najblizsiaUdalost(dnes)}"

        pripravGraf()

        val zoznamHodin: RecyclerView = findViewById(R.id.zoznam_hodin)
        val nadpisHodin: TextView = findViewById(R.id.nadpis_hodiny)
        if (hodiny.isEmpty()) {
            zoznamHodin.visibility = View.GONE
            nadpisHodin.visibility = View.GONE
        } else {
            zoznamHodin.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            zoznamHodin.adapter = AdapterHodin()
        }

        val zoznamDni: RecyclerView = findViewById(R.id.zoznam_dni)
        zoznamDni.layoutManager = LinearLayoutManager(this)
        zoznamDni.adapter = AdapterDni()
    }

    // ─────────────────────────────── graf ───────────────────────────────────

    /**
     * Graf pocasia a tlacidla rozsahu nad nim.
     *
     * Rozsah je v HODINACH a zacina na 24: „co bude do zajtra" je otazka,
     * kvoli ktorej sa clovek na graf pozera najcastejsie, a tyzdenny graf na
     * nu odpoveda zle — dvadsatstyri stlpcov zo stoseddesiatich je na sirku
     * palca. Vyber sa PAMATA ([Prefs.grafHodin]): kto sa pozera na tri dni,
     * pozera sa na ne pri kazdom miesta, nie raz.
     *
     * Ked hodinovka chyba (v Nastaveniach sa da vypnut kvoli datam), graf
     * kresli denne uhrny a tlacidla rozsahu zmiznu — vyberat by sa nemalo
     * z coho.
     */
    private fun pripravGraf() {
        graf = findViewById(R.id.graf_pocasie)
        stavGrafu = findViewById(R.id.graf_stav)
        radRozsahov = findViewById(R.id.graf_rozsahy)
        val nadpis: TextView = findViewById(R.id.nadpis_graf)

        if (vsetkyHodiny.isEmpty() && dni.isEmpty()) {
            // Nie je z coho kreslit: prazdny ramcek vyzera ako chyba.
            graf.visibility = View.GONE
            nadpis.visibility = View.GONE
            stavGrafu.visibility = View.GONE
            radRozsahov.visibility = View.GONE
            return
        }

        if (vsetkyHodiny.isEmpty()) {
            nadpis.setText(R.string.graf_nadpis_dni)
            radRozsahov.visibility = View.GONE
            vykresli(Graf.zDni(dni), getString(R.string.graf_dni, dni.size))
            return
        }

        nadpis.setText(R.string.graf_nadpis_hodiny)
        // Rozsah, ktory by ukazal to iste co ten predosly, medzi tlacidlami
        // nema co robit: pri trojdnovej predpovedi je „7 dní" klamstvo.
        val moznosti = Graf.ROZSAHY_HODIN.filter { it - 24 < vsetkyHodiny.size }
            .ifEmpty { listOf(vsetkyHodiny.size) }
        var zvoleny = Prefs.grafHodin(this).let { ulozeny ->
            moznosti.firstOrNull { it == ulozeny } ?: moznosti.first()
        }

        fun prekresli() {
            Prefs.nastavGrafHodin(this, zvoleny)
            vykresli(
                Graf.zHodin(vsetkyHodiny, zvoleny),
                Graf.popisRozsahu(minOf(zvoleny, vsetkyHodiny.size)),
            )
            for (i in 0 until radRozsahov.childCount) {
                oznac(radRozsahov.getChildAt(i) as TextView, moznosti[i] == zvoleny)
            }
        }

        radRozsahov.removeAllViews()
        for (hodin in moznosti) {
            val tlacidlo = TextView(this).apply {
                text = Graf.popisRozsahu(hodin)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener {
                    zvoleny = hodin
                    prekresli()
                }
            }
            radRozsahov.addView(tlacidlo, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
        }
        prekresli()
    }

    /** Vzhlad tlacidla rozsahu. Vybrane je vyplnene, ostatne su obrysy. */
    private fun oznac(tlacidlo: TextView, vybrane: Boolean) {
        tlacidlo.setBackgroundResource(
            if (vybrane) R.drawable.chip_vybrany else R.drawable.chip)
        tlacidlo.setTextColor(ContextCompat.getColor(
            this, if (vybrane) R.color.pozadie else R.color.text_vedlajsi))
    }

    private fun vykresli(stlpce: List<Graf.Stlpec>, obdobie: String) {
        val zakladny = getString(
            R.string.graf_spolu, Format.zrazky(Graf.spolu(stlpce)), obdobie)
        stavGrafu.text = zakladny
        graf.nastav(stlpce)
        // Prst na grafe = jedina cesta k presnemu cislu jednej hodiny; do
        // stlpca sirokeho tri pixely sa popisok nakreslit neda.
        graf.naVyber = { s ->
            stavGrafu.text = when {
                s == null -> zakladny
                s.jeHodinovy -> getString(
                    R.string.graf_bod_hodina,
                    Format.denKratko(s.den), s.hodina,
                    Format.teplota(s.teplota), Format.zrazky(s.mm))
                else -> getString(
                    R.string.graf_bod_den,
                    "${Format.denVTyzdni(s.den)} ${Format.denKratko(s.den)}",
                    Format.teplota(s.teplota), Format.zrazky(s.mm))
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ───────────────────────────── adaptéry ─────────────────────────────────

    private inner class AdapterHodin : RecyclerView.Adapter<AdapterHodin.Drzitel>() {
        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            val cas: TextView = v.findViewById(R.id.hodina_cas)
            val teplota: TextView = v.findViewById(R.id.hodina_teplota)
            val zrazky: TextView = v.findViewById(R.id.hodina_zrazky)
        }

        override fun onCreateViewHolder(r: ViewGroup, t: Int) = Drzitel(
            LayoutInflater.from(r.context).inflate(R.layout.item_hodina, r, false))

        override fun getItemCount() = hodiny.size

        override fun onBindViewHolder(d: Drzitel, i: Int) {
            val h = hodiny[i]
            d.cas.text = Format.hodina(h.cas)
            d.teplota.text = getString(R.string.teplota, Format.teplota(h.teplota))
            // Zrazky sa ukazuju v milimetroch, ked su, inak v percentach
            // pravdepodobnosti. Nula milimetrov pri 60 % je iná sprava nez
            // nula pri 0 % — a prve je to, kvoli comu clovek berie dazdnik.
            d.zrazky.text = when {
                h.zrazkyMm >= 0.1 ->
                    getString(R.string.zrazky_mm_h, Format.zrazky(h.zrazkyMm))
                h.pravdepodobnostZrazok >= 20 ->
                    getString(R.string.pravdepodobnost, h.pravdepodobnostZrazok)
                else -> ""
            }
        }
    }

    private inner class AdapterDni : RecyclerView.Adapter<AdapterDni.Drzitel>() {
        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            val nazov: TextView = v.findViewById(R.id.den_nazov)
            val popis: TextView = v.findViewById(R.id.den_popis)
            val zrazky: TextView = v.findViewById(R.id.den_zrazky)
            val min: TextView = v.findViewById(R.id.den_min)
            val max: TextView = v.findViewById(R.id.den_max)
        }

        override fun onCreateViewHolder(r: ViewGroup, t: Int) = Drzitel(
            LayoutInflater.from(r.context).inflate(R.layout.item_den, r, false))

        override fun getItemCount() = dni.size

        override fun onBindViewHolder(d: Drzitel, i: Int) {
            val den = dni[i]
            d.nazov.text = when (i) {
                0 -> getString(R.string.dnes)
                1 -> getString(R.string.zajtra)
                else -> "${Format.denVTyzdni(den.datum)} ${Format.denKratko(den.datum)}"
            }
            // Vychod a zapad k popisu pocasia, nie do vlastneho riadku: je to
            // druhotny udaj a samostatny riadok by zoznam dni zdvojnasobil.
            val slnko = if (den.vychodHm.isNotEmpty() && den.zapadHm.isNotEmpty())
                "  ↑${den.vychodHm} ↓${den.zapadHm}" else ""
            d.popis.text = Format.popisPocasia(den.kod) + slnko
            d.zrazky.text = if (den.zrazkyMm >= 0.1)
                getString(R.string.zrazky_mm_den, Format.zrazky(den.zrazkyMm)) else ""
            d.min.text = Format.teplota(den.minTeplota)
            d.max.text = Format.teplota(den.maxTeplota)
        }
    }

    companion object {
        const val EXTRA_ID = "miesto_id"
    }
}
