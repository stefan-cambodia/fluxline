import java.util.Properties
import javax.inject.Inject

plugins {
    // Un seul plugin Android : depuis AGP 9 le support Kotlin est intégré.
    alias(libs.plugins.android.application)
}

// --- Signature de release ------------------------------------------------------
// Le matériel de signature n'entre jamais dans le dépôt. Il est lu, dans l'ordre,
// depuis keystore.properties (poste de développement) puis des variables
// d'environnement (intégration continue). Sans lui, la release est signée avec
// la clé de debug : installable pour tester, mais pas publiable.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSecret(propertyName: String, environmentName: String): String? =
    (keystoreProperties.getProperty(propertyName) ?: System.getenv(environmentName))?.takeIf { it.isNotBlank() }

val keystoreFile = signingSecret("storeFile", "FLUXLINE_KEYSTORE_FILE")
val keystorePassword = signingSecret("storePassword", "FLUXLINE_KEYSTORE_PASSWORD")
val keystoreKeyAlias = signingSecret("keyAlias", "FLUXLINE_KEY_ALIAS")
val keystoreKeyPassword = signingSecret("keyPassword", "FLUXLINE_KEY_PASSWORD")
val hasReleaseKey = listOf(keystoreFile, keystorePassword, keystoreKeyAlias, keystoreKeyPassword).all { it != null }

android {
    namespace = "dev.stefan.fluxline"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.stefan.fluxline"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "DEV_TOOLS", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }

    packaging {
        // Les .so libGDX sont alignés 16 Ko : on les laisse non compressés
        // dans l'APK pour qu'ils soient chargés directement (mmap).
        jniLibs.useLegacyPackaging = false
    }
}

// --- Bibliothèques natives libGDX -------------------------------------------------
// libGDX publie ses .so dans des JAR (un par ABI). Cette tâche les extrait dans
// un dossier généré, branché ensuite comme source jniLibs de chaque variante.
val gdxNatives: Configuration by configurations.creating

val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

dependencies {
    implementation(project(":game"))
    implementation(libs.gdx.backend.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlin.stdlib) { version { strictly(libs.versions.kotlin.get()) } }
    abis.forEach { abi ->
        gdxNatives(variantOf(libs.gdx.platform) { classifier("natives-$abi") })
        gdxNatives(variantOf(libs.gdx.freetype.platform) { classifier("natives-$abi") })
    }
}

abstract class ExtractGdxNatives : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val nativeJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject abstract val fileSystem: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun extract() {
        fileSystem.delete { delete(outputDir) }
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        nativeJars.files.forEach { jar ->
            val abi = abis.firstOrNull { jar.name.endsWith("natives-$it.jar") } ?: return@forEach
            fileSystem.copy {
                from(archives.zipTree(jar))
                include("*.so")
                into(outputDir.dir(abi))
            }
        }
    }
}

val extractGdxNatives = tasks.register<ExtractGdxNatives>("extractGdxNatives") {
    nativeJars.from(gdxNatives)
    outputDir.set(layout.buildDirectory.dir("generated/gdx-natives"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractGdxNatives, ExtractGdxNatives::outputDir)
        // Les ressources du jeu (textures, sons, polices) sont partagées avec le
        // lanceur PC : elles vivent à la racine, dans assets/.
        variant.sources.assets?.addStaticSourceDirectory(rootProject.file("assets").absolutePath)
    }
}

if (!hasReleaseKey) {
    tasks.matching { it.name == "packageRelease" || it.name == "signReleaseBundle" }.configureEach {
        doFirst {
            logger.warn(
                "Fluxline : aucune clé de release (keystore.properties ou FLUXLINE_KEYSTORE_*). " +
                    "Ce paquet est signé avec la clé de debug et n'est PAS publiable.",
            )
        }
    }
}
