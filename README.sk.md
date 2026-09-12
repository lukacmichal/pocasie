# Počasie

Predpoveď pre miesta, ktoré si vyberieš, s **upozorneniami na teplotu
a zrážky** — a s dôrazom na to, aby appka sťahovala čo najmenej dát.

Verzia 1.8 (9). Postavená 2. 9. 2026, widget 3. 9. 2026, graf zrážok a delené
počítadlo dát 3. 9. 2026, teplota v grafe, rozsah 24 h – 7 dní a graf aj vo
widgete 4. 9. 2026, návrhy miest počas písania, graf vo widgete na celú výšku
a sekcia Verzia v Nastaveniach 5. 9. 2026, východ a západ slnka s fázou mesiaca
6. 9. 2026, **ranné stiahnutie o pevnej hodine výhradne cez Wi-Fi**
7. 9. 2026, **mriežka grafu hustá podľa miesta** 12. 9. 2026.

| Vec | Hodnota |
|---|---|
| Zdroj dát | [Open-Meteo](https://open-meteo.com) — bez kľúča, bez registrácie |
| Balík | `sk.lukac.pocasie`, minSdk 26, targetSdk 35 |
| Aktualizácie | SMB z NAS-u (`Android\Pocasie\pocasie.apk`) |
| Testy | 79 (`./gradlew :app:testDebugUnitTest`) |

## Čo appka robí

* **Zoznam miest** — mesto sa nájde podľa názvu (geokódovanie Open-Meteo),
  pridá sa ťuknutím. **Návrhy pribúdajú už počas písania** (od 5. 9. 2026);
  server sa pýta 400 ms po poslednom písmene, nie po každom — geokódovanie je
  prenos ako každý iný a „Bratislava" by inak stála desať dopytov namiesto
  jedného. Enter funguje ďalej a nečaká. Poloha telefónu sa nikde nepoužíva,
  takže appka nepýta žiadne povolenie na polohu.
* **Detail** — **graf počasia** (čiara = teplota, stĺpce = zrážky) s výberom
  rozsahu **24 h až 7 dní**, pásik po hodinách (48 h dopredu) a zoznam po dňoch
  na celý týždeň.
* **Zrážky sú vždy s obdobím** — `0,3 mm/h` v hodine, `0,3 mm/deň` v dni,
  `dnes 0,3 mm` v zozname a vo widgete. Samotné „0,3 mm" je hádanka: za hodinu,
  za deň, alebo za týždeň?
* **Východ a západ slnka a fáza mesiaca** (od 1.6, 6. 9. 2026). Slnko chodí
  v tom istom dennom volaní ako zvyšok predpovede, takže nestojí ani dopyt
  navyše — je pri každom dni v detaile (`↑06:24 ↓19:31`) aj v hlavičke widgetu.
  **Fáza mesiaca sa nesťahuje, počíta sa** (`Mesiac.kt`): Open-Meteo ju nevracia
  a kvôli jednému údaju by pribudlo druhé API a ďalšie miesto, kde sa dá prísť
  o sieť. Pod zoznamom dní je fáza aj **najbližší spln alebo nov**; vo widgete
  je z nej emoji. Presnosť je ±pol dňa (priemerný synodický mesiac) — na otázku
  „kedy je spln" to stačí, na zatmenie nie.
* **Upozornenia** — pravidlá typu „teplota klesne pod −2 °C v najbližších
  24 hodinách" alebo „zrážky prekročia 2 mm/h". Vyhodnocujú sa
  **z už stiahnutej predpovede**, nie novým dopytom.
* **Obnova na pozadí** — WorkManager, predvolene každé 3 hodiny a **len na
  Wi-Fi**.
* **Ranné stiahnutie o pevnej hodine** (predvolene **6:00**, meniteľné
  v Nastaveniach), **výhradne cez Wi-Fi** (od 7. 9. 2026). Interval hovorí
  „ako často", nie „kedy" — pri troch hodinách môže posledné sťahovanie vyjsť
  na 4:45 a človek odchádza z domu s predpoveďou, ktorá vie o hodinu a pol
  menej. Keď v tú hodinu Wi-Fi nie je, sťahovanie **počká na najbližšiu**;
  cez mobilné dáta sa nespustí nikdy a už stiahnutá predpoveď medzitým platí
  ďalej (leží na disku, nemaže sa). Ráno sa sťahuje aj vtedy, keď je uložená
  predpoveď ešte „čerstvá" — o 6:00 má byť v telefóne to najnovšie a štyri
  miesta stoja po Wi-Fi zopár kilobajtov.
* **Na mobilných dátach nesťahuje ani otvorenie appky** (od 3. 9. 2026).
  Otvorenie appky nie je žiadosť o nové dáta — človek sa častejšie pozerá na
  to, čo už je stiahnuté. Na dátach sťahuje výlučne ↻ alebo potiahnutie
  zoznamu; na Wi-Fi sa nemení nič. Dá sa to prepnúť v Nastaveniach
  (*Mobilné dáta*).
* **Widget na plochu** — miesta s aktuálnou teplotou a **grafom na najbližších
  24 h**. Graf je vysoký podľa toho, **koľko miesta widget naozaj má**: pri
  jedinom sledovanom meste vyplní celú plochu (predtým mal pevných 52 dp
  a widget bol z dvoch tretín prázdny), pri troch a viac miestach ostáva ako
  doteraz. Výška sa cez `RemoteViews` nastaviť nedá (`setViewLayoutHeight` je
  až od Androidu 12), preto ju určuje **pomer strán obrázka**. **Jeden stĺpec** (dvojstĺpcové usporiadanie zo Stocks tu orezávalo
  názvy miest), roluje sa prstom. Ťuknutie na miesto otvorí jeho detail.
  Ťuknutie na miesto otvorí jeho detail, ↻ stiahne nové dáta. Widget sám od
  seba na sieť **nechodí** — kreslí z disku, takže polhodinové tiknutie
  systému nestojí ani bajt.

## Koľko to sťahuje

Namerané 2. 9. 2026 pre Nitru, po drôte (teda po gzipe):

| Nastavenie | po drôte | nekomprimovane |
|---|---|---|
| 7 dní + hodinovka (predvolené) | **1 337 B** | 6 679 B |
| 3 dni bez hodinovky | **361 B** | 753 B |

Pri štyroch miestach a obnove každé 3 hodiny je to zhruba **1,3 MB mesačne**
s hodinovkou a **0,35 MB** bez nej.

Ako sa to dosiahlo — je to päť rozhodnutí, nie jedno:

1. **Pýtajú sa len tie polia, ktoré appka naozaj kreslí.** Predvolená odpoveď
   Open-Meteo má desiatky premenných; appka používa šesť.
2. **Počet dní a hodinovka sa dajú vypnúť** v Nastaveniach. Hodinovka je zhruba
   dve tretiny odpovede (168 hodnôt na premennú proti siedmim).
3. **Cache na disku, nie v pamäti.** Zoznam sa kreslí z posledného stiahnutia
   a nové sa robí, len keď je staré. Bez toho by každé otvorenie appky
   znamenalo ďalší prenos — presne to bola chyba v `askener` do 28. 8. 2026.
4. **Upozornenia nesťahujú nič.** Bežia nad tým, čo už prišlo; každé pridané
   pravidlo by inak stálo ďalší dopyt.
5. **Detail nesťahuje nič.** Vchádza sa doň zo zoznamu, ktorý sa práve obnovil.

Nastavenia majú sekciu **Prenesené dáta** — a od 3. 9. 2026 **zvlášť Wi-Fi
a zvlášť mobilné dáta** (`Prenos.kt`, spoločný súbor pre všetky appky). Nie je
to ozdoba: „minimalizuj dáta" sa nedá overiť bez čísla a bez neho by aj zlé
nastavenie vyzeralo dobre.

Prečo rozdelené: bajt cez Wi-Fi je zadarmo, bajt cez SIM stojí z paušálu. Jedno
spoločné číslo tie dva svety zlúčilo a tým zakrylo jedinú otázku, na ktorú má
počítadlo odpovedať — *stiahla mi appka niečo za peniaze?* Appka môže mať
mesačne 20 MB a byť v poriadku (19,9 MB z domácej Wi-Fi) a môže mať 2 MB a byť
problém (všetky cez dáta). Rozhoduje **prenosová vrstva**, nie meranosť siete:
zadanie znie „Wi-Fi proti SIM", a hotspot kolegu je Wi-Fi.

Ráta sa **nekomprimovaná** veľkosť — horný odhad, nie prikrášlenie. Ráta sa aj
geokódovanie (hľadanie mesta) a **stiahnuté APK**, čo je najväčší jednorazový
prenos, aký appka spraví.

## Graf počasia

Jeden graf, dve veličiny: **stĺpce sú zrážky** (mm, os vpravo) a **čiara je
teplota** (°C, os vľavo). Dva grafy pod sebou nútia oko skákať a hľadať, ktorá
hodina je ktorá — pritom otázka nie je „ako bude teplo" ani „či bude pršať",
ale „ako sa obliecť a či brať dáždnik". Stĺpce majú spodných 45 % výšky, aby
v daždivý deň neprekryli práve tú čiaru.

Pod nadpisom sa dá prepnúť rozsah: **24 h, 2, 3, 4, 5, 6, 7 dní**. Výber sa
pamätá. Ponúkajú sa len tie rozsahy, na ktoré sú stiahnuté dáta — pri
trojdňovej predpovedi je „7 dní" klamstvo. Ťahanie prstom po grafe vypíše nad
ním presnú hodinu (`So 5.9. 14:00 · 21 °C · 0,3 mm/h`); do stĺpca širokého tri
pixely sa popisok nakresliť nedá.

**Hustota mriežky sa riadi miestom, nie rozsahom** (od 1.8, 12. 9. 2026).
Dovtedy boli hodiny pod grafom pevne každých 6 h (na 24 h len štyri čísla)
a pri viac ako 80 stĺpcoch sa nepísali vôbec; teplotné čiary boli vždy tri.
Teraz `Graf.krokHodin` vyberie najhustejší krok z **1, 2, 3, 6, 12 h**, pri
ktorom sa popisky „00" ešte neprekryjú — na telefóne vychádza 24 h po dvoch
hodinách, 3 dni po šiestich, týždeň po dvanástich. Na tých istých hodinách
sú **zvislé čiary**; hranica dňa je výraznejšia, aby sa oko pri týždni chytilo
dní, nie poludní. Kroky sú delitele 24, takže popisky padnú každý deň na tie
isté hodiny. Vodorovné čiary teploty idú po **1, 2, 5 alebo 10 °C** podľa
výšky grafu (`Graf.krokTeplot`) — nízky widget dostane riedku mriežku, graf
v appke hustejšiu.

**Widget kreslí ten istý graf na 24 h** pri každom mieste. Kresba je spoločná
(`GrafKresba`), takže sa tvar na ploche a v appke nemôže rozísť. Widget ho
dostáva ako `Bitmap` (`GrafObrazok`) — `RemoteViews` vlastný `View` zobraziť
nevedia.

## Dve chyby, pre ktoré widget nefungoval vôbec (4. 9. 2026)

Obe boli v kóde od začiatku a obe sa ukázali až pri pokuse widget naozaj
pridať na plochu (Android 10):

1. **`setNumColumns` cez `RemoteViews`.** `GridView.setNumColumns` je
   „remotable" až od Androidu 12; na starších host odmietne celý layout
   a widget skončí hláškou **„Problém s načítaním miniaplikácií"**. Počet
   stĺpcov teraz nastavuje výlučne layout (`android:numColumns="1"`).
2. **Nemenná šablóna kolekcie.** `setPendingIntentTemplate` s
   `FLAG_IMMUTABLE` zahodí výplňový intent riadku, takže detail dostal prázdne
   `miesto_id` a hneď sa zavrel — navonok to vyzeralo, že ťuknutie na riadok
   „nerobí nič". Šablóna musí byť `FLAG_MUTABLE` (na Androide < 12 stačí
   `FLAG_IMMUTABLE` neuviesť).

Rovnaká dvojica číha v každej appke, ktorá má vo widgete kolekciu — v
`askener` sa opravila v ten istý deň.

## Čo sa pri stavbe ukázalo (a takmer prešlo)

**Hodinová predpoveď začína polnocou dnešného dňa, nie aktuálnou hodinou.**
Overené naživo o 23:00: prvá položka bola `2026-09-02T00:00`. Kto berie prvú
hodinu ako „teraz", prejde celý už odžitý deň — a appka by o jedenástej večer
zazvonila na rannú hmlu, ktorá dávno prešla. Preto sa „teraz" berie
z `current.time`, teda **z časového pásma toho miesta**; systémový čas telefónu
by pri predpovedi pre Sydney ukazoval na úplne inú hodinu.

## Ako sa v tom orientovať

| Súbor | Za čo zodpovedá |
|---|---|
| `Predpoved.kt` | model a parser odpovede — bez Androidu, testovateľný |
| `Graf.kt` | dáta grafu (mierky oboch osí, hranice dní, rozsahy) — čistá logika |
| `GrafKresba.kt` | samotné kreslenie — jedno miesto pre appku aj widget |
| `GrafView.kt` | graf v appke; ťahanie prstom vyberá stĺpec |
| `GrafObrazok.kt` | ten istý graf ako `Bitmap` pre widget |
| `Prenos.kt` | počítadlo dát Wi-Fi/mobil — rovnaké vo všetkých appkách |
| `Upozornenie.kt` | pravidlá a ich vyhodnotenie — tiež čistá logika |
| `OpenMeteo.kt` | jediné miesto, kde sa niečo sťahuje |
| `Ulozisko.kt` | predpoveď na disku, jeden súbor na miesto |
| `Alerty.kt` | Android okolo upozornení (kanál, text, odoslanie) |
| `Obnova.kt` | WorkManager |
| `MainActivity` · `DetailActivity` · `UpozorneniaActivity` · `NastaveniaActivity` | obrazovky |
| `App` · `Vzhlad` · `ZakladActivity` · `NastaveniaForm` · `SmbUpdater` · `ApkInstaller` · `VersionUi` | spoločná šablóna appiek (docs/shared-standard.md 2) |

## Vydanie

Appka sa aktualizuje sama z NAS-u (`Android\Pocasie\pocasie.apk`).
Tichá kontrola beží pri štarte a **len na Wi-Fi** — 8 MB APK na mobilných
dátach je presne to, čomu sa appka vyhýba.

Ručne sa dá kontrola vyžiadať **na dvoch miestach**: *Nastavenia → Verzia →
Skontrolovať teraz* (od 5. 9. 2026) alebo ťuknutím na pätičku s verziou.
Do 1.5 existovala len tá pätička a nikde nestálo, že je klikateľná — appka tak
z pohľadu človeka „nemala kontrolu aktualizácií nikde", hoci ju mala.
Obe cesty vedú do toho istého `Aktualizacia.kt`, aby sa nerozišli.

```powershell
.\gradlew :app:assembleDebug              # v priečinku pocasie
cd C:\NAS; .\nahraj-na-nas.ps1 -Appka pocasie
.\overit-verzie.ps1                       # kontrola, že NAS a projekt sedia
```

Pred vydaním zdvihni `versionCode` aj `versionName` v `app\build.gradle.kts` —
bez toho telefón novú verziu neponúkne.

## Čo ešte nie je

* **Preklady.** Zatiaľ len slovenčina.

## Build

```
./gradlew :app:assembleDebug        # APK do app/build/outputs/apk/debug/pocasie.apk
./gradlew :app:testDebugUnitTest    # 79 testov
```
