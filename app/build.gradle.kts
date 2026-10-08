// ─────────────────────────────────────────────────────────────────────────────
// MISSA TV — module applicatif
//
// AGP 9 fournit Kotlin nativement (built-in Kotlin) : le plugin
// « org.jetbrains.kotlin.android » n'est donc PAS appliqué ici.
// ─────────────────────────────────────────────────────────────────────────────

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Exécute les tests unitaires avec JUnit 5 (Jupiter) sur le runner Android.
    alias(libs.plugins.junit5)
}

android {
    namespace = "com.missa.tv"

    // AGP 9.4 accepte au maximum l'API 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.missa.tv"
        minSdk = 23
        targetSdk = 37
        // Version pilotée par l'étiquette de publication (voir
        // .github/workflows/release.yml). Le versionCode est dérivé du nom de
        // version (1.2.3 → 10203) pour que le système accepte la mise à jour,
        // quelle que soit l'étiquette publiée, et peut être forcé par
        // `-Pmissa.versionCode=...`.
        val nomVersion = configValue("missa.versionName", "1.0.0")
        versionName = nomVersion
        versionCode = configValue("missa.versionCode", "").toIntOrNull()
            ?: versionCodeOf(nomVersion)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // ── Configuration distante ────────────────────────────────────────────
        // L'emplacement du fichier de configuration publié dans le dépôt privé
        // `missa-tv-config` est injecté ici : aucune adresse n'est écrite dans le
        // code source, et l'emplacement peut changer sans toucher au code.
        //
        // Le dépôt étant PRIVÉ, un jeton GitHub en lecture seule est requis pour
        // lire le fichier. Il ne doit jamais être versionné : il se déclare dans
        // local.properties (fichier non suivi par Git) ou dans les secrets de la CI.
        val configOwner = configValue("missa.config.owner", "missamedia69-code")
        val configRepo = configValue("missa.config.repo", "missa-tv-config")
        val configPath = configValue("missa.config.path", "portal-config.json")
        // Branche ou étiquette lue. Vide = branche par défaut du dépôt (`main`).
        // Renseigner cette valeur permet de tester une configuration publiée sur
        // une autre branche, sans la fusionner dans `main`.
        val configRef = configValue("missa.config.ref", "")
        val configToken = configValue("missa.config.token", "", secret = true)

        buildConfigField("String", "GITHUB_CONFIG_OWNER", "\"$configOwner\"")
        buildConfigField("String", "GITHUB_CONFIG_REPO", "\"$configRepo\"")
        buildConfigField("String", "GITHUB_CONFIG_PATH", "\"$configPath\"")
        buildConfigField("String", "GITHUB_CONFIG_REF", "\"$configRef\"")
        buildConfigField("String", "GITHUB_CONFIG_TOKEN", "\"$configToken\"")
    }

    /**
     * Signature de la variante release.
     *
     * Le keystore n'est **jamais** versionné : il est fourni par l'environnement
     * de signature (secrets de la CI, ou `local.properties` pour un poste de
     * développement) et écrit dans un dossier temporaire.
     *
     * Sans keystore, la variante release reste compilable : R8 s'exécute, les
     * règles sont vérifiées, seul le fichier final n'est pas signé. C'est ce qui
     * permet à la CI de valider la minification à chaque push, sans détenir de
     * secret de signature.
     */
    signingConfigs {
        create("release") {
            val chemin = signingValue("missa.keystore.file", "MISSA_KEYSTORE_FILE")
            val motDePasse = signingValue("missa.keystore.password", "MISSA_KEYSTORE_PASSWORD")
            val aliasCle = signingValue("missa.key.alias", "MISSA_KEY_ALIAS", "missa")
            val motDePasseCle = signingValue(
                "missa.key.password",
                "MISSA_KEY_PASSWORD",
                motDePasse.orEmpty(),
            )

            if (!chemin.isNullOrBlank() && !motDePasse.isNullOrBlank()) {
                storeFile = file(chemin)
                storePassword = motDePasse
                keyAlias = aliasCle
                keyPassword = motDePasseCle
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signé seulement si un keystore est disponible ; sinon la variante
            // reste utile pour vérifier R8 (voir signingConfigs ci-dessus).
            signingConfigs.findByName("release")
                ?.takeIf { !it.storeFile?.toString().isNullOrBlank() }
                ?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // resValues reste désactivé : AGP 9 le désactive par défaut et le
        // projet n'en a pas besoin.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Le lint des variantes release est coûteux et redondant avec debug.
        checkReleaseBuilds = false
        // Les rapports (HTML/XML) sont désormais toujours générés par AGP 9 :
        // les options htmlReport/xmlReport/sarifReport sont dépréciées.
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

/**
 * Lit une valeur de signature, dans l'ordre :
 *  1. `local.properties` (jamais versionné) ;
 *  2. une variable d'environnement (secrets de la CI) ;
 *  3. la valeur par défaut fournie.
 *
 * Aucune de ces valeurs n'est journalisée, même partiellement : seule leur
 * présence est signalée.
 */
fun signingValue(key: String, envName: String, default: String? = null): String? {
    val localProperties = Properties().apply {
        val fichier = rootProject.file("local.properties")
        if (fichier.exists()) fichier.inputStream().use { load(it) }
    }

    return localProperties.getProperty(key)
        ?: providers.environmentVariable(envName).orNull
        ?: default
}

/**
 * Lit une valeur de configuration, dans l'ordre :
 *  1. `local.properties` (jamais versionné) ;
 *  2. une propriété Gradle (`-Pmissa.config.owner=...`) ;
 *  3. une variable d'environnement, pour les secrets de la CI ;
 *  4. la valeur par défaut fournie.
 *
 * Les valeurs marquées « secret » ne sont jamais écrites dans le journal de
 * compilation.
 */
/**
 * versionCode déduit d'un nom de version : `1.2.3` donne `10203`.
 *
 * Une étiquette inattendue (par exemple `2026.10`) ne fait pas échouer le build :
 * on retombe sur `1`, et la publication reste possible.
 */
fun versionCodeOf(versionName: String): Int {
    val morceaux = versionName.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
    if (morceaux.isEmpty()) return 1
    val majeur = morceaux.getOrElse(0) { 0 }
    val mineur = morceaux.getOrElse(1) { 0 }
    val correctif = morceaux.getOrElse(2) { 0 }
    return majeur * 10_000 + mineur * 100 + correctif
}

fun configValue(key: String, default: String, secret: Boolean = false): String {
    val localProperties = Properties().apply {
        val fichier = rootProject.file("local.properties")
        if (fichier.exists()) fichier.inputStream().use { load(it) }
    }
    val nomVariable = key.uppercase().replace('.', '_').replace('-', '_')

    val valeur = localProperties.getProperty(key)
        ?: providers.gradleProperty(key).orNull
        ?: providers.environmentVariable(nomVariable).orNull
        ?: default

    if (secret && valeur.isNotBlank()) {
        logger.lifecycle("$nomVariable : fourni (valeur non affichée)")
    }
    return valeur.replace("\\", "\\\\").replace("\"", "\\\"")
}

// Cible JVM commune à Java et Kotlin : indispensable pour éviter l'erreur
// « Inconsistent JVM-target compatibility ».
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Socle AndroidX -------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // --- Compose (versions alignées par la BOM) -------------------------------
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // --- Android TV -----------------------------------------------------------
    // Seul tv-material est utilisé : les listes de télévision (TvLazyRow) sont
    // désormais fournies par Compose Foundation, et androidx.tv:tv-foundation
    // 1.0.0 ne contient plus de composant utile à ce projet.
    implementation(libs.androidx.tv.material)

    // --- Lecture vidéo (Media3 / ExoPlayer) -----------------------------------
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    // --- Injection de dépendances --------------------------------------------
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- Persistance ----------------------------------------------------------
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    // --- Réseau ---------------------------------------------------------------
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // --- Images ---------------------------------------------------------------
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- Tests unitaires ------------------------------------------------------
    // Écrits avec JUnit 5, vérifiés par Truth, avec MockK pour les doublures et
    // Turbine pour les flux. La BOM JUnit garantit l'alignement des versions
    // entre Jupiter et la plateforme de test.
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    // --- Tests instrumentés (Compose) -----------------------------------------
    // Ces tests s'exécutent sur un appareil ou un émulateur : la CI les compile
    // pour garantir qu'ils restent valides, l'exécution relève de la recette
    // manuelle (voir docs/TESTS_FAIBLE_DEBIT.md).
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // --- Outils de développement ---------------------------------------------
    debugImplementation(libs.compose.ui.tooling)
}
