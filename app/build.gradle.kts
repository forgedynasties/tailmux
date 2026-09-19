plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hk1.tmuxtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hk1.tmuxtv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // xterm.js is pre-minified; don't let aapt compress/rename assets.
    androidResources {
        noCompress += listOf("js", "css", "html")
    }

    packaging {
        resources {
            // BouncyCastle ships duplicate multi-release OSGi metadata.
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    // Maintained JSch fork with ed25519 / modern crypto support.
    implementation("com.github.mwiede:jsch:0.2.20")
    // Android has no JDK Ed25519 provider; BouncyCastle supplies the Ed25519
    // Signature/KeyFactory that JSch needs to auth with our ed25519 key.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Tiny embedded HTTP server for phone-based token pairing.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // QR code generation for the pairing screen.
    implementation("com.google.zxing:core:3.5.3")
}
