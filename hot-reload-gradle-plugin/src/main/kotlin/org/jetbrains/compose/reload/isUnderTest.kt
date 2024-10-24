package org.jetbrains.compose.reload

import org.gradle.api.Project
import org.gradle.api.provider.Provider

val Project.isDebugMode: Provider<Boolean>
    get() = providers.gradleProperty("compose.reload.debug").map { raw -> raw.toBoolean() }