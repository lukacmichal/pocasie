package sk.lukac.pocasie

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * Widget na plochu: sledovane miesta s teplotou a rozsahom dna.
 *
 * **Widget sam od seba na siet nechodi.** Pri tiknuti systemu kresli len to,
 * co uz je na disku ([Ulozisko]). Inak by kazda polhodina znamenala dalsi
 * prenos na mobilnych datach — a minimalizacia dat je pri tejto appke zadanie,
 * nie ozdoba. Cerstvu predpoved prinasa [Obnova] (len na Wi-Fi, ked je zapnuta)
 * alebo otvorenie appky.
 *
 * Na siet siahne jedine po tuknuti na ↻ — to je rovnaky suhlas ako potiahnutie
 * zoznamu v appke („chcem to teraz") a cerstvost cache sa vtedy obchadza.
 *
 * **Zoznam sa roluje PRSTOM.** Riadky plni `GridView` cez [MiestaService]
 * a launcher si ich pyta sam podla toho, kam clovek posunul. Vysku riesit
 * netreba, zoznam si ju zoberie celu.
 *
 * **Jeden stlpec, vzdy** (3. 9. 2026). Sirsi widget dostane sirsie riadky, nie
 * druhy stlpec. Dvojstlpcove usporiadanie ma zmysel v stocks, kde je polozka
 * kratke "AAPL 231,4"; nazov miesta je dlhy ("Nitrianske Hrnciarovce") a v
 * polovicnej sirke sa orezal. Riadok cez celu sirku je aj pokojnejsi na
 * citanie — pohlad ide zhora nadol, nie do mriezky.
 *
 * **Farby sa nastavuju vyslovne**, nie cez `values-night`. Widget kresli
 * launcher a `values-night` by sa v nom riadil nastavenim TELEFONU — kto si
 * zapne cierny rezim v appke, mal by biely widget.
 */
class PocasieWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { prekresli(ctx, manager, it) }
    }

    /**
     * Po zmene velkosti prekresli. Pocet stlpcov je odvtedy vzdy jeden, ale
     * hlavicka s vekom dat sa pri uzsom widgete oreze inak a prazdny stav ma
     * inu vysku — jedno prekreslenie je lacnejsie nez hladat, kedy netreba.
     */
    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        manager: AppWidgetManager,
        id: Int,
        novy: Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(ctx, manager, id, novy)
        prekresli(ctx, manager, id)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == AKCIA_OBNOV) obnov(ctx)
    }

    private fun obnov(ctx: Context) {
        // Sietove volanie z receivera musi drzat proces nazive, kym dobehne —
        // bez goAsync() by ho system mohol zabit hned po navrate z onReceive.
        val hotovo = goAsync()
        val app = ctx.applicationContext
        ukazStav(app, app.getString(R.string.w_obnovujem))

        Thread {
            try {
                // vynutene = cerstvost cache sa obchadza; tuknutie na ↻ JE
                // ziadost o nove cisla, aj na mobilnych datach.
                Obnova.stiahniVsetko(app, vynutene = true)
                Alerty.vyhodnot(app)
            } catch (e: Exception) {
                // Vypadok siete widget nezhodi: prekreslenie nizsie ukaze
                // poslednu znamu predpoved a stary cas, z ktoreho je vidiet,
                // ze cerstve data neprisli.
                Log.w(TAG, "obnova widgetu zlyhala", e)
            } finally {
                prekresliVsetky(app)
                hotovo.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "PocasieWidget"

        /** Tuknutie na sipku vo widgete. Explicitny intent, netreba filter. */
        const val AKCIA_OBNOV = "sk.lukac.pocasie.OBNOV_WIDGET"

        private const val KOD_OBNOVY = 8311
        private const val KOD_POLOZKY = 8312

        /**
         * Priznaky pre SABLONU kolekcie — musi byt MENITELNA.
         *
         * Sablona je prazdny intent bez `miesto_id`; ktore miesto sa otvori,
         * dopĺňa az vyplnovy intent riadku (`setOnClickFillInIntent`). Do
         * NEMENNEHO `PendingIntent`u sa vsak vlozit neda: host ho ticho
         * zahodi, detail dostane prazdne id a hned sa zavrie. Naživo to
         * vyzeralo tak, ze tuknutie na riadok widgetu „nerobi nic".
         * (Overene 4. 9. 2026 na Androide 10, kde `FLAG_IMMUTABLE` stalo
         * v kode od zaciatku.)
         *
         * `FLAG_MUTABLE` existuje az od Androidu 12; nizsie je menitelnost
         * predvolena, takze staci neuviest `FLAG_IMMUTABLE`.
         *
         * Ostatne tlacidla widgetu (↻, hlavicka) nemenne ostavaju — nic sa
         * do nich nedoplna a nemenny PendingIntent je bezpecnejsi.
         */
        private val PRIZNAKY_SABLONY: Int
            get() = PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0

        // Zakladna velkost pisma v hlavicke (sp). Nasobi sa krokom PRE WIDGET
        // z Nastaveni — RemoteViews `fontScale` z kontextu appky nepocuvaju.
        private const val SP_CAS = 12f

        fun prekresliVsetky(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            manager.getAppWidgetIds(
                ComponentName(ctx, PocasieWidgetProvider::class.java)
            ).forEach { prekresli(ctx, manager, it) }
        }

        /**
         * Prepise hlavicku vo vsetkych widgetoch, nic ineho sa nedotkne.
         *
         * Pouziva sa na „Obnovujem…" hned po tuknuti: bez okamzitej odozvy
         * clovek tukne druhy a treti raz, lebo si mysli, ze sa nic nedeje.
         */
        private fun ukazStav(ctx: Context, text: String) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(
                ComponentName(ctx, PocasieWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val v = RemoteViews(ctx.packageName, R.layout.widget_pocasie)
            v.setTextViewText(R.id.w_cas, text)
            ids.forEach { manager.partiallyUpdateAppWidget(it, v) }
        }

        private fun prekresli(ctx: Context, manager: AppWidgetManager, id: Int) {
            val v = RemoteViews(ctx.packageName, R.layout.widget_pocasie)

            // Farby patria zvolenemu rezimu appky, nie nastaveniu telefonu.
            val tema = Vzhlad.temaKontext(ctx)
            val tlmena = ContextCompat.getColor(tema, R.color.text_vedlajsi)
            v.setInt(R.id.w_koren, "setBackgroundColor",
                ContextCompat.getColor(tema, R.color.widget_pozadie))
            v.setInt(R.id.w_obnov, "setColorFilter",
                ContextCompat.getColor(tema, R.color.ikona))

            val faktor = Vzhlad.faktorWidgetu(ctx)
            v.setTextViewTextSize(R.id.w_cas, TypedValue.COMPLEX_UNIT_SP, SP_CAS * faktor)
            v.setTextColor(R.id.w_cas, tlmena)
            v.setTextColor(R.id.w_prazdno, tlmena)

            val miesta = Prefs.miesta(ctx)
            v.setTextViewText(R.id.w_cas, hlavicka(ctx, miesta))
            v.setTextViewText(R.id.w_prazdno, prazdnyStav(ctx, miesta.size))

            // Pocet stlpcov NASTAVUJE LAYOUT (`android:numColumns="1"`), nie kod.
            //
            // `setInt(..., "setNumColumns", ...)` vyzera nevinne, ale host ho
            // odmietne: „GridView can't use method with RemoteViews:
            // setNumColumns(int)". Cely widget potom skonci hlaskou „Problém
            // s načítaním miniaplikácií" — teda nie orezany, ale ziadny.
            // Overene naživo 4. 9. 2026 na EMUI; do vtedy tu to volanie bolo
            // a widget sa nedal na plochu vobec pridat.

            // Kazdy widget ma vlastny adapter: `data` v intente ich odlisi,
            // inak by si dva widgety na ploche zdielali jednu factory a zmena
            // poctu stlpcov v jednom by prekreslila aj ten druhy.
            val obsah = Intent(ctx, MiestaService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("pocasie://widget/$id"))
            v.setRemoteAdapter(R.id.w_mriezka, obsah)
            v.setEmptyView(R.id.w_mriezka, R.id.w_prazdno)

            // Tuknutie na miesto otvori jeho detail. Kolekcia posiela vyplnovy
            // intent; cielovu triedu urcuje az tato sablona.
            v.setPendingIntentTemplate(
                R.id.w_mriezka,
                PendingIntent.getActivity(
                    ctx, KOD_POLOZKY,
                    Intent(ctx, DetailActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PRIZNAKY_SABLONY,
                ),
            )

            v.setOnClickPendingIntent(
                R.id.w_obnov,
                PendingIntent.getBroadcast(
                    ctx, KOD_OBNOVY,
                    Intent(ctx, PocasieWidgetProvider::class.java).setAction(AKCIA_OBNOV),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            // Tuknutie na hlavicku (mimo ↻) otvori appku. Na koren sa to dat
            // NEDA — prebilo by to rolovanie aj tuknutie na polozku.
            v.setOnClickPendingIntent(
                R.id.w_cas,
                PendingIntent.getActivity(
                    ctx, 0,
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

            manager.updateAppWidget(id, v)
            // Bez tohto by launcher kreslil to, co mal odlozene — obsah
            // kolekcie si sam od seba neoveruje.
            manager.notifyAppWidgetViewDataChanged(id, R.id.w_mriezka)
        }

        /**
         * „Obnovené pred 20 min" — vek NAJSTARSIEHO miesta.
         *
         * Zamerne najstarsieho, nie najnovsieho: hlavicka ma povedat, ako stare
         * su data, na ktore sa clovek pozera. Keby ukazovala najnovsie, jedno
         * cerstve miesto by zakrylo tri tyzdne stare.
         */
        private fun hlavicka(ctx: Context, miesta: List<Miesto>): String {
            if (miesta.isEmpty()) return ctx.getString(R.string.w_ziadne_miesta)
            val najstarsie = miesta
                .mapNotNull { Ulozisko.nacitaj(ctx, it)?.stiahnuteMs }
                .minOrNull()
                // Prazdna cache po prvej instalacii vyzera ako porucha.
                ?: return ctx.getString(R.string.w_prazdno)
            val obnovene = ctx.getString(
                R.string.w_obnovene,
                Format.predAko(System.currentTimeMillis() - najstarsie),
            )
            return obnovene + slnkoAMesiac(ctx, miesta)
        }

        /**
         * „ · ↑06:24 ↓19:31 🌔" do hlavicky widgetu.
         *
         * Slnko sa berie z PRVEHO miesta v zozname — je to miesto, kde clovek
         * je, a vychod v Nitre a v Sydney nie je jeden udaj. Ked ho cache
         * nema (stara odpoved z verzie pred 1.6), vypadne len ta cast; faza
         * mesiaca sa pocita a je tam vzdy.
         */
        private fun slnkoAMesiac(ctx: Context, miesta: List<Miesto>): String {
            val dnes = java.time.LocalDate.now()
            val den = miesta.firstOrNull()
                ?.let { Ulozisko.nacitaj(ctx, it) }
                ?.dni?.firstOrNull { it.datum == dnes.toString() }
            val slnko = if (den != null && den.vychodHm.isNotEmpty() &&
                den.zapadHm.isNotEmpty()
            ) " · ↑${den.vychodHm} ↓${den.zapadHm}" else ""
            return slnko + " " + Mesiac.znak(dnes)
        }

        /** Co stoji namiesto mriezky, ked v nej nic nie je. */
        private fun prazdnyStav(ctx: Context, spolu: Int): String =
            if (spolu == 0) ctx.getString(R.string.w_ziadne_miesta)
            else ctx.getString(R.string.w_prazdno)
    }
}
