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
        versionCode = 5
        versionName = "2.0.3"

        // Hard-baked default server URL. When non-empty AND the device has
        // never been configured, SetupActivity skips itself entirely so the
        // operator just installs the APK and the screen comes up.
        // Set to "" to ship a "blank slate" build that always shows setup +
        // mDNS discovery (useful for purely-LAN deployments).
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
}
