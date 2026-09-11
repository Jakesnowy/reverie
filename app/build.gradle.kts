plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Recomposition/stability reporting for UI optimization work. Off by default;
// run with -PcomposeMetrics (reports land in app/build/compose_compiler/).
composeCompiler {
    if (project.findProperty("composeMetrics") != null) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler/reports")
        metricsDestination = layout.buildDirectory.dir("compose_compiler/metrics")
    }
}

ktlint {
    android.set(true)
    version.set("1.8.0")
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/cpp/3rdparty/") }
    }
}

detekt {
    toolVersion = "1.23.7"
    config.setFrom("$projectDir/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(files("src/main/java", "src/main/kotlin"))
}

android {
    namespace = "io.github.xororz.localdream"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xororz.localdream"
        minSdk = 28
//        minSdk = 31
        targetSdk = 36
        versionCode = 74
        versionName = "2.8.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }
        // CI beta builds override the code via -POVERRIDE_VERSION_CODE so it
        // increases monotonically across releases; local/default stays put.
        versionCode = (project.findProperty("OVERRIDE_VERSION_CODE") as String?)?.toInt() ?: 74
    }

    signingConfigs {
        create("release") {
            storeFile = file(project.findProperty("RELEASE_STORE_FILE") as String? ?: "keystore.jks")
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
        }
        getByName("debug") {
            // GitHub builds must be mutually installable: on CI every debug
            // APK is signed with the repo-committed ci/debug.keystore, so
            // successive CI builds upgrade in place over each other (Android
            // refuses an update when signatures differ). Local builds keep
            // the default ~/.android/debug.keystore — they don't need to
            // match CI, and a debug signature is not a trust anchor anyway.
            if (System.getenv("CI") != null) {
                storeFile = rootProject.file("ci/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("beta") {
            // The Beta line is the real distribution channel: its key is the
            // trust anchor for external users' installs, so it is kept in
            // repo secrets (BETA_*) and wired in via -P flags at CI time —
            // never committed. CI decodes BETA_KEYSTORE_BASE64 to a temp file
            // and passes the path here.
            storeFile = file(project.findProperty("BETA_STORE_FILE") as String? ?: "beta.keystore")
            storePassword = project.findProperty("BETA_STORE_PASSWORD") as String?
            keyAlias = project.findProperty("BETA_KEY_ALIAS") as String?
            keyPassword = project.findProperty("BETA_KEY_PASSWORD") as String?
        }
    }

    bundle {
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
        language {
            enableSplit = false
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
//            signingConfig = signingConfigs.getByName("release")
            // Local test builds install alongside the official release:
            // distinct applicationId, version suffix, and launcher label
            // (label override lives in src/debug/res). Release builds are
            // untouched.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        // Beta line: this repo's official distribution variant. Deliberately
        // NO applicationId suffix — it takes the plain io.github.xororz.
        // localdream id, so it installs side-by-side with debug builds
        // (.debug id) but switching from the official upstream app needs a
        // one-time uninstall (same id, different signature). Signed with the
        // secret-kept beta keystore; CI overrides versionCode so betas
        // upgrade in order.
        create("beta") {
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("beta")
            versionNameSuffix = "-beta"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    flavorDimensions += "version"
    productFlavors {
        create("basic") {
            dimension = "version"
            versionNameSuffix = ""
        }
        create("filter") {
            dimension = "version"
            versionNameSuffix = "_with_filter"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = output.versionName.orNull
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("LocalDream_armv8a_$versionName.apk")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material3.xml)
    implementation(libs.coil.compose)
    implementation(libs.cropify)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Adds the ktlint-rule wrappers to detekt; we only enable UnusedImports
    // (the standalone ktlint plugin's no-unused-imports does not flag them).
    detektPlugins(libs.detekt.formatting)
}
