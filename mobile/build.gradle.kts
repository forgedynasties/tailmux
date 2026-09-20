plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cd4li.tmuxmobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cd4li.tmuxmobile"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("js", "css", "html") }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
}
