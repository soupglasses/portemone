// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

plugins { alias(libs.plugins.android.application) }

android {
    namespace = "dev.finnes.portemone.notificationcompanion"
    compileSdk { version = release(37) }
    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        debug { isPseudoLocalesEnabled = true }
    }

    defaultConfig {
        applicationId = "dev.finnes.portemone.notificationcompanion"
        minSdk = 29
        // This optional sideloaded companion retains the legacy notification layout contract.
        // The main application keeps its modern target SDK and all card/location logic.
        targetSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = rootProject.extra["releaseVersionCode"] as Int
        versionName = rootProject.extra["releaseVersionName"] as String
    }
    lint {
        // This separately installed compatibility app intentionally is not a Play Store artifact.
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
