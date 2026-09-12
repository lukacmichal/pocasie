plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- spolocny blok pre vsetky appky (nemen len tu, viz docs/shared-standard.md) ----
/**
 * Pristupove udaje k NAS-u sa citaju z JEDNEHO suboru spolocneho pre vsetky
 * appky: nas-credentials.local. Odtial sa pri builde vlozia do APK ako
 * predvolby, takze na telefone sa uz nic nezadava.
 *
 * Prepis v Nastaveniach appky ma prednost — to je cesta pre zmenu hesla bez
 * rebuildu. Ked subor chyba, hodnoty ostanu prazdne a appka si udaje vypyta
 * v Nastaveniach ako predtym; build kvoli tomu nikdy nespadne.
 */
fun nasSetting(key: String): String {
    val f = file(System.getenv("NAS_CREDENTIALS") ?: "nas-credentials.local")
    if (!f.exists()) return ""
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")?.trim().orEmpty()
}

/** Heslo moze obsahovat spatne lomitko aj uvodzovku — bez tohto by build nepreslo. */
fun nasField(key: String): String =
    "\"" + nasSetting(key).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
// ---- koniec spolocneho bloku -------------------------------------------------

android {
    namespace = "sk.lukac.pocasie"
    compileSdk = 35

    defaultConfig {
        applicationId = "sk.lukac.pocasie"
        minSdk = 26
        targetSdk = 35
        // 1.0 (1) — prva verzia: miesta, detail, upozornenia. Nikdy nebola
        // na NAS-e, len ako subor.
        // 1.1 (2) — widget na plochu (roluje sa prstom, sam na siet nechodi)
        // a vlastna velkost pisma pre widget v Nastaveniach.
        // 1.2 (3) — graf zrazok v detaile, zrazky vzdy s obdobim (mm/h, mm/den,
        // dnes), pocitadlo dat rozdelene na Wi-Fi a mobilne, tlacidlo Ulozit
        // v Nastaveniach, siroky rozsah velkosti pisma (-6..+6), widget ma
        // vzdy jeden stlpec a na mobilnych datach sa pri otvoreni nestahuje.
        // 1.3 (4) — pocitadlo dat rozdelene na Wi-Fi a mobilne cez spolocny
        // Prenos.kt (rovnaky subor vo vsetkych appkach) a sirsi rozsah pisma.
        // 1.4 (5) — graf ma aj TEPLOTU (ciara) a da sa prepnut na 24 h az
        // 7 dni; widget kresli ten isty graf na 24 h pri kazdom miesta.
        // Zaroven dve opravy, bez ktorych widget nefungoval vobec:
        // `setNumColumns` cez RemoteViews (Android 10 ho odmieta a widget
        // skoncil hlaskou "Problém s načítaním miniaplikácií") a nemenna
        // sablona kolekcie (tuknutie na riadok otvorilo prazdny detail).
        // 1.5 (6) — navrhy miest sa ukazuju UZ POCAS PISANIA (nie az po
        // Enteri), graf vo widgete vyplni vysku, ktoru widget naozaj ma
        // (pri jedinom mieste uz nie je z dvoch tretin prazdny) a Nastavenia
        // maju sekciu Verzia s tlacidlom „Skontrolovat teraz" podla
        // docs/shared-standard.md 2.8 — dovtedy sa aktualizacia dala vyziadat len
        // tuknutim na paticku, o ktorom nikde nic nestalo.
        // 1.7 (8) — ranne stiahnutie predpovede o pevnej hodine (predvolene
        // 6:00) VYHRADNE po Wi-Fi. Interval hovoril „ako casto", nie „kedy",
        // takze rano mohla byt predpoved aj hodinu a pol stara.
        // 1.8 (9) — graf ma hustotu mriezky podla miesta: hodiny pod grafom
        // po 1–12 h (24 h po dvoch, tyzden po dvanast), zvisle ciary na tych
        // istych hodinach, vyraznejsie hranice dni a vodorovne ciary teploty
        // po 1/2/5/10 °C podla vysky. Dovtedy pevne kazdych 6 h a tri ciary.
        versionCode = 9
        versionName = "1.8"

        buildConfigField("String", "NAS_HOST", nasField("NAS_HOST"))
        buildConfigField("String", "NAS_SHARE", nasField("NAS_SHARE"))
        buildConfigField("String", "NAS_USER", nasField("NAS_USER"))
        buildConfigField("String", "NAS_PASS", nasField("NAS_PASS"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Pevne pomenovany vystup kvoli self-update konvencii na NAS-e
    // (Android/Pocasie/pocasie.apk sa pri kazdom vydani len prepise; skutocnu
    // verziu appka cita z APK manifestu, nie z nazvu suboru).
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "pocasie.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // SMB2/3 klient pre self-update z NAS-u; slf4j-nop potlaci jeho logovanie.
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:2.0.9")
    // Sifrovane prefs pre heslo k NAS-u (MasterKey v Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Obnova predpovede na pozadi. WorkManager, nie AlarmManager: system smie
    // pracu odlozit a zlucit s inou. `NetworkType.UNMETERED` drzi slub, ze sa
    // na mobilnych datach nespusti vobec.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Odpovede Open-Meteo sa parsuju cez org.json — je v Androide, takze ziadna
    // dalsia zavislost nepribuda. V unit testoch ho dodava json:20240303,
    // lebo android.jar ma len prazdne stuby.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
