package sk.lukac.pocasie

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/**
 * Self-update z NAS-u — JEDNA cesta pre obe miesta, odkial sa spusta.
 *
 * Do 5. 9. 2026 zil cely postup v `MainActivity` a v Nastaveniach nebol vobec.
 * Nasledok bol presne ten, na ktory prisla staznost: appka sa sice
 * aktualizovat vedela, ale jediny sposob, ako o to poziadat, bolo tuknut na
 * pat riadkov vysoku paticku s verziou. Kto ju nenasiel, mal appku, ktora
 * „nema skontrolovat aktualizacie nikde".
 *
 * Preto je to tu a nie tam: docs/shared-standard.md 2.8 chce v Nastaveniach sekciu
 * **Verzia** s tlacidlom `Skontrolovat teraz`, a dve kopie tej istej logiky
 * (jedna pre paticku, druha pre tlacidlo) by sa rozisli pri prvej oprave.
 *
 * Postup je z docs/shared-standard.md 2.4 a nemeni sa:
 *   1. cita sa IBA `output-metadata.json` (par stoviek bajtov), nikdy APK
 *   2. novsia verzia = spytaj sa dialogom
 *   3. az po potvrdeni stiahni, over `versionCode` PRIAMO Z APK a odovzdaj
 *      systemovemu instalatoru
 *   4. nedostupny NAS je pri tichej kontrole TICHO
 */
object Aktualizacia {

    /** Verzia tak, ako sa vsade ukazuje: `1.5 (6)`. */
    data class Verzia(val kod: Long, val meno: String) {
        val popis: String get() = "$meno ($kod)"
    }

    private val vlakno = Executors.newSingleThreadExecutor()
    private val hlavne = Handler(Looper.getMainLooper())

    /**
     * Clovek odisiel povolit instalaciu z neznamych zdrojov a ma sa mu po
     * navrate ponuknut znova. Je to stav procesu, nie obrazovky — odchod do
     * systemovych nastaveni moze aktivitu aj zabit.
     */
    @Volatile
    var cakaNaPovolenie = false

    /** Blokujuce (SMB) — volaj z vlakna. Null = NAS sa neozval. */
    fun naNase(cfg: UpdateConfig): Verzia? = runCatching {
        val j = org.json.JSONObject(SmbUpdater.readText(cfg, "output-metadata.json"))
            .getJSONArray("elements").getJSONObject(0)
        Verzia(j.getLong("versionCode"), j.optString("versionName").ifBlank { "?" })
    }.getOrNull()

    /**
     * Cela kontrola vratane ponuky a instalacie.
     *
     * @param hlasny   rucna kontrola: kazdy vysledok sa aj povie. Ticha
     *                 kontrola pri starte mlci a na mobilnych datach sa
     *                 nespusti vobec (8 MB APK je presny opak slubu, ze na
     *                 datach appka nestahuje nic).
     * @param oznam    kam pisat hlasky — Toast v appke, riadok stavu
     *                 v Nastaveniach.
     * @param oVerzii  co je na NAS-e a ci je to novsie. Vola sa na hlavnom
     *                 vlakne aj vtedy, ked sa NAS neozval (vtedy `null`),
     *                 aby paticka vedela nakreslit pomlcku.
     */
    fun skontroluj(
        a: Activity,
        hlasny: Boolean,
        oznam: (String) -> Unit,
        oVerzii: (Verzia?, Boolean) -> Unit = { _, _ -> },
    ) {
        val cfg = Prefs.updateConfig(a)
        if (!cfg.isConfigured) {
            if (hlasny) oznam(a.getString(R.string.up_error, a.getString(R.string.up_bez_udajov)))
            return
        }
        if (!hlasny && Prenos.naMobilnychDatach(a)) return
        if (hlasny) oznam(a.getString(R.string.up_checking))
        vlakno.execute {
            val vzdialena = naNase(cfg)
            hlavne.post {
                if (a.isFinishing || a.isDestroyed) return@post
                if (vzdialena == null) {
                    oVerzii(null, false)
                    if (hlasny) oznam(a.getString(R.string.up_error, "NAS je nedostupný"))
                    return@post
                }
                val teraz = ApkInstaller.currentVersionCode(a)
                val novsia = vzdialena.kod > teraz
                oVerzii(vzdialena, novsia)
                if (!novsia) {
                    if (hlasny) oznam(a.getString(R.string.up_latest, teraz))
                    return@post
                }
                if (!ApkInstaller.canInstall(a)) {
                    cakaNaPovolenie = true
                    if (hlasny) {
                        oznam(a.getString(R.string.up_need_perm))
                        ApkInstaller.openInstallPermissionSettings(a)
                    }
                    return@post
                }
                AlertDialog.Builder(a)
                    .setTitle(R.string.up_prompt_title)
                    .setMessage(
                        a.getString(
                            R.string.up_prompt_msg,
                            vzdialena.popis,
                            VersionUi.installed(a),
                        )
                    )
                    .setPositiveButton(R.string.up_install) { _, _ -> stiahni(a, cfg, oznam) }
                    .setNegativeButton(R.string.up_later, null)
                    .show()
            }
        }
    }

    private fun stiahni(a: Activity, cfg: UpdateConfig, oznam: (String) -> Unit) {
        oznam(a.getString(R.string.up_downloading))
        vlakno.execute {
            val ciel = ApkInstaller.downloadTarget(a, cfg.apkFileName)
            val apk = runCatching { SmbUpdater.download(cfg, ciel) }.getOrNull()
            // Najvacsi jednorazovy prenos, aky appka spravi. Keby sa neratal,
            // pocitadlo by ukazovalo kilobajty predpovedi a mlcalo o osmich
            // megabajtoch APK.
            if (apk != null) Prenos.zapis(a, apk.length())
            hlavne.post {
                if (a.isFinishing || a.isDestroyed) return@post
                when {
                    apk == null -> oznam(a.getString(R.string.up_error, "sťahovanie zlyhalo"))
                    ApkInstaller.readVersionCode(a, apk) == null ->
                        oznam(a.getString(R.string.up_bad_apk))
                    else -> ApkInstaller.install(a, apk)
                }
            }
        }
    }
}
