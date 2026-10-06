// ─────────────────────────────────────────────────────────────────────────────
// MISSA TV — configuration du build
// ─────────────────────────────────────────────────────────────────────────────

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MISSA_TV"

include(":app")
