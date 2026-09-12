package sk.lukac.pocasie

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Predpoved na disku — jeden subor na miesto.
 *
 * **Preco na disk a nie do pamate:** appku system zabije pri kazdom upratovani
 * a widget aj obnova na pozadi bezia mimo nej. Cache v pamati by znamenala,
 * ze sa po kazdom otvoreni stahuje znova — teda presny opak toho, co appka
 * slubuje. (Rovnaka chyba bola v `askener` do 28. 8. 2026.)
 *
 * **Preco surovy JSON a nie vlastny format:** to, co prislo zo servera, je
 * jediny tvar, o ktorom sa vie, ze sa da rozparsovat. Vlastny format by
 * znamenal druhy parser a druhe miesto, kde sa da spravit chyba.
 *
 * Cas stiahnutia sa berie z **casu zmeny suboru** (`lastModified`), nie
 * z obsahu: JSON od Open-Meteo ho neobsahuje a doplnat ho don rucne by
 * znamenalo prepisovat cudziu odpoved.
 */
object Ulozisko {
    private const val TAG = "Ulozisko"
    private const val PRIECINOK = "predpoved"

    private fun priecinok(ctx: Context): File =
        File(ctx.applicationContext.filesDir, PRIECINOK).apply { mkdirs() }

    private fun subor(ctx: Context, m: Miesto): File =
        File(priecinok(ctx), m.id.replace(',', '_').replace('-', 'm') + ".json")

    fun uloz(ctx: Context, m: Miesto, json: String) {
        try {
            subor(ctx, m).writeText(json)
        } catch (e: Exception) {
            // Plny disk nesmie zhodit obnovu — appka bude len o beh starsia.
            Log.w(TAG, "predpoved sa nepodarilo ulozit", e)
        }
    }

    fun nacitaj(ctx: Context, m: Miesto): Predpoved? {
        val f = subor(ctx, m)
        if (!f.exists()) return null
        return try {
            Predpoved.zJson(f.readText(), f.lastModified())
        } catch (e: Exception) {
            Log.w(TAG, "predpoved sa neda precitat, mazem", e)
            f.delete()
            null
        }
    }

    fun zabudni(ctx: Context, m: Miesto) {
        subor(ctx, m).delete()
    }

    /**
     * Je ulozena predpoved dost cerstva na to, aby sa nestahovalo?
     *
     * Prah je polovica intervalu obnovy, najmenej 30 minut. Kratsi prah by
     * znamenal, ze rucne potiahnutie zoznamu stiahne to iste znova; dlhsi by
     * ukazoval stare cisla clovek, ktory prave preto appku otvoril.
     */
    fun jeCerstva(p: Predpoved?, obnovaHodin: Int, teraz: Long): Boolean {
        if (p == null) return false
        val prah = maxOf(obnovaHodin * 3600_000L / 2, 30 * 60_000L)
        return teraz - p.stiahnuteMs < prah
    }
}
