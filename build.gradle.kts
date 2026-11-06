// Fichier de build racine : déclare les plugins sans les appliquer.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
