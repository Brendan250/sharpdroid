// the APK.
//
// **drive this through app/build-app.ps1 rather than calling gradle directly.** the script resolves
// the SDK and JDK through scripts/toolchain.ps1, writes local.properties from what it found, and
// passes the identity this build should carry. gradle on its own would find its own SDK through
// ANDROID_HOME, which is exactly the disagreement the resolver exists to prevent.
//
// the native libraries are NOT built here. host/build.ps1 and scripts/build-adrenotools.ps1 produce
// them into build/, and stageJniLibs below collects the four that go in the APK.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

// the identity, as app/build-app.ps1 resolved it. absent means the manifest's own -- which is what
// -Release asks for -- so these are properties rather than defaults with a value.
val identityApplicationId: String? = (findProperty("sharpemuApplicationId") as String?)?.takeIf { it.isNotBlank() }
val identityAppLabel: String = (findProperty("sharpemuAppLabel") as String?)?.takeIf { it.isNotBlank() } ?: "SharpEmu"

android {
    // **the java package, and it does not move.** the JNI entry points are named
    // Java_com_mircowuffwuff_sharpemu_HostLayer_*, host/CMakeLists.txt has a -Wl,-u keeping them
    // from being garbage-collected, and scripts/toolchain.ps1 spells it out for every launch
    // command. renaming this breaks the native link and every am start at once.
    namespace = "com.mircowuffwuff.sharpemu"
    compileSdk = 35

    defaultConfig {
        // **the application id is a different thing from the namespace above, and only this one
        // moves.** a renamed id installs beside the release app as a separate app to android, with
        // its own internal storage, its own external files directory and its own save data -- which
        // is what keeps a deploy loop away from a personal install. the activity is then
        // <application id>/com.mircowuffwuff.sharpemu.MainActivity, because only half of it moved.
        applicationId = identityApplicationId ?: "com.mircowuffwuff.sharpemu"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"

        // the label, which a renamed build has to change too: two entries in the launcher both
        // called "SharpEmu" and no way to tell which is which is the failure this avoids.
        resValue("string", "app_name", identityAppLabel)

        ndk {
            // arm64 only, and that is the architecture rather than a packaging choice: FEXCore's
            // backend emits arm64 and there is no other target.
            abiFilters += "arm64-v8a"
        }
    }

    // **a throwaway debug key, and it must be this one.** the installed debug app on a development
    // device was signed with it; a different key -- including gradle's own ~/.android/debug.keystore
    // -- makes adb install -r fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE and costs an uninstall,
    // which takes the app's save data with it. app/build-app.ps1 generates it on demand.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "sharpemu"
            keyPassword = "android"
        }
    }

    buildTypes {
        // **only the debug build type is ever assembled**, including for -Release. the two senses of
        // "release" are deliberately not the same thing here: -Release means the manifest's own
        // application id and label, not an optimised non-debuggable build. that is also where
        // android:debuggable="true" went -- the debug type sets it, and hardcoding it in the
        // manifest is a lint error under AGP.
        getByName("debug") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        // Eden's frontend is view-based with viewBinding, and this one follows it. its Compose
        // rework is a separate work in progress that does not replace the emulator activity.
        viewBinding = true
    }

    packaging {
        jniLibs {
            // **extractNativeLibs, under its AGP name.** the host layer is a 33 MB .so and leaving
            // it compressed in the zip is simpler than the uncompressed page-aligned layout an
            // in-place load wants -- install time once rather than launch time every time. and
            // adrenotools needs its hooks to exist as real files in nativeLibraryDir, which is
            // exactly what extraction produces. one flag, a packaging convenience and a hard
            // requirement of the driver path.
            useLegacyPackaging = true

            // **the native libraries ship unstripped, on purpose.** AGP strips by default, and the
            // host layer is where this project's open crash investigations live -- a stripped .so
            // turns a native backtrace into a list of addresses. it costs about 7 MB of APK, on a
            // build that is only ever installed over adb.
            //
            // it is written down rather than left alone because AGP is *already* failing to strip
            // these ("Unable to strip the following libraries, packaging them as they are") and
            // that is an accident of it not finding a usable llvm-strip. without this line, the day
            // it does find one is the day symbols quietly disappear.
            keepDebugSymbols += "**/*.so"
        }
    }

    sourceSets {
        getByName("main") {
            // the four .so files are produced by other steps into build/ and collected by
            // stageJniLibs below, rather than living in the source tree.
            jniLibs.srcDir(layout.buildDirectory.dir("jniLibs"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil)
}

// ---------------------------------------------------------------------------------------------
// the native libraries
//
// four .so files from three different places, none of them a source directory: the host layer and
// the adrenotools hooks are build output, and the STL comes out of the NDK. they are copied into one
// staging directory because that is the shape jniLibs.srcDir wants.
//
// **each is asserted rather than globbed hopefully.** an APK missing the host layer installs and
// then dies at UnsatisfiedLinkError, and one missing a hook does something worse: adrenotools falls
// back to the stock driver quietly, so a driver comparison would silently measure the same driver
// twice.

val hostLayerSo = rootProject.file("build/host/libsharpemu-host-layer.so")
val adrenotoolsHookSos = listOf(
    rootProject.file("build/adrenotools/src/hook/libmain_hook.so"),
    rootProject.file("build/adrenotools/src/hook/libhook_impl.so"),
)

// the STL.
//
// app/build-app.ps1 passes the path it resolved, because scripts/toolchain.ps1 is what decides
// which NDK this repository builds against and the answer must not differ between the native step
// and this one. the search below is the fallback for opening the project in Android Studio and
// hitting build, and it deliberately globs every installed NDK rather than asking for
// android.ndkVersion -- that property answers with AGP's own default when nothing set it, which is
// an NDK that is not installed here.
val stlSo: File? = (findProperty("sharpemuStlSo") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { File(it) }
    ?.takeIf { it.isFile }
    ?: File(android.sdkDirectory, "ndk")
        .listFiles()
        ?.sortedByDescending { it.name }
        ?.asSequence()
        ?.flatMap { ndk ->
            (File(ndk, "toolchains/llvm/prebuilt").listFiles() ?: emptyArray()).asSequence()
        }
        ?.map { File(it, "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so") }
        ?.firstOrNull { it.isFile }

val stageJniLibs by tasks.registering(Sync::class) {
    description = "collects the host layer, the STL and the adrenotools hooks into one jniLibs tree"

    // Sync rather than Copy: it deletes what is no longer produced, so a .so removed from this list
    // does not linger in the staging directory and keep being packaged.
    into(layout.buildDirectory.dir("jniLibs/arm64-v8a"))

    from(hostLayerSo)
    // the other two hooks adrenotools builds, file_redirect and gsl_alloc, are deliberately absent:
    // they back feature flags this app does not pass, and an unused hook in nativeLibraryDir is one
    // more thing that could be loaded by accident.
    from(adrenotoolsHookSos)
    // guarded rather than wrapped in a provider: a provider with no value fails while gradle is
    // still working out the task graph, which reports as "cannot query the value of this provider"
    // and names neither the STL nor the NDK. missing, it is doFirst below that says so.
    stlSo?.let { from(it) }

    doFirst {
        if (!hostLayerSo.isFile) {
            throw GradleException("$hostLayerSo not found. run .\\host\\build.ps1 first.")
        }
        adrenotoolsHookSos.firstOrNull { !it.isFile }?.let {
            throw GradleException("$it not found. run .\\scripts\\build-adrenotools.ps1 first.")
        }
        if (stlSo == null) {
            throw GradleException(
                "libc++_shared.so not found under ${android.sdkDirectory}/ndk. the host layer links" +
                    " c++_shared, and the copy in the APK has to be the one it was linked against."
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(stageJniLibs)
}
