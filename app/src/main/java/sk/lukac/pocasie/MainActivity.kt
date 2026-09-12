package sk.lukac.pocasie

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors

/**
 * Zoznam sledovanych miest.
 *
 * **Kresli sa VZDY z disku, nikdy nie z odpovede siete priamo.** Zoznam sa
 * ukaze okamzite z ulozenej predpovede a stahovanie ho len prepise. Bez toho
 * by appka po otvoreni ukazala prazdno a par sekund by cakala na siet — to
 * vyzera ako chyba, aj ked ide o pomalu wifi.
 *
 * Stary udaj sa **oznaci**, neschova: „pred 3 d" pod nazvom miesta je jediny
 * sposob, ako rozoznat cerstvu predpoved od tyzden starej.
 */
class MainActivity : ZakladActivity() {

    private lateinit var zoznam: RecyclerView
    private lateinit var potiahni: SwipeRefreshLayout
    private lateinit var prazdno: TextView
    private lateinit var verzia: TextView
    private lateinit var panelHladania: View
    private lateinit var poleHladania: EditText
    private lateinit var stavHladania: TextView
    private lateinit var zoznamNavrhov: RecyclerView

    private val vlakno = Executors.newSingleThreadExecutor()
    private val hlavne = Handler(Looper.getMainLooper())

    /**
     * Vyraz, na ktory uz bezi (alebo dobehol) dopyt. Chrani pred dvoma vecami:
     * opakovanym stiahnutim toho isteho a starou odpovedou, ktora by prepisala
     * navrhy k novsiemu vyrazu — jednovlaknovy `vlakno` ich vracia v poradi,
     * ale clovek medzitym pise dalej.
     */
    private var hladanyVyraz = ""

    /** Odlozene hladanie; kazde pismeno ho posunie o [PAUZA_HLADANIA] dalej. */
    private val odlozeneHladanie = Runnable { hladaj() }
    private val polozky = mutableListOf<PolozkaMiesta>()
    private val navrhy = mutableListOf<Miesto>()

    /** Miesto aj s tym, co o nom vieme z disku. */
    data class PolozkaMiesta(val miesto: Miesto, val predpoved: Predpoved?)

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        setContentView(R.layout.activity_main)

        zoznam = findViewById(R.id.zoznam_miest)
        potiahni = findViewById(R.id.potiahni)
        prazdno = findViewById(R.id.prazdno)
        verzia = findViewById(R.id.verzia)
        panelHladania = findViewById(R.id.panel_hladania)
        poleHladania = findViewById(R.id.pole_hladania)
        stavHladania = findViewById(R.id.stav_hladania)
        zoznamNavrhov = findViewById(R.id.zoznam_navrhov)

        zoznam.layoutManager = LinearLayoutManager(this)
        zoznam.adapter = AdapterMiest()
        zoznamNavrhov.layoutManager = LinearLayoutManager(this)
        zoznamNavrhov.adapter = AdapterNavrhov()

        findViewById<ImageView>(R.id.btn_pridat).setOnClickListener { prepniHladanie() }
        findViewById<ImageView>(R.id.btn_obnovit).setOnClickListener { stiahni(true) }
        findViewById<ImageView>(R.id.btn_upozornenia).setOnClickListener {
            startActivity(Intent(this, UpozorneniaActivity::class.java))
        }
        findViewById<ImageView>(R.id.btn_nastavenia).setOnClickListener {
            startActivity(Intent(this, NastaveniaActivity::class.java))
        }
        potiahni.setOnRefreshListener { stiahni(true) }

        // Navrhy sa ukazuju UZ POCAS PISANIA, nie az po Enteri. Enter ostava —
        // kto je zvyknuty ho stlacit, dostane vysledok hned a nie o 400 ms.
        //
        // Preco odklad a nie dopyt na kazde pismeno: geokodovanie je prenos ako
        // kazdy iny (rata sa do pocitadla dat) a "Bratislava" by inak stalo
        // desat dopytov namiesto jedneho. 400 ms je kratsie, nez trva napisat
        // dalsie pismeno, takze sa to na obrazovke neprejavi.
        poleHladania.setOnEditorActionListener { _, _, _ ->
            hlavne.removeCallbacks(odlozeneHladanie)
            hladaj()
            true
        }
        poleHladania.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                hlavne.removeCallbacks(odlozeneHladanie)
                if (s.toString().trim().length < NAJMENEJ_ZNAKOV) {
                    // Prazdne pole nema ukazovat navrhy k tomu, co tam bolo
                    // pred sekundou — to vyzera, akoby appka nereagovala.
                    hladanyVyraz = ""
                    vycistiNavrhy()
                    return
                }
                hlavne.postDelayed(odlozeneHladanie, PAUZA_HLADANIA)
            }
        })

        // Pomlcka miesto prazdna: "pozrelo sa a nedalo sa zistit" je iná
        // sprava nez prazdne miesto, ktore vyzera ako chyba vykreslenia.
        VersionUi.render(verzia, null)
        verzia.setOnClickListener { skontrolujAktualizaciu(manualne = true) }
        skontrolujAktualizaciu(manualne = false)
    }

    override fun onResume() {
        super.onResume()
        prekresli()
        // Pri otvoreni sa stahuje len to, co je stare — a na mobilnych datach
        // ani to (zadanie z 3. 9. 2026). Na datach ma stahovat VYLUCNE to,
        // co si clovek vypyta: tlacidlo ↻ alebo potiahnutie zoznamu. Otvorenie
        // appky nie je ziadost o nove data, casto je to len pohlad na to, co
        // uz je stiahnute. Na Wi-Fi ostava spravanie nezmenene.
        if (!Prenos.naMobilnychDatach(this) || Prefs.otvorenieNaDatach(this)) {
            stiahni(false)
        }
        // Vratil sa z nastaveni, kde prave povolil instalaciu? Nech nemusi
        // klikat na paticku znova.
        if (Aktualizacia.cakaNaPovolenie && ApkInstaller.canInstall(this)) {
            Aktualizacia.cakaNaPovolenie = false
            skontrolujAktualizaciu(manualne = true)
        }
    }

    // ──────────────────────────── data ──────────────────────────────────────

    private fun prekresli() {
        polozky.clear()
        polozky += Prefs.miesta(this).map { PolozkaMiesta(it, Ulozisko.nacitaj(this, it)) }
        zoznam.adapter?.notifyDataSetChanged()
        val prazdny = polozky.isEmpty()
        prazdno.visibility = if (prazdny) View.VISIBLE else View.GONE
        prazdno.text = "${getString(R.string.prazdno)}\n\n${getString(R.string.prazdno_hint)}"
        zoznam.visibility = if (prazdny) View.GONE else View.VISIBLE
    }

    private fun stiahni(vynutene: Boolean) {
        if (Prefs.miesta(this).isEmpty()) {
            potiahni.isRefreshing = false
            return
        }
        potiahni.isRefreshing = true
        vlakno.execute {
            Obnova.stiahniVsetko(this, vynutene)
            val poslane = Alerty.vyhodnot(this)
            hlavne.post {
                potiahni.isRefreshing = false
                prekresli()
                PocasieWidgetProvider.prekresliVsetky(this)
                if (poslane > 0) toast(getString(R.string.upoz_poslanych, poslane))
            }
        }
    }

    // ──────────────────────────── hľadanie ──────────────────────────────────

    private fun prepniHladanie() {
        val viditelne = panelHladania.visibility == View.VISIBLE
        panelHladania.visibility = if (viditelne) View.GONE else View.VISIBLE
        if (!viditelne) poleHladania.requestFocus()
    }

    private fun vycistiNavrhy() {
        navrhy.clear()
        zoznamNavrhov.adapter?.notifyDataSetChanged()
        stavHladania.visibility = View.GONE
    }

    private fun hladaj() {
        val vyraz = poleHladania.text.toString().trim()
        if (vyraz.length < NAJMENEJ_ZNAKOV) return
        if (vyraz == hladanyVyraz) return          // to iste uz mame
        hladanyVyraz = vyraz
        stavHladania.visibility = View.VISIBLE
        stavHladania.text = getString(R.string.hladam)
        vlakno.execute {
            val najdene = runCatching { OpenMeteo.najdiMiesta(vyraz) }.getOrDefault(emptyList())
            // Geokodovanie je tiez prenos. Bez tohto by pocitadlo tvrdilo, ze
            // appka nestiahla nic, a pritom by za hladanim jedneho mesta bolo
            // pat dopytov (jeden na kazde stlacenie klavesy).
            Prenos.zapis(this, OpenMeteo.poslednyPrenosBajtov.toLong())
            hlavne.post {
                // Clovek medzitym dopisal dalsie pismeno? Potom tato odpoved
                // patri k inemu vyrazu a zobrazit ju znamena ukazat navrhy,
                // ktore uz s napisanym textom nesuvisia.
                if (poleHladania.text.toString().trim() != vyraz) return@post
                navrhy.clear()
                navrhy += najdene
                zoznamNavrhov.adapter?.notifyDataSetChanged()
                stavHladania.text = if (najdene.isEmpty())
                    getString(R.string.nic_sa_nenaslo) else ""
                stavHladania.visibility =
                    if (najdene.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun pridaj(m: Miesto) {
        Prefs.pridajMiesto(this, m)
        panelHladania.visibility = View.GONE
        poleHladania.setText("")
        hladanyVyraz = ""
        vycistiNavrhy()
        prekresli()
        stiahni(true)
    }

    private fun spytajSaNaZmazanie(m: Miesto) {
        AlertDialog.Builder(this)
            .setTitle(R.string.zmazat_miesto)
            .setMessage(getString(R.string.zmazat_miesto_otazka, m.nazov))
            .setPositiveButton(R.string.zmazat) { _, _ ->
                Prefs.zmazMiesto(this, m)
                prekresli()
                // Zmazane miesto musi zmiznut aj z plochy, nielen zo zoznamu.
                PocasieWidgetProvider.prekresliVsetky(this)
            }
            .setNegativeButton(R.string.zrusit, null)
            .show()
    }

    // ──────────────────────────── adaptéry ──────────────────────────────────

    private inner class AdapterMiest : RecyclerView.Adapter<AdapterMiest.Drzitel>() {
        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            val nazov: TextView = v.findViewById(R.id.miesto_nazov)
            val popis: TextView = v.findViewById(R.id.miesto_popis)
            val zrazky: TextView = v.findViewById(R.id.miesto_zrazky)
            val teplota: TextView = v.findViewById(R.id.miesto_teplota)
            val rozsah: TextView = v.findViewById(R.id.miesto_rozsah)
        }

        override fun onCreateViewHolder(rodic: ViewGroup, typ: Int) = Drzitel(
            LayoutInflater.from(rodic.context).inflate(R.layout.item_miesto, rodic, false))

        override fun getItemCount() = polozky.size

        override fun onBindViewHolder(d: Drzitel, i: Int) {
            val (m, p) = polozky[i]
            d.nazov.text = m.nazov
            d.teplota.text = getString(R.string.teplota,
                Format.teplota(p?.teplotaTeraz ?: p?.dnes?.maxTeplota))

            val dnes = p?.dnes
            d.rozsah.text = if (dnes == null) "" else getString(
                R.string.rozsah_dna,
                Format.teplota(dnes.minTeplota), Format.teplota(dnes.maxTeplota))

            // Druhy riadok hovori DVE veci naraz: kde to je a ako stare to je.
            // Vek udaja tu musi byt — inak vyzera tyzden stara predpoved presne
            // ako cerstva.
            val vek = when {
                p == null -> getString(R.string.nikdy_nestiahnute)
                else -> Format.predAko(System.currentTimeMillis() - p.stiahnuteMs)
            }
            val popis = Format.popisPocasia(p?.kodTeraz ?: -1)
            d.popis.text = listOfNotNull(
                m.popis.ifBlank { null }, popis.ifBlank { null }, vek,
            ).joinToString(" · ")

            // Cislo bez obdobia je hadanka: "0,3 mm" moze byt za hodinu aj
            // za tyzden. Tu je to VZDY uhrn za dnesok, tak to aj stoji.
            val mm = dnes?.zrazkyMm ?: 0.0
            d.zrazky.text = if (mm >= 0.1)
                getString(R.string.zrazky_dnes, Format.zrazky(mm))
            else getString(R.string.bez_zrazok)

            d.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_ID, m.id))
            }
            d.itemView.setOnLongClickListener { spytajSaNaZmazanie(m); true }
        }
    }

    // ─────────────────────────── self-update ───────────────────────────────
    //
    // Cely postup je v [Aktualizacia] — to iste tlacidlo je aj v Nastaveniach
    // (docs/shared-standard.md 2.8 §3) a dve kopie by sa rozisli. Tu ostava len paticka:
    // po kontrole do nej pribudne verzia z NAS-u, alebo pomlcka, ked sa NAS
    // neozval.

    private fun toast(text: String) =
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun skontrolujAktualizaciu(manualne: Boolean) =
        Aktualizacia.skontroluj(
            this,
            hlasny = manualne,
            oznam = { toast(it) },
            oVerzii = { v, novsia -> VersionUi.render(verzia, v?.popis, novsia) },
        )

    private inner class AdapterNavrhov : RecyclerView.Adapter<AdapterNavrhov.Drzitel>() {
        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            val nazov: TextView = v.findViewById(R.id.navrh_symbol)
            val popis: TextView = v.findViewById(R.id.navrh_popis)
        }

        override fun onCreateViewHolder(rodic: ViewGroup, typ: Int) = Drzitel(
            LayoutInflater.from(rodic.context).inflate(R.layout.item_navrh, rodic, false))

        override fun getItemCount() = navrhy.size

        override fun onBindViewHolder(d: Drzitel, i: Int) {
            val m = navrhy[i]
            d.nazov.text = m.nazov
            // Popis (kraj, krajina) je to jedine, cim sa dve rovnomenne mesta
            // od seba odlisia — preto je vzdy vidiet.
            d.popis.text = m.popis
            d.itemView.setOnClickListener { pridaj(m) }
        }
    }

    private companion object {
        /** Kratsie nez dve pismena vrati pol sveta — netreba sa pytat. */
        const val NAJMENEJ_ZNAKOV = 2

        /** Ako dlho po poslednom pismene sa pyta servera (ms). */
        const val PAUZA_HLADANIA = 400L
    }
}
