package sk.lukac.pocasie

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Stahovanie predpovede na pozadi.
 *
 * WorkManager, nie AlarmManager: system smie pracu odlozit a zlucit s inou,
 * a `NetworkType.UNMETERED` drzi slub, ze sa na mobilnych datach nespusti
 * vobec. Rovnaka schema ako obnova cien v `askener`.
 *
 * **Preplanuje sa pri kazdej zmene nastavenia** (`REPLACE`), inak by zmena
 * intervalu platila az po tom, co dobehne ten stary — a to je pri 24 h
 * cely den.
 *
 * Rozvrhy su DVA a kazdy odpoveda na inu otazku:
 *
 * 1. **interval** (`pocasie_obnova`) — ako casto sa obnovuje cez den,
 * 2. **rano** (`pocasie_obnova_rano`) — o ktorej hodine ma byt v telefone
 *    to najnovsie, nez clovek odide z domu. Vzdy len po Wi-Fi.
 */
object Obnova {
    private const val TAG = "Obnova"
    private const val MENO = "pocasie_obnova"
    private const val MENO_RANO = "pocasie_obnova_rano"

    /** Vstup pracovnika: preskocit kontrolu cerstvosti. */
    const val KLUC_VYNUTENE = "vynutene"

    fun naplanuj(ctx: Context) {
        naplanujInterval(ctx)
        naplanujRannu(ctx)
    }

    private fun naplanujInterval(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!Prefs.obnovaZapnuta(ctx)) {
            wm.cancelUniqueWork(MENO)
            return
        }
        val podmienky = Constraints.Builder()
            .setRequiredNetworkType(
                if (Prefs.lenWifi(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val poziadavka = PeriodicWorkRequestBuilder<Praca>(
            Prefs.obnovaHodin(ctx).toLong(), TimeUnit.HOURS,
        ).setConstraints(podmienky).build()
        wm.enqueueUniquePeriodicWork(MENO, ExistingPeriodicWorkPolicy.UPDATE, poziadavka)
    }

    /**
     * Ranne stiahnutie o [Prefs.rannaHodina] — **vyhradne po Wi-Fi**.
     *
     * `NetworkType.UNMETERED` nie je len setrenie: je to cely zmysel tohto
     * rozvrhu. Ked v tu hodinu Wi-Fi nie je, WorkManager pracu NEZAHODI ani
     * nepusti cez simku — pocka, kym sa Wi-Fi objavi. Uz stiahnuta predpoved
     * lezi na disku a plati dalej, takze cakanie nikoho neobera o data.
     *
     * `vynutene = true`: rano sa stahuje aj vtedy, ked je ulozena predpoved
     * este "cerstva". O 6:00 ma byt v telefone to najnovsie, nie nieco spred
     * hodiny a pol — a styri miesta stoja po Wi-Fi zopar kilobajtov.
     *
     * Politika je `KEEP`, kym sa hodina nezmenila. Appka planuje pri kazdom
     * starte a `UPDATE` s novym odkladom by rozvrh posuval donekonecna —
     * kto appku otvara kazdy vecer, tomu by rano nestiahla nikdy.
     */
    private fun naplanujRannu(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!Prefs.rannaObnova(ctx)) {
            wm.cancelUniqueWork(MENO_RANO)
            Prefs.zapamataRannyRozvrh(ctx, "")
            return
        }
        val hodina = Prefs.rannaHodina(ctx)
        val podpis = "rano=$hodina"
        val politika = if (Prefs.rannyRozvrh(ctx) == podpis) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }
        val poziadavka = PeriodicWorkRequestBuilder<Praca>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInitialDelay(
                odkladDoHodiny(hodina, System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KLUC_VYNUTENE to true))
            .build()
        wm.enqueueUniquePeriodicWork(MENO_RANO, politika, poziadavka)
        Prefs.zapamataRannyRozvrh(ctx, podpis)
    }

    /**
     * Kolko milisekund zostava do najblizsej [hodina]:00 miestneho casu.
     *
     * Pocita sa cez `LocalDate.atTime`, nie pripocitanim 24 hodin: pri zmene
     * casu ma "6:00" ostat sestou hodinou rano, nie sa posunut na piatu.
     * Ked je prave teraz presne ta hodina, mieri sa na zajtrajsok — inak by
     * odklad vysiel nula a praca by sa spustila hned pri kazdom starte appky.
     */
    internal fun odkladDoHodiny(
        hodina: Int,
        teraz: Long,
        zona: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val ted = Instant.ofEpochMilli(teraz).atZone(zona)
        var ciel = ted.toLocalDate().atTime(hodina.coerceIn(0, 23), 0).atZone(zona)
        if (!ciel.isAfter(ted)) ciel = ciel.plusDays(1)
        return Duration.between(ted, ciel).toMillis()
    }

    /**
     * Stiahne predpoved pre vsetky miesta. Vrati, kolko sa ich podarilo.
     *
     * [vynutene] preskoci kontrolu cerstvosti — pouziva sa pri potiahnuti
     * zoznamu prstom, kde clovek vyslovne ziada nove cisla.
     */
    fun stiahniVsetko(ctx: Context, vynutene: Boolean): Int {
        val dni = Prefs.dni(ctx)
        val hodinovka = Prefs.hodinovka(ctx)
        val obnovaHodin = Prefs.obnovaHodin(ctx)
        var hotovych = 0
        for (miesto in Prefs.miesta(ctx)) {
            if (!vynutene) {
                val ulozena = Ulozisko.nacitaj(ctx, miesto)
                if (Ulozisko.jeCerstva(ulozena, obnovaHodin, System.currentTimeMillis())) {
                    hotovych++
                    continue
                }
            }
            try {
                val json = OpenMeteo.predpovedJson(miesto, dni, hodinovka)
                // Overit sa musi PRED ulozenim: pokazena odpoved v cache by
                // prepisala poslednu funkcnu predpoved a appka by ostala
                // prazdna aj po navrate siete.
                if (Predpoved.zJson(json, System.currentTimeMillis()) == null) {
                    Log.w(TAG, "predpoved pre ${miesto.nazov} sa neda precitat")
                    continue
                }
                Ulozisko.uloz(ctx, miesto, json)
                Prenos.zapis(ctx, json.toByteArray().size.toLong())
                hotovych++
            } catch (e: Exception) {
                Log.w(TAG, "predpoved pre ${miesto.nazov} sa nestiahla", e)
            }
        }
        return hotovych
    }

    class Praca(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            val vynutene = inputData.getBoolean(KLUC_VYNUTENE, false)
            val hotovych = stiahniVsetko(applicationContext, vynutene)
            if (hotovych == 0 && Prefs.miesta(applicationContext).isNotEmpty()) {
                // Ziadne miesto sa nestiahlo = pravdepodobne siet. Retry, nie
                // failure: WorkManager to skusi s odstupom a nezrusi rozvrh.
                return Result.retry()
            }
            Alerty.vyhodnot(applicationContext)
            // Widget kresli launcher a o novych datach sa sam nedozvie —
            // bez tohto by ukazoval stare cisla az do svojho dalsieho tiknutia.
            PocasieWidgetProvider.prekresliVsetky(applicationContext)
            return Result.success()
        }
    }
}
