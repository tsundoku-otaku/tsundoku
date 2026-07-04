@file:Suppress("UNUSED", "KotlinConstantConditions")

package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.BuildConfig

val telemetryIncluded: Boolean
    inline get() = BuildConfig.TELEMETRY_INCLUDED

val updaterEnabled: Boolean
    inline get() = BuildConfig.UPDATER_ENABLED

val isDebugBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "debug"

val isNightlyBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "nightly"

// isNightly must always be checked before isPreview in any statements
// This is because isPreview is always true if isNightly is true
// Did this because adding an argument to AppUpdateChecker seemed complicated.
// The only desired differences are two if statements in
// repo target and build name, anyway.
val isPreviewBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "preview" || BuildConfig.BUILD_TYPE == "nightly"

val isReleaseBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "release"

val isFossBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE == "foss"

val isBenchmarkBuildType: Boolean
    inline get() = BuildConfig.BUILD_TYPE.contains("nonMinified") || BuildConfig.BUILD_TYPE.contains("benchmark")
