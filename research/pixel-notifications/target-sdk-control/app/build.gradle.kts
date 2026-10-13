// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

plugins { id("com.android.application") }
android {
 namespace = "dev.finnes.portemone.pixelresearch"
 compileSdk { version = release(37) }
 defaultConfig { applicationId = "dev.finnes.portemone.pixelresearch"; minSdk = 26; targetSdk = 30; versionCode = 1; versionName = "1.0" }
 flavorDimensions += "target"
 productFlavors {
  create("legacy") { dimension = "target"; targetSdk = 30 }
  create("modern") { dimension = "target"; targetSdk = 36; applicationIdSuffix = ".modern" }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation("com.google.zxing:core:3.5.3") }
