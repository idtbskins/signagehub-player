plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.affissia.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.affissia.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "2.1.9"

        // Hard-baked default server URL. First-run setup still asks for
        // the tenant's Player invite code; the URL is only prefilled so
        // installers do not type it manually. Set to "" for purely-LAN
        // deployments that should rely on mDNS discovery.
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://app.affissia.it\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/signagehub-release.jks")
            storePassword = "signagehub2026"
            keyAlias = "signagehub"
            keyPassword = "signagehub2026"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // PR #5: EncryptedSharedPreferences for the long-lived
    // device_secret. Backed by the Android Keystore master key.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
