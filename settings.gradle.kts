pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClawMate"
include(":app")
include(":shared-app", ":android-capabilities")
include(":gui-control-fixture") // Disposable SDK-only cross-app test actor, not a product host.
val meetingSdkRoot = providers.gradleProperty("meetingSdkRoot")
    .orElse(providers.environmentVariable("MEETING_SDK_ROOT")).orNull
// The Compose regression baseline must remain buildable without the optional
// meeting checkout. The shared host reports its own missing dependency on build.
if (meetingSdkRoot != null) {
    val sdkDirectory = file(meetingSdkRoot).resolve("android/meeting-sdk")
    require(sdkDirectory.resolve("build.gradle.kts").isFile || sdkDirectory.resolve("build.gradle").isFile) {
        "meetingSdkRoot must contain android/meeting-sdk; see docs/MEETING_INTEGRATION.md"
    }
    include(":meeting-sdk")
    project(":meeting-sdk").projectDir = sdkDirectory
}
