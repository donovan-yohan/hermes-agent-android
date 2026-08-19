plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hermesagent.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermesagent.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-phase2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // This slice ships debug APKs only; keep release honest rather than
            // pretending a signing/shrinking config exists.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // sshj + BouncyCastle reach for java.time / java.nio.file types that
        // only exist from API 26+ on some paths; desugaring keeps minSdk 26 safe.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main").kotlin.srcDir("src/main/kotlin")
        getByName("test").kotlin.srcDir("src/test/kotlin")
        // Compose UI tests live here, not in `test/`: they need
        // `ui-test-manifest`, which is a debug-only artifact by design, and
        // `check` runs the release unit tests too.
        getByName("testDebug").kotlin.srcDir("src/testDebug/kotlin")
        getByName("androidTest").kotlin.srcDir("src/androidTest/kotlin")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Deliberately NOT isReturnDefaultValues: it stubs every method on
            // the mockable android.jar — including the java.* classes Android
            // ships — so a real call like CharArray.fill() silently becomes a
            // no-op and a test can pass while the code does nothing.
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = false
        disable += setOf(
            // sshj/BouncyCastle ship desktop-JVM classes the Android lint API
            // check flags even though the SSH code paths never touch them.
            "InvalidPackage",
        )
    }
}

// `./gradlew check` is the one command that has to be honest: unit tests plus
// lint plus the repo invariants.
tasks.named("check") {
    dependsOn(rootProject.tasks.named("verifyRepoInvariants"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Dead imports and unused locals are how a refactor leaves litter.
        // Warnings are visible; they are not errors, so a work-in-progress
        // build still runs.
        extraWarnings.set(true)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    // No SSH-library logging at all: the cheapest way to guarantee sshj never
    // writes host names, banners, or auth detail into logcat.
    implementation(libs.slf4j.nop)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // ThemeParityTest walks the palette/token contract reflectively so a new
    // field cannot be added without the parity check seeing it.
    testImplementation(kotlin("reflect"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
