// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("org.cyclonedx.bom") version "3.5.1"
}

apply(from = rootProject.file("gradle/build-identity.gradle.kts"))

allprojects {
    version = rootProject.extra["releaseVersionName"] as String
    tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
        // Inventory shipped dependencies, excluding debug, test and build tooling.
        includeConfigs = listOf("releaseRuntimeClasspath")
        projectType = Component.Type.APPLICATION
    }
}

tasks.cyclonedxBom {
    projectType = Component.Type.APPLICATION
}
