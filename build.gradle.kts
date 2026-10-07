// ─────────────────────────────────────────────────────────────────────────────
// MISSA TV — build racine
//
// Remarque importante sur AGP 9 : le plugin Kotlin « org.jetbrains.kotlin.android »
// ne doit pas être appliqué, AGP fournit Kotlin nativement (built-in Kotlin).
// Pour utiliser une version de Kotlin plus récente que celle embarquée par AGP,
// il suffit de déclarer le KGP dans le classpath (voir ci-dessous) : Gradle
// retient la version la plus élevée.
// ─────────────────────────────────────────────────────────────────────────────

buildscript {
    dependencies {
        // Aligne le compilateur Kotlin sur la version du catalogue de versions.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint)
}

// ─────────────────────────────────────────────────────────────────────────────
// Analyse statique : tous les modules sont vérifiés par ktlint.
// detekt n'est volontairement pas utilisé : la dernière version publiée
// (1.23.8) embarque un analyseur Kotlin antérieur à Kotlin 2.4 et ne sait pas
// lire certaines constructions récentes du langage.
// ─────────────────────────────────────────────────────────────────────────────
ktlint {
    version.set(libs.versions.ktlintTool.get())
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("generated") }
    }
}
