plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.freease.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freease.music"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.4.1"
        vectorDrawables.useSupportLibrary = true
        // Hanya sertakan resource bahasa yang dipakai (library AndroidX/Material membawa puluhan bahasa).
        androidResources.localeFilters += setOf("en", "id")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // Ditandatangani dengan debug key supaya langsung bisa dipasang; ganti dengan key sendiri untuk rilis.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Opsional: folder berisi jar android-all Robolectric untuk mode offline (-ProbolectricDir=PATH).
            project.findProperty("robolectricDir")?.let {
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", it)
            }
            project.findProperty("recognize.pcm")?.let { test.systemProperty("recognize.pcm", it) }
            project.findProperty("karaoke.pcm")?.let { test.systemProperty("karaoke.pcm", it) }
            project.findProperty("karaoke.mix")?.let { test.systemProperty("karaoke.mix", it) }
            project.findProperty("shotsDir")?.let {
                test.systemProperty("shots.dir", it)
                test.systemProperty("roborazzi.test.record", "true")
            }
        }
    }
    lint {
        // Media3 menandai sebagian API sebagai @UnstableApi; sudah dipakai dengan sadar.
        disable += "UnsafeOptInUsageError"
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES",
            "META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module", "META-INF/com/android/build/gradle/**",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "**/*.kotlin_builtins",
            "**/*.kotlin_metadata", "META-INF/versions/**", "okhttp3/internal/publicsuffix/NOTICE",
        )
    }
    // Tidak butuh dependency metadata di APK (hanya berguna untuk Play Store).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    // org.json asli (android.jar hanya berisi stub kosong di unit test JVM).
    testImplementation("org.json:json:20231013")
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.androidx.palette)
}
