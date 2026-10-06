// ─────────────────────────────────────────────────────────────────────────────
// MISSA TV — module applicatif
//
// AGP 9 fournit Kotlin nativement (built-in Kotlin) : le plugin
// « org.jetbrains.kotlin.android » n'est donc PAS appliqué ici.
// ─────────────────────────────────────────────────────────────────────────────

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
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // ── Configuration distante ────────────────────────────────────────────
        // L'emplacement du fichier de configuration publié dans le dépôt est
        // injecté ici : aucune adresse n'est écrite dans le code source, et
        // l'emplacement peut changer sans toucher au code.
        //
        // Le jeton GitHub est FACULTATIF (le dépôt est public). S'il est fourni,
        // il ne doit jamais être versionné : il se déclare dans local.properties
        // (fichier non suivi par Git) ou dans les secrets de la CI.
        val configOwner = configValue("missa.config.owner", "missamedia69-code")
        val configRepo = configValue("missa.config.repo", "MISSA_TV")
        val configPath = configValue("missa.config.path", "remote-config/portal-config.json")
        val configToken = configValue("missa.config.token", "", secret = true)

        buildConfigField("String", "GITHUB_CONFIG_OWNER", "\"$configOwner\"")
        buildConfigField("String", "GITHUB_CONFIG_REPO", "\"$configRepo\"")
        buildConfigField("String", "GITHUB_CONFIG_PATH", "\"$configPath\"")
        buildConfigField("String", "GITHUB_CONFIG_TOKEN", "\"$configToken\"")
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
            // La signature de release est fournie par la CI via des secrets
            // (voir .github/workflows/release.yml) : aucun keystore ici.
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
 * Lit une valeur de configuration, dans l'ordre :
 *  1. `local.properties` (jamais versionné) ;
 *  2. une propriété Gradle (`-Pmissa.config.owner=...`) ;
 *  3. une variable d'environnement, pour les secrets de la CI ;
 *  4. la valeur par défaut fournie.
 *
 * Les valeurs marquées « secret » ne sont jamais écrites dans le journal de
 * compilation.
 */
fun configValue(key: String, default: String, secret: Boolean = false): String {
    val localProperties = java.util.Properties().apply {
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
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)

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
    implementation(libs.androidx.security.crypto)
    implementation(libs.tink.android)
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

    // --- Outils de développement ---------------------------------------------
    debugImplementation(libs.compose.ui.tooling)
}
