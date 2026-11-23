plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ch.leico.declic"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.leico.declic"
        minSdk = 28
        targetSdk = 36
        versionCode = 16
        versionName = "0.5.1"
    }

    signingConfigs {
        // Clé fixe : les prochaines versions s'installeront par-dessus sans désinstaller.
        val ks = rootProject.file("keystore/declic.jks")
        if (ks.exists()) {
            create("fixed") {
                storeFile = ks
                storePassword = "declic"
                keyAlias = "declic"
                keyPassword = "declic"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Connexion au débogage sans fil du téléphone (remplace Shizuku)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // Création du certificat d'association
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
