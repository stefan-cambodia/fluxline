// Module :game — tout ce qui se voit, s'entend et se touche : rendu (shaders,
// bloom, particules), interface, entrées tactiles, audio, sauvegarde.
// Ne dépend que de libGDX : le même code tourne sur Android et sur PC.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    api(libs.gdx.core)
    api(libs.gdx.freetype)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Les tests de traduction lisent les textes et les polices du dossier assets/.
val assetsDir: String = rootProject.layout.projectDirectory.dir("assets").asFile.absolutePath

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("fluxline.assets", assetsDir)
    systemProperty("java.awt.headless", "true")
    inputs.dir(assetsDir).withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
