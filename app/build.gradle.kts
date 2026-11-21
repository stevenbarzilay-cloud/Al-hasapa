import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.couch.alhasapa"
    // Compile against API 37.2 (current AndroidX needs 37.1+). targetSdk below is a separate choice:
    // it decides which runtime behavior changes apply to the app.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "app.couch.alhasapa"
        targetSdk = 37
        versionCode = 17
        versionName = "1.4.0"
    }

    // Two builds from one codebase: the phone app, and the Android TV app (a Xiaomi Mi Box runs Android TV 8.1 or 9, so the TV
    // build goes down to API 26; the phone app keeps 29). Both can be installed side by side, they are different apps.
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            minSdk = 29
            buildConfigField("boolean", "TV", "false")
        }
        create("tv") {
            dimension = "device"
            minSdk = 26
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
            buildConfigField("boolean", "TV", "true")
        }
    }

    // One fixed debug key, committed on purpose (standard "android" passwords; this is not a secret).
    // Every build, whether from Android Studio, a terminal or this repo's CI, is then signed the same,
    // so a new APK always installs OVER the old one and the logins and history survive updates.
    // A per-machine debug key would force an uninstall each time, which wipes both.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // A release build is what a TV box needs: the debug build is ten times slower to start (unoptimised Compose, a 36 MB dex that
            // has to be loaded whole), which is the 10 to 15 seconds of waiting on the Mi Box. R8 shrinks the code to a fraction and
            // optimises it; the names are kept (-dontobfuscate in the rules) so a crash report still reads.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The same committed key as the debug build, so a release APK installs OVER a debug one (and the logins survive).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and assets (the hub is loaded from assets).
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // The app is intentionally Hebrew-only, so the default resources are Hebrew.
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
