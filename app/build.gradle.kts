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
        htmlReport = true
        xmlReport = true
        sarifReport = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// Cible JVM commune à Java et Kotlin : indispensable pour éviter l'erreur
// « Inconsistent JVM-target compatibility ».
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            // Erreurs explicites sur les API expérimentales mal utilisées.
            "-Xannotation-default-target=param-property",
        )
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

    // --- Outils de développement ---------------------------------------------
    debugImplementation(libs.compose.ui.tooling)
}
