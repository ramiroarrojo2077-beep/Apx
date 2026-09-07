plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La base de conocimiento vive una sola vez, en data/conocimiento.json, y se
// copia a los assets al construir. Así la app y la versión de escritorio
// comparten exactamente el mismo cerebro.
val assetsGenerados = layout.buildDirectory.dir("generated/assets")

val sincronizarConocimiento by tasks.registering(Copy::class) {
    from(rootProject.file("../data/conocimiento.json"))
    into(assetsGenerados)
}

android {
    namespace = "ar.rama.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.rama.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(assetsGenerados)

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

tasks.named("preBuild") { dependsOn(sincronizarConocimiento) }

dependencies {
    // El motor no necesita nada: sólo el framework de Android.
    testImplementation("junit:junit:4.13.2")
    // org.json real para los tests de JVM (el android.jar de tests es un stub).
    testImplementation("org.json:json:20240303")
}
