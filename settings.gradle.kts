// the gradle build. one module, :app.
//
// **the android SDK is not discovered by gradle here.** local.properties is written by
// app/build-app.ps1 from whatever scripts/toolchain.ps1 resolved, so gradle is handed the same SDK
// every other script in this repository uses rather than finding its own through ANDROID_HOME. that
// is the whole point of the resolver: a machine with two SDKs installed must not have the app step
// silently disagree with the native step about which one it built against.

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

// FAIL_ON_PROJECT_REPOS: a module declaring its own repositories is how a dependency ends up
// resolved from somewhere nobody wrote down. every artefact this build uses comes from one of the
// two below.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sharpemu-android"
include(":app")
