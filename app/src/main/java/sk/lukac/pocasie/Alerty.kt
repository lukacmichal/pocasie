package sk.lukac.pocasie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Upozornenia na teplotu a zrazky.
 *
 * Rozhodovanie „ozvat sa?" je v [Pravidla] (ciste, testovatelne); tu je len
 * Android okolo toho — kanal, text a odoslanie.
 *
 * **Vyhodnocuje sa nad UZ STIAHNUTOU predpovedou.** Ziadne pravidlo nespusti
 * vlastne stahovanie: kazde pridane upozornenie by inak znamenalo dalsi prenos
 * a appka slubuje opak.
 *
 * **Ta ista hodina sa ohlasi raz.** Kluc zasahu obsahuje hodinu, ktorej sa
 * tyka, takze obnova kazde tri hodiny nezvoni na ten isty mraz dokola. Bez
 * toho by clovek upozornenia po dvoch dnoch vypol.
 */
object Alerty {
    private const val KANAL = "pocasie_upozornenia"

    fun pripravKanal(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val kanal = NotificationChannel(
            KANAL,
            ctx.getString(R.string.notif_kanal),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = ctx.getString(R.string.notif_kanal_popis) }
        nm.createNotificationChannel(kanal)
    }

    /**
     * Prejde vsetky pravidla nad ulozenymi predpovedami a ozve sa na to, co
     * este ohlasene nebolo. Vrati pocet odoslanych upozorneni.
     *
     * [teraz] je lokalny cas v tvare "2026-09-03T14:00" — hodiny pred nim sa
     * preskakuju. Berie sa z hodin samotnej predpovede, nie zo systemoveho
     * casu telefonu: predpoved pre Sydney ma vlastnu zonu a systemovy cas by
     * v nej ukazal na uplne inu hodinu.
     */
    fun vyhodnot(ctx: Context): Int {
        val pravidla = Prefs.upozornenia(ctx).filter { it.zapnute }
        if (pravidla.isEmpty()) return 0
        val uzOhlasene = Prefs.ohlasene(ctx)
        val nove = mutableListOf<String>()
        var poslane = 0

        for (miesto in Prefs.miesta(ctx)) {
            val p = Ulozisko.nacitaj(ctx, miesto) ?: continue
            // "Teraz" v case MIESTA (`current.time`), nie prva hodina
            // predpovede: tá je polnoc dnesneho dna, takze by sa vyhodnocoval
            // aj uz odzity den a appka by zvonila na mraz, ktory clovek rano
            // zazil.
            val odCasu = p.odkedyDopredu
            if (odCasu.isBlank()) continue
            for (u in pravidla) {
                val z = Pravidla.zasah(u, miesto, p, odCasu) ?: continue
                val kluc = Pravidla.kluc(z)
                if (kluc in uzOhlasene) continue
                posli(ctx, z)
                nove += kluc
                poslane++
            }
        }
        Prefs.zapamataOhlasene(ctx, nove)
        return poslane
    }

    private fun posli(ctx: Context, z: Zasah) {
        val text = text(ctx, z)
        val otvor = PendingIntent.getActivity(
            ctx, z.hodina.cas.hashCode(),
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sprava = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(R.drawable.ic_upozornenie)
            .setContentTitle(z.miesto.nazov)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(otvor)
            .build()
        try {
            NotificationManagerCompat.from(ctx)
                .notify(Pravidla.kluc(z).hashCode(), sprava)
        } catch (e: SecurityException) {
            // Od Androidu 13 sa da povolenie odmietnut. Pad by bol horsi nez
            // neodoslane upozornenie — obrazovka Upozorneni to hovori zvlast.
        }
    }

    /** Znenie upozornenia. Vzdy obsahuje HODNOTU aj HODINU — bez nich sa
     *  neda posudit, ci sa oplati nieco robit (docs/shared-standard.md 6). */
    fun text(ctx: Context, z: Zasah): String {
        val h = Format.hodina(z.hodina.cas)
        return when (z.upozornenie.druh) {
            DruhUpozornenia.TEPLOTA_POD -> ctx.getString(
                R.string.notif_teplota_pod, z.miesto.nazov,
                Format.teplota(z.hodina.teplota), h,
                Format.hranica(z.upozornenie.hranica))
            DruhUpozornenia.TEPLOTA_NAD -> ctx.getString(
                R.string.notif_teplota_nad, z.miesto.nazov,
                Format.teplota(z.hodina.teplota), h,
                Format.hranica(z.upozornenie.hranica))
            DruhUpozornenia.ZRAZKY_AKEKOLVEK ->
                ctx.getString(R.string.notif_zrazky, z.miesto.nazov,
                              Format.zrazky(z.hodina.zrazkyMm), h)
            DruhUpozornenia.ZRAZKY_NAD ->
                ctx.getString(R.string.notif_zrazky_nad, z.miesto.nazov,
                              Format.zrazky(z.hodina.zrazkyMm), h,
                              Format.hranica(z.upozornenie.hranica))
            // Den slovom aj datumom ("St 24.9."): pri dennom uhrne hodina nic
            // nepovie, dolezite je, KTORY den treba dazdnik.
            DruhUpozornenia.ZRAZKY_DEN_NAD ->
                ctx.getString(R.string.notif_zrazky_den, z.miesto.nazov,
                              Format.zrazky(z.sucet ?: 0.0),
                              "${Format.denVTyzdni(z.zaciatok)} ${Format.denKratko(z.zaciatok)}",
                              Format.hranica(z.upozornenie.hranica))
        }
    }
}
