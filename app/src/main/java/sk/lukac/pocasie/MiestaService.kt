package sk.lukac.pocasie

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat

/**
 * Obsah rolovatelnej mriezky vo widgete.
 *
 * Riadky sa nedaju vygenerovat natvrdo do layoutu — rolovanie tahom sa
 * v `RemoteViews` da jedine cez kolekciu plnenu z `RemoteViewsService`.
 * Launcher si potom riadky pyta sam podla toho, kam clovek posunul.
 *
 * **Na siet sa nesiaha.** Factory cita vylucne z disku ([Ulozisko]) — cerstvu
 * predpoved prinesie ↻, obnova na pozadi alebo otvorenie appky.
 *
 * Farby aj velkosti pisma sa nastavuju VYSLOVNE: kresli to launcher, takze
 * `values-night` sa v nom riadi nastavenim TELEFONU a `fontScale` z kontextu
 * appky nepocuva vobec.
 */
class MiestaService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MiestaFactory(
            applicationContext,
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ),
        )
}

/**
 * @param widgetId kvoli SIRKE grafu: obrazok sa kresli na tolko pixelov, kolko
 *   ma widget naozaj, inak by bol na sirokom widgete rozmazany a na uzkom
 *   zbytocne velky. Neznamy id (stary host) padne na sirku displeja.
 */
class MiestaFactory(
    private val ctx: Context,
    private val widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
) : RemoteViewsService.RemoteViewsFactory {

    /**
     * Odfotene data. Factory sa pyta z ineho vlakna nez sa meni cache, takze
     * sa raz nacitaju do zoznamu a z neho sa uz len cita — inak by sa pri
     * obnove uprostred rolovania menil pocet poloziek pod rukami.
     */
    private var polozky: List<Polozka> = emptyList()

    private data class Polozka(val miesto: Miesto, val predpoved: Predpoved?)

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        polozky = Prefs.miesta(ctx).map { Polozka(it, Ulozisko.nacitaj(ctx, it)) }
    }

    override fun onDestroy() {
        polozky = emptyList()
    }

    override fun getCount() = polozky.size

    override fun getItemId(position: Int) = position.toLong()

    override fun hasStableIds() = true

    override fun getViewTypeCount() = 1

    /**
     * Placeholder, kym sa polozka nacita. Vlastny layout netreba — prazdna
     * bunka je menej rusiva nez blikajuci text.
     */
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.item_widget_miesto)
        val p = polozky.getOrNull(position) ?: return v

        val tema = Vzhlad.temaKontext(ctx)
        val tlmena = ContextCompat.getColor(tema, R.color.text_vedlajsi)
        val hlavna = ContextCompat.getColor(tema, R.color.text_hlavny)
        v.setInt(R.id.b_ciara, "setBackgroundColor",
            ContextCompat.getColor(tema, R.color.widget_ciara))

        val faktor = Vzhlad.faktorWidgetu(ctx)
        val pred = p.predpoved
        val dnes = pred?.dnes

        // ── prvy riadok: nazov · teplota ─────────────────────────────────
        v.setTextViewText(R.id.b_miesto, p.miesto.nazov)
        v.setTextViewTextSize(R.id.b_miesto, TypedValue.COMPLEX_UNIT_SP, SP_MIESTO * faktor)
        // Miesto bez predpovede zosivie CELE. Bez toho vyzeralo rovnako
        // „platne" ako ostatne a pomlcka vpravo splynula s pozadim — rovnaka
        // chyba, akou v askener-i mesiace ticho sedelo `DGB` bez ceny.
        v.setTextColor(R.id.b_miesto, if (pred == null) tlmena else hlavna)

        v.setTextViewText(
            R.id.b_teplota,
            // Pomlcka, nie prazdno: „este nemam predpoved" ma byt vidiet.
            if (pred == null) ctx.getString(R.string.ver_none)
            else Format.teplota(pred.teplotaTeraz ?: dnes?.maxTeplota),
        )
        v.setTextViewTextSize(R.id.b_teplota, TypedValue.COMPLEX_UNIT_SP, SP_TEPLOTA * faktor)
        v.setTextColor(R.id.b_teplota, if (pred == null) tlmena else hlavna)

        // ── druhy riadok: rozsah dna · zrazky ────────────────────────────
        v.setTextViewText(
            R.id.b_rozsah,
            if (dnes == null) "" else ctx.getString(
                R.string.rozsah_dna,
                Format.teplota(dnes.minTeplota), Format.teplota(dnes.maxTeplota)),
        )
        v.setTextViewTextSize(R.id.b_rozsah, TypedValue.COMPLEX_UNIT_SP, SP_RIADOK * faktor)
        v.setTextColor(R.id.b_rozsah, tlmena)

        // Zrazky sa ukazuju, LEN ked su. „0 mm" na kazdom riadku by bol sum,
        // v ktorom by sa tie dva dazdive dni stratili.
        val mm = dnes?.zrazkyMm ?: 0.0
        val zrazkyText = if (mm >= 0.1)
            ctx.getString(R.string.zrazky_dnes, Format.zrazky(mm)) else ""
        v.setViewVisibility(R.id.b_zrazky, if (zrazkyText.isEmpty()) View.GONE else View.VISIBLE)
        if (zrazkyText.isNotEmpty()) {
            v.setTextViewText(R.id.b_zrazky, zrazkyText)
            v.setTextViewTextSize(R.id.b_zrazky, TypedValue.COMPLEX_UNIT_SP, SP_RIADOK * faktor)
            v.setTextColor(R.id.b_zrazky, ContextCompat.getColor(tema, R.color.zrazky))
        }

        // ── graf na najblizsich 24 h ─────────────────────────────────────
        //
        // Widget ukazuje to iste co appka, len skratene na jeden den: „ako
        // bude dnes a ci brat dazdnik" je otazka, kvoli ktorej je widget na
        // ploche. Kresli sa z hodinovky; ked je v Nastaveniach vypnuta,
        // graf zmizne (prazdny obdlznik vyzera ako chyba).
        val hodiny = pred?.hodinyOd(pred.odkedyDopredu).orEmpty()
        val stlpce = Graf.zHodin(hodiny, Graf.HODIN_WIDGET)
        val obrazok = GrafObrazok.vytvor(
            ctx, stlpce, sirkaGrafuPx(), vyskaGrafuPx(), faktor)
        if (obrazok == null) {
            v.setViewVisibility(R.id.b_graf, View.GONE)
        } else {
            v.setViewVisibility(R.id.b_graf, View.VISIBLE)
            v.setImageViewBitmap(R.id.b_graf, obrazok)
        }

        // Vyplnovy intent: sablonu (cielovu triedu) urcil provider.
        v.setOnClickFillInIntent(
            R.id.b_koren,
            Intent().putExtra(DetailActivity.EXTRA_ID, p.miesto.id),
        )
        return v
    }

    /**
     * Sirka obrazka grafu v pixeloch.
     *
     * Berie sa zo SKUTOCNEJ sirky widgetu (host ju hlasi v dp), zmensena
     * o odsadenie widgetu. Ked ju host neposlal, padne sa na sirku displeja —
     * je to horny odhad, takze graf bude nanajvys ostrejsi, nez treba.
     */
    private fun sirkaGrafuPx(): Int {
        val metriky = ctx.resources.displayMetrics
        val sirkaDp = if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) 0
        else AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val px = if (sirkaDp > 0) ((sirkaDp - ODSADENIE_DP) * metriky.density).toInt()
                 else metriky.widthPixels
        return px.coerceAtLeast((120 * metriky.density).toInt())
    }

    /**
     * Vyska grafu v pixeloch — TOLKO, KOLKO WIDGET DOVOLI.
     *
     * Do 5. 9. 2026 to bolo pevnych 52 dp a pri jedinom sledovanom mieste tak
     * dve tretiny widgetu ostali prazdne: staznost znela „ked mam zobrazene len
     * jedno mesto, widget je zbytocne velky". Teraz sa volne miesto rozdeli
     * medzi miesta a graf dostane, co po textovych riadkoch zvysi. Pri troch
     * a viac miestach vyjde strop [VYSKA_GRAFU_DP] a spravanie je ako predtym.
     *
     * Vyska sa NEDA nastavit cez `RemoteViews` (`setViewLayoutHeight` je az od
     * Androidu 12 a domaci telefon ma 10). Preto ma `b_graf` vysku
     * `wrap_content` s `adjustViewBounds` a riadi ju POMER STRAN obrazka —
     * jedine, co sa da poslat aj na starsom systeme.
     */
    private fun vyskaGrafuPx(): Int {
        val metriky = ctx.resources.displayMetrics
        val volneDp = volnaVyskaDp()
        val pocet = polozky.size.coerceAtLeast(1)
        val naPolozku = if (volneDp <= 0) 0 else volneDp / pocet
        val vyskaDp = (naPolozku - TEXT_DP).coerceIn(VYSKA_GRAFU_DP, VYSKA_GRAFU_MAX_DP)
        return (vyskaDp * metriky.density).toInt()
    }

    /**
     * Kolko dp ostane na mriezku po hlavicke a odsadeni. Nula = host vysku
     * nehlasi (stary launcher), vtedy sa graf drzi predvolenej vysky.
     */
    private fun volnaVyskaDp(): Int {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return 0
        val vyskaDp = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (vyskaDp <= 0) return 0
        return (vyskaDp - HLAVICKA_DP - ODSADENIE_DP).coerceAtLeast(0)
    }

    private companion object {
        /** Vodorovne odsadenie widgetu (2× padding korena). */
        const val ODSADENIE_DP = 20

        /** Riadok s casom a ↻ nad mriezkou (`widget_pocasie.xml`). */
        const val HLAVICKA_DP = 32

        /**
         * Co v riadku zaberie vsetko okrem grafu: ciara, dva textove riadky
         * a ich odsadenia (`item_widget_miesto.xml`).
         */
        const val TEXT_DP = 63

        /** Najmensia vyska grafu — pod nou uz z krivky nic necitat. */
        const val VYSKA_GRAFU_DP = 52

        /** A najvacsia: nad tym uz graf nepovie viac, len zoberie miesto. */
        const val VYSKA_GRAFU_MAX_DP = 160

        const val SP_MIESTO = 17f
        const val SP_TEPLOTA = 18f
        const val SP_RIADOK = 13f
    }
}
