pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Fluxline"

// :core    règles du jeu, Kotlin pur (aucune dépendance) — testé sur la JVM.
// :game    moteur de rendu, interface, audio, entrées (libGDX, multiplateforme).
// :android lanceur Android (cycle de vie, vibrations, plein écran, encoches).
// :desktop lanceur PC pour le développement et les captures (non distribué).
include(":core", ":game", ":android", ":desktop")
