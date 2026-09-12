package sk.lukac.pocasie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

/**
 * Pravidla upozorneni — teplota a zrazky.
 *
 * Dialog na pridanie sa sklada v kode, nie v XML: su to styri polia, ktore sa
 * navzajom ovplyvnuju (pri „akekolvek zrazky" nema hranica zmysel a schova sa),
 * a to sa v statickom layoute rieši horsie nez tromi riadkami kodu.
 *
 * **Povolenie na notifikacie sa pyta az tu**, pri prvom ulozenom pravidle —
 * nie pri starte appky. Dialog hned po instalacii sa odkliknuti bez precitania
 * a potom sa appka nema ako ozvat.
 */
class UpozorneniaActivity : ZakladActivity() {

    private val pravidla = mutableListOf<Upozornenie>()
    private lateinit var zoznam: RecyclerView
    private lateinit var prazdno: TextView

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        setContentView(R.layout.activity_upozornenia)

        zoznam = findViewById(R.id.zoznam)
        prazdno = findViewById(R.id.prazdno)
        zoznam.layoutManager = LinearLayoutManager(this)
        zoznam.adapter = Adapter()

        findViewById<ImageView>(R.id.btn_spat).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btn_pridat).setOnClickListener { dialog(null) }

        prekresli()
    }

    private fun prekresli() {
        pravidla.clear()
        pravidla += Prefs.upozornenia(this)
        zoznam.adapter?.notifyDataSetChanged()
        val prazdny = pravidla.isEmpty()
        prazdno.visibility = if (prazdny) View.VISIBLE else View.GONE
        prazdno.text = "${getString(R.string.upoz_ziadne)}\n\n" +
            getString(R.string.upoz_ziadne_hint)
        zoznam.visibility = if (prazdny) View.GONE else View.VISIBLE
    }

    private fun uloz(zoznamPravidiel: List<Upozornenie>) {
        Prefs.ulozUpozornenia(this, zoznamPravidiel)
        prekresli()
        pytajPovolenie()
    }

    private fun pytajPovolenie() {
        if (pravidla.isEmpty()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val ma = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (ma == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(
        kod: Int, povolenia: Array<out String>, vysledky: IntArray,
    ) {
        super.onRequestPermissionsResult(kod, povolenia, vysledky)
        // Odmietnutie sa musi povedat: inak clovek nastavi pravidla a diví sa,
        // preco sa appka nikdy neozve.
        if (vysledky.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            android.widget.Toast.makeText(
                this, R.string.upoz_povolenie, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Popis pravidla do zoznamu — vzdy s hodnotou, nikdy len „teplota". */
    private fun popis(u: Upozornenie): String = when (u.druh) {
        DruhUpozornenia.TEPLOTA_POD ->
            "${getString(R.string.upoz_teplota_pod)} ${Format.teplota(u.hranica)} °C"
        DruhUpozornenia.TEPLOTA_NAD ->
            "${getString(R.string.upoz_teplota_nad)} ${Format.teplota(u.hranica)} °C"
        DruhUpozornenia.ZRAZKY_AKEKOLVEK -> getString(R.string.upoz_zrazky_ake)
        DruhUpozornenia.ZRAZKY_NAD ->
            "${getString(R.string.upoz_zrazky_nad)} ${Format.zrazky(u.hranica)} mm/h"
    }

    private fun kde(u: Upozornenie): String {
        val miesto = Prefs.miesta(this).firstOrNull { it.id == u.miestoId }
        val kdeText = miesto?.nazov ?: getString(R.string.upoz_vsetky_miesta)
        return "$kdeText · ${getString(R.string.upoz_hodin, u.hodinDopredu)}"
    }

    // ──────────────────────────── dialóg ────────────────────────────────────

    private fun dialog(existujuce: Upozornenie?) {
        val miesta = Prefs.miesta(this)
        val druhy = listOf(
            DruhUpozornenia.TEPLOTA_POD to getString(R.string.upoz_teplota_pod),
            DruhUpozornenia.TEPLOTA_NAD to getString(R.string.upoz_teplota_nad),
            DruhUpozornenia.ZRAZKY_AKEKOLVEK to getString(R.string.upoz_zrazky_ake),
            DruhUpozornenia.ZRAZKY_NAD to getString(R.string.upoz_zrazky_nad),
        )

        val telo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        fun nadpis(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }.also { telo.addView(it) }

        nadpis(getString(R.string.upoz_druh))
        val vyberDruhu = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                                   druhy.map { it.second })
            setSelection(druhy.indexOfFirst { it.first == existujuce?.druh }.coerceAtLeast(0))
        }
        telo.addView(vyberDruhu)

        val popisHranice = nadpis(getString(R.string.upoz_hranica))
        val poleHranice = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(existujuce?.hranica?.let { Format.teplota(it) } ?: "0")
        }
        telo.addView(poleHranice)

        nadpis(getString(R.string.upoz_miesto))
        val vyberMiesta = Spinner(this).apply {
            val moznosti = listOf(getString(R.string.upoz_vsetky_miesta)) + miesta.map { it.nazov }
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, moznosti)
            val i = miesta.indexOfFirst { it.id == existujuce?.miestoId }
            setSelection(if (i >= 0) i + 1 else 0)
        }
        telo.addView(vyberMiesta)

        val popisHodin = nadpis(getString(R.string.upoz_dopredu))
        val posuvnik = SeekBar(this).apply {
            max = 71                              // 1..72 h
            progress = (existujuce?.hodinDopredu ?: 24) - 1
        }
        fun obnovPopisHodin() {
            popisHodin.text = "${getString(R.string.upoz_dopredu)}: " +
                getString(R.string.upoz_hodin, posuvnik.progress + 1)
        }
        posuvnik.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, od: Boolean) = obnovPopisHodin()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        obnovPopisHodin()
        telo.addView(posuvnik)

        // Pri „akékoľvek zrážky" hranica nemá čo znamenať — schová sa aj
        // s popisom, aby sa nedala vyplniť hodnota, ktorá nič nespraví.
        fun prepniHranicu() {
            val druh = druhy[vyberDruhu.selectedItemPosition].first
            val viditelna = druh != DruhUpozornenia.ZRAZKY_AKEKOLVEK
            popisHranice.visibility = if (viditelna) View.VISIBLE else View.GONE
            poleHranice.visibility = if (viditelna) View.VISIBLE else View.GONE
        }
        vyberDruhu.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, i: Int, id: Long,
                ) = prepniHranicu()

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        prepniHranicu()

        val staviteľ = AlertDialog.Builder(this)
            .setTitle(R.string.upoz_nove)
            .setView(telo)
            .setPositiveButton(R.string.upoz_ulozit) { _, _ ->
                val druh = druhy[vyberDruhu.selectedItemPosition].first
                val hranica = poleHranice.text.toString().replace(',', '.').toDoubleOrNull()
                if (druh != DruhUpozornenia.ZRAZKY_AKEKOLVEK && hranica == null) {
                    android.widget.Toast.makeText(
                        this, R.string.upoz_zla_hranica,
                        android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val i = vyberMiesta.selectedItemPosition
                val nove = Upozornenie(
                    id = existujuce?.id ?: UUID.randomUUID().toString().take(8),
                    druh = druh,
                    miestoId = if (i == 0) "" else miesta[i - 1].id,
                    hranica = hranica ?: 0.0,
                    hodinDopredu = posuvnik.progress + 1,
                    zapnute = existujuce?.zapnute ?: true,
                )
                uloz(pravidla.filterNot { it.id == nove.id } + nove)
            }
            .setNegativeButton(R.string.zrusit, null)

        if (existujuce != null) {
            staviteľ.setNeutralButton(R.string.upoz_zmazat) { _, _ ->
                uloz(pravidla.filterNot { it.id == existujuce.id })
            }
        }
        staviteľ.show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Drzitel>() {
        inner class Drzitel(v: View) : RecyclerView.ViewHolder(v) {
            val popis: TextView = v.findViewById(R.id.upoz_popis)
            val kde: TextView = v.findViewById(R.id.upoz_kde)
            val zapnute: Switch = v.findViewById(R.id.upoz_zapnute)
        }

        override fun onCreateViewHolder(r: ViewGroup, t: Int) = Drzitel(
            LayoutInflater.from(r.context).inflate(R.layout.item_upozornenie, r, false))

        override fun getItemCount() = pravidla.size

        override fun onBindViewHolder(d: Drzitel, i: Int) {
            val u = pravidla[i]
            d.popis.text = popis(u)
            d.kde.text = kde(u)
            d.zapnute.setOnCheckedChangeListener(null)
            d.zapnute.isChecked = u.zapnute
            d.zapnute.setOnCheckedChangeListener { _, zap ->
                uloz(pravidla.map { if (it.id == u.id) it.copy(zapnute = zap) else it })
            }
            d.itemView.setOnClickListener { dialog(u) }
        }
    }
}
