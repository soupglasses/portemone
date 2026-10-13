// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.finnes.portemone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.finnes.portemone"
        minSdk = 31
        targetSdk = 36
        versionCode = rootProject.extra["releaseVersionCode"] as Int
        versionName = rootProject.extra["releaseVersionName"] as String

        resValue(
            "string",
            "app_build_identity",
            rootProject.extra["developmentBuildIdentity"] as String
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            resValue(
                "string",
                "app_build_identity",
                rootProject.extra["releaseBuildIdentity"] as String
            )
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        resources.merges += "META-INF/LICENSE.md"
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.palette)
    implementation(libs.bouncycastle.pkix)
    testImplementation(libs.json)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(libs.google.play.services.location)
    implementation(libs.zxing)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
