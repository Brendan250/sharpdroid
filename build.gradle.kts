// the root project. it builds nothing itself -- :app is the only module.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}

// **all local output goes to build/, which is the one directory .gitignore covers.** gradle would
// otherwise write app/build/ and .gradle/ scattered through the tree, and the convention here is
// that every artefact this repository produces is under build/ and nothing else needs ignoring.
// there is no way to redirect .gradle/ from inside the build, so that one is ignored instead.
layout.buildDirectory.set(file("build/gradle/root"))

subprojects {
    layout.buildDirectory.set(file("${rootDir}/build/gradle/${project.name}"))
}
