// Module :desktop — lanceur PC (LWJGL3) réservé au développement : itérer vite
// sur le rendu et produire des captures. Il n'est pas distribué.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":game"))
    implementation(libs.gdx.backend.lwjgl3)
    implementation(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
    implementation(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.stefan.fluxline.desktop.DesktopLauncherKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("assets")
    // Arguments transmis par -Pargs="--shot menu" par exemple.
    providers.gradleProperty("args").orNull?.let { args(it.split(" ")) }
}
