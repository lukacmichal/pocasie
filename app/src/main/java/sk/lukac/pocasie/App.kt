package sk.lukac.pocasie

import android.app.Application

/**
 * Vstupny bod procesu — appky aj widgetu (bezia v tom istom procese).
 *
 * Dve veci, ktore musia byt hotove skor nez sa nakresli cokolvek:
 *
 * 1. **Nocny rezim** podla nastavenia appky. Keby sa nasadzoval az v aktivite,
 *    prva obrazovka by blikla vo svetlom.
 * 2. **Kanal notifikacii.** Musi existovat skor, nez sa da poslat prve
 *    upozornenie — a to sa moze stat hned pri prvom stiahnuti predpovede.
 * 3. **Rozvrh obnovy.** Preplanuje sa pri kazdom starte, aby zmena
 *    v nastaveniach platila aj vtedy, ked ju system medzitym zahodil.
 *
 * Predpoved sa tu ZAMERNE nenacitava: kazda obrazovka si ju cita z disku sama,
 * takze cache v pamati by bola len druhe miesto, kde moze zostarnut.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Vzhlad.pouzi(this)
        Alerty.pripravKanal(this)
        Obnova.naplanuj(this)
    }
}
