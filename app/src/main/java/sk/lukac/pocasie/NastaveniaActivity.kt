package sk.lukac.pocasie

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Nastavenia: najprv appka (kolko dat tahat), potom NAS (self-update).
 *
 * Poradie ani tvar si tu nevymyslam — drzi sa spolocneho standardu, ktory
 * stavia [NastaveniaForm] (docs/shared-standard.md 2.8).
 *
 * Sekcia "Prenesene data" je tu preto, ze "minimalizuj data" sa neda overit
 * bez cisla. Bez neho by aj zle nastavenie vyzeralo dobre a nikto by sa
 * nedozvedel, ze hodinovka pre styri miesta stoji trikrat viac nez treba.
 * Cislo je **rozdelene na Wi-Fi a mobilne data** ([Prenos]) — jedno spolocne
 * cislo zakryvalo jedinu otazku, na ktoru ma pocitadlo odpovedat: stiahla mi
 * appka nieco za peniaze?
 */
class NastaveniaActivity : ZakladActivity() {

    private lateinit var f: NastaveniaForm

    private lateinit var rezim: RadioGroup
    private lateinit var pismo: NastaveniaForm.Krokovac
    private lateinit var pismoWidgetu: NastaveniaForm.Krokovac

    private lateinit var obnovaZapnuta: CheckBox
    private lateinit var obnovaHodin: NastaveniaForm.Krokovac
    private lateinit var lenWifi: CheckBox
    private lateinit var rannaObnova: CheckBox
    private lateinit var rannaHodina: NastaveniaForm.Krokovac
    private lateinit var otvorenieNaDatach: CheckBox
    private lateinit var dni: NastaveniaForm.Krokovac
    private lateinit var hodinovka: CheckBox

    private lateinit var host: EditText
    private lateinit var share: EditText
    private lateinit var cesta: EditText
    private lateinit var subor: EditText
    private lateinit var user: EditText
    private lateinit var heslo: EditText

    override fun onCreate(stav: Bundle?) {
        super.onCreate(stav)
        f = NastaveniaForm(this)

        // ── vzhlad (rovnake vo vsetkych appkach) ─────────────────────────────
        f.nadpis(getString(R.string.nastavenia))
        rezim = f.vyber(
            "Režim", listOf("Podľa systému", "Svetlý", "Čierny"), Vzhlad.rezim(this))
        pismo = f.krokovac("Veľkosť písma", Vzhlad.pismo(this),
                           Vzhlad.PISMO_MIN, Vzhlad.PISMO_MAX) { Vzhlad.nazovKroku(it) }

        pismoWidgetu = f.krokovac(
            getString(R.string.n_pismo_widget), Vzhlad.pismoWidgetu(this),
            Vzhlad.PISMO_MIN, Vzhlad.PISMO_MAX,
            getString(R.string.n_pismo_widget_popis)) { Vzhlad.nazovKroku(it) }

        // ── kolko dat ────────────────────────────────────────────────────────
        f.ciara()
        f.nadpis(getString(R.string.n_obnova))
        obnovaZapnuta = f.zaskrtavatko(
            getString(R.string.n_obnova_zapnuta), Prefs.obnovaZapnuta(this))
        obnovaHodin = f.krokovac(
            getString(R.string.n_obnova_hodin), Prefs.obnovaHodin(this), 1, 24) { "$it h" }
        lenWifi = f.zaskrtavatko(
            getString(R.string.n_len_wifi), Prefs.lenWifi(this),
            getString(R.string.n_len_wifi_popis))
        rannaObnova = f.zaskrtavatko(
            getString(R.string.n_rano), Prefs.rannaObnova(this),
            getString(R.string.n_rano_popis))
        // Hodina, nie minuty: presna minuta je aj tak slub, ktory WorkManager
        // nedava — pracu spusta v okne a ked v tu hodinu nie je Wi-Fi, caka.
        rannaHodina = f.krokovac(
            getString(R.string.n_rano_hodina), Prefs.rannaHodina(this), 0, 23) {
            if (it < 10) "0$it:00" else "$it:00"
        }
        dni = f.krokovac(
            getString(R.string.n_dni), Prefs.dni(this), 1, 16,
            getString(R.string.n_dni_popis)) { "$it" }
        hodinovka = f.zaskrtavatko(
            getString(R.string.n_hodinovka), Prefs.hodinovka(this),
            getString(R.string.n_hodinovka_popis))

        // ── mobilne data ─────────────────────────────────────────────────────
        //
        // Vlastna sekcia, nie riadok v obnove na pozadi: obnova na pozadi a
        // otvorenie appky su dve rozne cesty k tomu istemu prenosu a clovek,
        // ktory si ich chce ustrazit, hlada jedno miesto, nie dve.
        f.ciara()
        f.nadpis(getString(R.string.n_data))
        otvorenieNaDatach = f.zaskrtavatko(
            getString(R.string.n_otvorenie_na_datach), Prefs.otvorenieNaDatach(this),
            getString(R.string.n_otvorenie_na_datach_popis))

        // ── prenesene data ───────────────────────────────────────────────────
        //
        // Nadpis, text aj tlacidlo pyta [Prenos]: sekcia je vo vsetkych
        // appkach rovnaka a devat kopii tych istych retazcov by sa rozislo
        // uz pri prvej oprave preklepu.
        f.ciara()
        f.nadpis(Prenos.NADPIS)
        val stavPrenosu: TextView = f.stav(Prenos.popis(this))
        f.poznamka(Prenos.POZNAMKA)
        f.tlacidlo(Prenos.VYNULOVAT) {
            Prenos.vynuluj(this)
            stavPrenosu.text = Prenos.popis(this)
        }

        // ── NAS / self-update ────────────────────────────────────────────────
        //
        // Nazvy poli aj poradie su z docs/shared-standard.md 2.8 §3 — rovnake vo vsetkych
        // appkach, aby sa clovek nemusel v kazdej orientovat nanovo.
        f.ciara()
        f.nadpis(getString(R.string.n_sekcia_nas))
        f.odstavec(getString(R.string.up_hint))
        val cfg = Prefs.updateConfig(this)
        host = f.pole(getString(R.string.up_host), cfg.host,
                      getString(R.string.n_host_popis))
        share = f.pole(getString(R.string.up_share), cfg.shareName,
                       getString(R.string.n_share_popis))
        cesta = f.pole(getString(R.string.up_path), cfg.remotePath,
                       getString(R.string.n_path_popis))
        subor = f.pole(getString(R.string.up_file), cfg.apkFileName,
                       getString(R.string.n_file_popis))
        user = f.pole(getString(R.string.up_user), cfg.username,
                      getString(R.string.n_user_popis))
        heslo = f.pole(getString(R.string.up_pass), cfg.password,
                       getString(R.string.n_pass_popis), heslo = true)
        f.prepinacHesiel()
        f.poznamka("Prázdne pole = použije sa hodnota, s ktorou bola appka " +
                   "zbuildovaná (z C:\\NAS\\nas-credentials.local).")

        // ── verzia ───────────────────────────────────────────────────────────
        //
        // Toto tu do 5. 9. 2026 CHYBALO — a bola to jedina cast staznosti
        // „appka nema skontrolovat aktualizacie nikde", ktora sa dala overit
        // bez telefonu. Appka sa aktualizovat vedela, ale poziadat o to sa dalo
        // jedine tuknutim na paticku s verziou na hlavnej obrazovke; kto
        // nevedel, ze je klikatelna, mal appku bez kontroly aktualizacii.
        f.ciara()
        f.nadpis(getString(R.string.n_sekcia_verzia))
        f.stav(getString(R.string.n_verzia_stav,
                         BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        val stavAktualizacie = f.stav()
        f.tlacidlo(getString(R.string.up_check)) {
            // Kontrola ide proti tomu, co je PRAVE vo formulari — inak by
            // opravene heslo zabralo az po odchode z obrazovky.
            uloz()
            Aktualizacia.skontroluj(
                this,
                hlasny = true,
                oznam = { stavAktualizacie.text = it },
            )
        }

        // ── ulozit ───────────────────────────────────────────────────────────
        //
        // Odchod z obrazovky uklada tiez (`onPause` nizsie) — tlacidlo nie je
        // druha cesta k tomu istemu, ale POTVRDENIE. Bez neho sa clovek pyta,
        // ci sa nastavenie ulozilo, a radsej ho prepise este raz; ostatne
        // appky (askener, meniny, wake-nb, stocks) ho maju tiez.
        f.ciara()
        f.tlacidlo(getString(R.string.up_ulozit)) {
            uloz()
            android.widget.Toast.makeText(
                this, R.string.n_ulozene, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }

        f.hotovo()
    }

    /**
     * Uloz pri odchode AJ tlacidlom.
     *
     * Odchod uklada preto, aby sa nedalo o nastavenie prist zabudnutym klikom;
     * tlacidlo preto, aby bolo vidiet, ze sa ulozilo. Obe cesty vedu do tej
     * istej metody — dve kopie ukladania su dve miesta, kde sa da na nove
     * pole zabudnut.
     */
    override fun onPause() {
        super.onPause()
        uloz()
    }

    private fun uloz() {
        Vzhlad.uloz(this, f.vybrane(rezim), pismo.hodnota, pismoWidgetu.hodnota)
        // Widget kresli launcher a sam sa o zmene nastavenia nedozvie.
        PocasieWidgetProvider.prekresliVsetky(this)

        Prefs.nastavObnovu(this, obnovaZapnuta.isChecked)
        Prefs.nastavObnovuHodin(this, obnovaHodin.hodnota)
        Prefs.nastavLenWifi(this, lenWifi.isChecked)
        Prefs.nastavRannuObnovu(this, rannaObnova.isChecked)
        Prefs.nastavRannuHodinu(this, rannaHodina.hodnota)
        Prefs.nastavOtvorenieNaDatach(this, otvorenieNaDatach.isChecked)
        Prefs.nastavDni(this, dni.hodnota)
        Prefs.nastavHodinovku(this, hodinovka.isChecked)
        // Rozvrh sa preplanuje hned — inak by zmena intervalu platila az po
        // dobehnuti toho stareho, co je pri 24 h cely den.
        Obnova.naplanuj(this)

        Prefs.ulozUpdateConfig(this, UpdateConfig(
            host = host.text.toString().trim(),
            shareName = share.text.toString().trim(),
            remotePath = cesta.text.toString().trim(),
            apkFileName = subor.text.toString().trim(),
            username = user.text.toString().trim(),
            password = heslo.text.toString(),
        ))
    }
}
