package sk.lukac.pocasie

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
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
 * Stahovanie predpovede na pozadi — VYHRADNE po Wi-Fi.
 *
 * WorkManager, nie AlarmManager: system smie pracu odlozit a zlucit s inou,
 * a `NetworkType.UNMETERED` drzi slub, ze sa na mobilnych datach nespusti
 * vobec. Od ANDROID-5 (13. 10. 2026) to plati bez vynimky — volba „Len na
 * Wi-Fi" z Nastaveni zmizla, mobilne data si uz zapnut neda. V samotnej
 * praci to este strazi [Prenos.naMobilnychDatach]: meranost nie je to iste
 * co simka (docs/shared-standard.md 2.8b), operator moze neobmedzene data hlasit ako
 * nemerane.
 *
 * Rozvrhy su DVA a kazdy odpoveda na inu otazku:
 *
 * 1. **interval** (`pocasie_obnova`) — ako casto sa obnovuje cez den.
 *    Periodicka praca, preplanuje sa pri kazdej zmene nastavenia (`UPDATE`).
 * 2. **rano** (`pocasie_obnova_rano_1x`) — o ktorej hodine ma byt v telefone
 *    to najnovsie, nez clovek odide z domu. **Jednorazova praca, ktora si
 *    naplanuje dalsiu, nie periodicka** (ANDROID-5): periodicka rata dalsi
 *    beh od skutocneho konca predosleho, takze rano bez Wi-Fi a Wi-Fi az
 *    o 17:00 by z rozvrhu urobilo „17:00" natrvalo. Dvojca: `RannaObnova`
 *    v tankomate, `PondelnaObnova` v Stocks.
 */
object Obnova {
    private const val TAG = "Obnova"
    private const val MENO = "pocasie_obnova"

    /**
     * Novy nazov od ANDROID-5. Pod starym visi periodicka praca a jednorazova
     * sa k periodickej pripojit neda — stary sa preto len rusi.
     */
    private const val MENO_RANO = "pocasie_obnova_rano_1x"
    private const val MENO_RANO_STARE = "pocasie_obnova_rano"

    /** Kolko pokusov ma jeden ranny beh, kym to vzda do zajtra. */
    private const val POKUSOV = 5

    /** Vstup pracovnika: preskocit kontrolu cerstvosti. */
    const val KLUC_VYNUTENE = "vynutene"

    /** Vstup pracovnika: ranny beh — po nom sa planuje zajtrajsok. */
    const val KLUC_RANO = "rano"

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
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val poziadavka = PeriodicWorkRequestBuilder<Praca>(
            Prefs.obnovaHodin(ctx).toLong(), TimeUnit.HOURS,
        ).setConstraints(podmienky).build()
        wm.enqueueUniquePeriodicWork(MENO, ExistingPeriodicWorkPolicy.UPDATE, poziadavka)
    }

    /**
     * Ranne stiahnutie o [Prefs.rannaHodina].
     *
     * Ked v tu hodinu Wi-Fi nie je, WorkManager pracu NEZAHODI ani nepusti
     * cez simku — pocka, kym sa Wi-Fi objavi. Uz stiahnuta predpoved lezi na
     * disku a plati dalej, takze cakanie nikoho neobera o data.
     *
     * `vynutene = true`: rano sa stahuje aj vtedy, ked je ulozena predpoved
     * este "cerstva". O 6:00 ma byt v telefone to najnovsie, nie nieco spred
     * hodiny a pol — a styri miesta stoja po Wi-Fi zopar kilobajtov.
     *
     * Politika je `KEEP`, kym sa hodina nezmenila: appka planuje pri kazdom
     * starte a `REPLACE` by cakajucu pracu zahodil.
     */
    private fun naplanujRannu(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        wm.cancelUniqueWork(MENO_RANO_STARE)
        if (!Prefs.rannaObnova(ctx)) {
            wm.cancelUniqueWork(MENO_RANO)
            Prefs.zapamataRannyRozvrh(ctx, "")
            return
        }
        val hodina = Prefs.rannaHodina(ctx)
        val podpis = "1x:rano=$hodina"
        val politika = if (Prefs.rannyRozvrh(ctx) == podpis) {
            ExistingWorkPolicy.KEEP
        } else {
            ExistingWorkPolicy.REPLACE
        }
        wm.enqueueUniqueWork(MENO_RANO, politika, rannaPoziadavka(hodina, System.currentTimeMillis()))
        Prefs.zapamataRannyRozvrh(ctx, podpis)
    }

    /**
     * Zajtrajsi ranny beh, volany z beziacej prace. `APPEND_OR_REPLACE`, nie
     * `REPLACE`: ten by zrusil prave beziacu pracu, teda samu seba.
     */
    private fun naplanujZajtrajsok(ctx: Context) {
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(
            MENO_RANO, ExistingWorkPolicy.APPEND_OR_REPLACE,
            rannaPoziadavka(Prefs.rannaHodina(ctx), System.currentTimeMillis()))
    }

    private fun rannaPoziadavka(hodina: Int, teraz: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<Praca>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setInitialDelay(odkladDoHodiny(hodina, teraz), TimeUnit.MILLISECONDS)
            // 5, 10, 20, 40 min: vypadok Open-Meteo o 6:00 sa do odchodu
            // doziha, ale beh nestrieli pokusy kazdych 30 s.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .setInputData(workDataOf(KLUC_VYNUTENE to true, KLUC_RANO to true))
            .build()

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
            val ctx = applicationContext
            val rano = inputData.getBoolean(KLUC_RANO, false)
            // Nemerana siet, ale simka: nestahovat nic (ANDROID-5).
            val naDatach = Prenos.naMobilnychDatach(ctx)
            if (naDatach && !rano) return Result.success()
            val hotovych = if (naDatach) 0
                else stiahniVsetko(ctx, inputData.getBoolean(KLUC_VYNUTENE, false))
            val neuspech = hotovych == 0 && Prefs.miesta(ctx).isNotEmpty()
            if (!neuspech) {
                Alerty.vyhodnot(ctx)
                // Widget kresli launcher a o novych datach sa sam nedozvie —
                // bez tohto by ukazoval stare cisla az do svojho dalsieho tiknutia.
                PocasieWidgetProvider.prekresliVsetky(ctx)
            }
            if (!rano) {
                // Ziadne miesto sa nestiahlo = pravdepodobne siet. Retry, nie
                // failure: WorkManager to skusi s odstupom a nezrusi rozvrh.
                return if (neuspech) Result.retry() else Result.success()
            }
            // Zajtrajsok sa pripaja az po poslednom pokuse: kazde opakovanie
            // spusti doWork znova a pripojilo by dalsieho.
            if (neuspech && runAttemptCount < POKUSOV - 1) return Result.retry()
            naplanujZajtrajsok(ctx)
            return Result.success()
        }
    }
}
