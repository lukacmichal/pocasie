package sk.lukac.pocasie

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Miesta, upozornenia, nastavenia obnovy a self-updatu.
 *
 * Heslo k NAS-u lezi v SIFROVANYCH prefs (kluc drzi Android Keystore); pri
 * zlyhani keystore padame spat na obycajne prefs, aby appka fungovala aj vtedy
 * — rovnaky pristup ako askener, wake-nb, meniny, stocks a homesecure.
 *
 * Zoznam miest tajny nie je, takze je v obycajnych prefs.
 */
object Prefs {
    private const val TAG = "Prefs"
    private const val FILE = "pocasie_prefs"
    private const val FILE_SECRET = "pocasie_secret"

    private const val K_MIESTA = "miesta"
    private const val K_UPOZORNENIA = "upozornenia"
    private const val K_OHLASENE = "ohlasene"

    private const val K_OBNOVA = "obnova_zapnuta"
    private const val K_OBNOVA_HODIN = "obnova_hodin"
    private const val K_LEN_WIFI = "len_wifi"
    private const val K_RANO = "ranna_obnova"
    private const val K_RANO_HODINA = "ranna_hodina"
    private const val K_RANO_ROZVRH = "ranny_rozvrh"
    private const val K_DNI = "dni"
    private const val K_HODINOVKA = "hodinovka"

    private const val K_OTVORENIE_NA_DATACH = "otvorenie_na_datach"
    private const val K_GRAF_HODIN = "graf_hodin"

    private const val K_UP_HOST = "up_host"
    private const val K_UP_SHARE = "up_share"
    private const val K_UP_PATH = "up_path"
    private const val K_UP_FILE = "up_file"
    private const val K_UP_USER = "up_user"
    private const val K_UP_PASS = "up_pass"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile
    private var secretCache: SharedPreferences? = null

    private fun secret(ctx: Context): SharedPreferences {
        secretCache?.let { return it }
        val app = ctx.applicationContext
        val sp = try {
            val kluc = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app, FILE_SECRET, kluc,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Keystore vie zlyhat po obnove zo zalohy alebo po zmene zamku
            // obrazovky. Appka bez hesla k NAS-u stale ukazuje pocasie —
            // neposlusi len self-update, a to je mensia skoda nez pad.
            Log.w(TAG, "sifrovane prefs sa nepodarili, idem na obycajne", e)
            app.getSharedPreferences(FILE_SECRET, Context.MODE_PRIVATE)
        }
        secretCache = sp
        return sp
    }

    // ─────────────────────────────── miesta ─────────────────────────────────

    fun miesta(ctx: Context): List<Miesto> =
        Miesto.zoZoznamu(sp(ctx).getString(K_MIESTA, "[]") ?: "[]")

    fun ulozMiesta(ctx: Context, zoznam: List<Miesto>) {
        sp(ctx).edit().putString(K_MIESTA, Miesto.doZoznamu(zoznam)).apply()
    }

    /** Prida miesto na koniec. To iste miesto druhy raz nic nespravi. */
    fun pridajMiesto(ctx: Context, m: Miesto): Boolean {
        val doteraz = miesta(ctx)
        if (doteraz.any { it.id == m.id }) return false
        ulozMiesta(ctx, doteraz + m)
        return true
    }

    fun zmazMiesto(ctx: Context, m: Miesto) {
        ulozMiesta(ctx, miesta(ctx).filterNot { it.id == m.id })
        // Upozornenia viazane na zmazane miesto by uz nemal kto vyhodnotit
        // a v zozname by ostali ako riadky bez miesta.
        ulozUpozornenia(ctx, upozornenia(ctx).filterNot { it.miestoId == m.id })
        Ulozisko.zabudni(ctx, m)
    }

    // ───────────────────────────── upozornenia ──────────────────────────────

    fun upozornenia(ctx: Context): List<Upozornenie> =
        Upozornenie.zoZoznamu(sp(ctx).getString(K_UPOZORNENIA, "[]") ?: "[]")

    fun ulozUpozornenia(ctx: Context, zoznam: List<Upozornenie>) {
        sp(ctx).edit().putString(K_UPOZORNENIA, Upozornenie.doZoznamu(zoznam)).apply()
    }

    /**
     * Kluce zasahov, ktore uz boli ohlasene.
     *
     * Bez tejto mnoziny by obnova na pozadi poslala tu istu spravu kazde tri
     * hodiny, kym mraz neprejde — a clovek by upozornenia vypol. Drzi sa
     * poslednych 200 klucov; starsie uz aj tak nemaju co pripomenut, lebo
     * predpoved siaha nanajvys tyzden dopredu.
     */
    fun ohlasene(ctx: Context): Set<String> =
        sp(ctx).getStringSet(K_OHLASENE, emptySet()) ?: emptySet()

    fun zapamataOhlasene(ctx: Context, kluce: Collection<String>) {
        if (kluce.isEmpty()) return
        val nove = (ohlasene(ctx) + kluce).toList().takeLast(200).toSet()
        sp(ctx).edit().putStringSet(K_OHLASENE, nove).apply()
    }

    // ───────────────────────────── obnova a data ────────────────────────────

    fun obnovaZapnuta(ctx: Context): Boolean = sp(ctx).getBoolean(K_OBNOVA, true)
    fun nastavObnovu(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_OBNOVA, v).apply()

    /** Ako casto sa stahuje na pozadi. 3 h je kompromis medzi datami a tym,
     *  aby upozornenie na rannu hmlu prislo vecer, nie az rano. */
    fun obnovaHodin(ctx: Context): Int =
        sp(ctx).getInt(K_OBNOVA_HODIN, 3).coerceIn(1, 24)

    fun nastavObnovuHodin(ctx: Context, h: Int) =
        sp(ctx).edit().putInt(K_OBNOVA_HODIN, h.coerceIn(1, 24)).apply()

    /** Predvolene ZAPNUTE: slub „na mobilnych datach nic" sa lepsie rusi
     *  vedome, nez sa objavuje prekvapenie na fakture. */
    fun lenWifi(ctx: Context): Boolean = sp(ctx).getBoolean(K_LEN_WIFI, true)
    fun nastavLenWifi(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_LEN_WIFI, v).apply()

    /**
     * Stiahnut raz denne o pevnej hodine (predvolene 6:00) — VZDY len po Wi-Fi.
     *
     * Interval z [obnovaHodin] hovori „ako casto", nie „kedy". Pri trojhodinovom
     * intervale moze posledne stiahnutie vyjst na 4:45 a clovek odchadza z domu
     * s predpovedou, ktora uz vie o hodinu menej. Toto je poistka na rano: kym
     * je telefon doma na nabijacke, stiahne sa to najnovsie.
     *
     * [lenWifi] sa na to NEVZTAHUJE zamerne. Ten je vypinac pre intervalovu
     * obnovu, kde si clovek moze mobilne data vedome povolit; ranne stiahnutie
     * je zo svojej podstaty „sprav to, kym som doma na Wi-Fi" a na mobilnych
     * datach nema co robit.
     */
    fun rannaObnova(ctx: Context): Boolean = sp(ctx).getBoolean(K_RANO, true)
    fun nastavRannuObnovu(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_RANO, v).apply()

    fun rannaHodina(ctx: Context): Int =
        sp(ctx).getInt(K_RANO_HODINA, 6).coerceIn(0, 23)

    fun nastavRannuHodinu(ctx: Context, h: Int) =
        sp(ctx).edit().putInt(K_RANO_HODINA, h.coerceIn(0, 23)).apply()

    /**
     * Podpis uz naplanovaneho ranneho rozvrhu (napr. `rano=6`).
     *
     * Appka planuje pri kazdom starte. Keby sa rozvrh zakazdym prepisal novym
     * odkladom, posuval by sa donekonecna a o 6:00 by nesiahol nikdy — preto
     * sa prepisuje len vtedy, ked sa hodina naozaj zmenila.
     */
    fun rannyRozvrh(ctx: Context): String = sp(ctx).getString(K_RANO_ROZVRH, "") ?: ""

    fun zapamataRannyRozvrh(ctx: Context, podpis: String) =
        sp(ctx).edit().putString(K_RANO_ROZVRH, podpis).apply()

    fun dni(ctx: Context): Int = sp(ctx).getInt(K_DNI, 7).coerceIn(1, 16)
    fun nastavDni(ctx: Context, d: Int) =
        sp(ctx).edit().putInt(K_DNI, d.coerceIn(1, 16)).apply()

    fun hodinovka(ctx: Context): Boolean = sp(ctx).getBoolean(K_HODINOVKA, true)
    fun nastavHodinovku(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_HODINOVKA, v).apply()

    /**
     * Stahovat pri otvoreni appky aj na mobilnych datach?
     *
     * Predvolene NIE (3. 9. 2026). Otvorenie appky nie je ziadost o nove data
     * — clovek sa castejsie pozera na to, co uz je stiahnute. Na datach preto
     * stahuje vylucne tlacidlo ↻ alebo potiahnutie zoznamu; na Wi-Fi sa
     * spravanie nemeni.
     *
     * S [lenWifi] sa to nebije: ten sa tyka obnovy NA POZADI, tento otvorenia
     * appky. Su to dve rozne cesty k tomu istemu prenosu a kazda ma vlastny
     * vypinac.
     */
    fun otvorenieNaDatach(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_OTVORENIE_NA_DATACH, false)

    fun nastavOtvorenieNaDatach(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_OTVORENIE_NA_DATACH, v).apply()

    /**
     * Rozsah grafu v detaile (hodin). Pamata sa preto, ze kto sleduje tri
     * dni, sleduje ich pri kazdom miesta — nie raz.
     */
    fun grafHodin(ctx: Context): Int =
        sp(ctx).getInt(K_GRAF_HODIN, Graf.HODIN_WIDGET).coerceIn(1, 24 * 16)

    fun nastavGrafHodin(ctx: Context, hodin: Int) =
        sp(ctx).edit().putInt(K_GRAF_HODIN, hodin.coerceIn(1, 24 * 16)).apply()

    // ───────────────────────────── self-update ──────────────────────────────

    fun updateConfig(ctx: Context): UpdateConfig {
        val s = sp(ctx)
        val predvolene = UpdateConfig()
        return UpdateConfig(
            host = s.getString(K_UP_HOST, null)?.ifBlank { null } ?: predvolene.host,
            shareName = s.getString(K_UP_SHARE, null)?.ifBlank { null } ?: predvolene.shareName,
            remotePath = s.getString(K_UP_PATH, null)?.ifBlank { null } ?: predvolene.remotePath,
            apkFileName = s.getString(K_UP_FILE, null)?.ifBlank { null } ?: predvolene.apkFileName,
            username = s.getString(K_UP_USER, null)?.ifBlank { null } ?: predvolene.username,
            password = secret(ctx).getString(K_UP_PASS, null)?.ifBlank { null } ?: predvolene.password,
        )
    }

    fun ulozUpdateConfig(ctx: Context, c: UpdateConfig) {
        sp(ctx).edit()
            .putString(K_UP_HOST, c.host)
            .putString(K_UP_SHARE, c.shareName)
            .putString(K_UP_PATH, c.remotePath)
            .putString(K_UP_FILE, c.apkFileName)
            .putString(K_UP_USER, c.username)
            .apply()
        secret(ctx).edit().putString(K_UP_PASS, c.password).apply()
    }
}
