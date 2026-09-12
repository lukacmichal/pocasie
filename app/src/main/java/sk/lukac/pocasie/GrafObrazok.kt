package sk.lukac.pocasie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import androidx.core.content.ContextCompat

/**
 * Graf pre WIDGET — ten isty tvar ako v appke, ale ako obrazok.
 *
 * **Preco obrazok.** Widget kresli systemovy launcher cez `RemoteViews` a tie
 * poznaju len hrstku hotovych prvkov; vlastny `View` (teda [GrafView]) sa do
 * nich poslat neda. Jedina cesta je nakreslit graf do `Bitmap`u a poslat ho
 * ako obsah `ImageView`. Kresba je spolocna ([GrafKresba]), takze widget
 * a appka ukazuju ten isty tvar.
 *
 * **Preco RGB_565 a strop na sirku.** Kazdy riadok widgetu nesie vlastny
 * obrazok a vsetky idu cez `RemoteViews` do ineho procesu, kde plati strop na
 * pamat. `ARGB_8888` v plnej sirke displeja by na styri miesta znamenal skoro
 * tri megabajty; `RGB_565` je polovica a strop [MAX_SIRKA] drzi obrazok
 * v rozumnej velkosti aj na tablete. Priehladnost netreba — pozadie widgetu
 * sa do obrazku vyplni.
 *
 * **Farby si podava sam.** Widget nema temu appky: `values-night` sa v nom
 * riadi nastavenim TELEFONU, nie nastavenim appky, takze farby chodia
 * z [Vzhlad.temaKontext].
 */
object GrafObrazok {

    /** Nad tuto sirku (px) sa obrazok uz nekresli — ImageView si ho roztiahne. */
    private const val MAX_SIRKA = 720

    /**
     * Strop na pocet bodov obrazka.
     *
     * `RemoteViews` idu cez binder do procesu launchera a ten ma na ne strop
     * pamate. Pri RGB_565 je to ~480 kB na jeden riadok, co znesie aj vysoky
     * graf na jedinom miesta. Pod strop sa obrazok zmensi CELY (obe strany),
     * nie orezanim — pomer stran musi ostat, lebo podla neho si `ImageView`
     * s `adjustViewBounds` pocita svoju vysku.
     */
    private const val MAX_BODOV = 240_000

    /**
     * @param sirkaPx sirka miesta, kam sa obrazok kresli (px)
     * @param vyskaPx vyska obrazka (px)
     * @param faktorPisma krok pisma widgetu z Nastaveni ([Vzhlad.faktorWidgetu])
     */
    fun vytvor(
        ctx: Context,
        stlpce: List<Graf.Stlpec>,
        sirkaPx: Int,
        vyskaPx: Int,
        faktorPisma: Float,
    ): Bitmap? {
        if (stlpce.isEmpty() || sirkaPx <= 0 || vyskaPx <= 0) return null
        // Zmensuje sa VZDY v oboch smeroch naraz. Do 5. 9. 2026 sa orezavala
        // len sirka a vyska ostavala — s `fitXY` to nebolo vidiet, lenze odvtedy
        // si vysku riadku pocita `ImageView` z pomeru stran obrazka, takze
        // orezanie jednej strany by graf naťahovalo.
        var mierka = 1.0
        if (sirkaPx > MAX_SIRKA) mierka = MAX_SIRKA.toDouble() / sirkaPx
        val bodov = (sirkaPx * mierka) * (vyskaPx * mierka)
        if (bodov > MAX_BODOV) mierka *= Math.sqrt(MAX_BODOV / bodov)
        val sirka = (sirkaPx * mierka).toInt().coerceAtLeast(1)
        val vyska = (vyskaPx * mierka).toInt().coerceAtLeast(1)
        val tema = Vzhlad.temaKontext(ctx)
        val obrazok = Bitmap.createBitmap(sirka, vyska, Bitmap.Config.RGB_565)
        val platno = Canvas(obrazok)
        platno.drawColor(ContextCompat.getColor(tema, R.color.widget_pozadie))

        val metriky = ctx.resources.displayMetrics
        GrafKresba.nakresli(
            c = platno,
            sirka = sirka.toFloat(),
            vyska = vyska.toFloat(),
            stlpce = stlpce,
            farby = GrafKresba.Farby(
                dazd = ContextCompat.getColor(tema, R.color.zrazky),
                sneh = ContextCompat.getColor(tema, R.color.text_vedlajsi),
                teplota = ContextCompat.getColor(tema, R.color.teplo),
                ciara = ContextCompat.getColor(tema, R.color.widget_ciara),
                text = ContextCompat.getColor(tema, R.color.text_vedlajsi),
                vyber = ContextCompat.getColor(tema, R.color.text_hlavny),
            ),
            hustota = metriky.density,
            // `applyDimension` s COMPLEX_UNIT_SP by pripocitalo systemove
            // zvacsenie pisma DVAKRAT — widget uz svoj krok dostal
            // vo `faktorPisma`. Preto sa rata z hustoty, nie zo `scaledDensity`.
            velkostTextu = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 9f, metriky) * faktorPisma,
            // Popisky dni pod 24-hodinovym grafom by boli dva datumy na sirku
            // dlane; hodina povie viac („o siedmej rannej prsi").
            popisyHodin = true,
            popisyDni = false,
        )
        return obrazok
    }
}
