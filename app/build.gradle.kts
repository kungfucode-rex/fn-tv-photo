import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tvphoto"
    compileSdk = 36

    defaultConfig {
        // The id an install is known by, and the one a forum post quotes. It carries
        // the author's own prefix; `namespace` stays com.tvphoto, which is where the
        // code and the R/BuildConfig classes live, so only the packaged id moves. A
        // new id is a different app to Android: it installs *beside* the old one and
        // starts from empty settings.
        applicationId = "com.kungfucode.fntvphoto"
        minSdk = 23
        targetSdk = 36
        // Bumped when the launcher entry was added: the phone has to be able to
        // tell the fixed build apart from the earlier one that had no icon.
        versionCode = 2
        versionName = "1.1"
    }

    androidResources {
        // Only the languages the UI actually ships with.
        localeFilters += setOf("en", "zh")
    }

    signingConfigs {
        create("tvphoto") {
            storeFile = file("tvphoto.jks")
            storePassword = "tvphoto"
            keyAlias = "tvphoto"
            keyPassword = "tvphoto"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the project keystore so the APK can be sideloaded onto a TV.
            signingConfig = signingConfigs.getByName("tvphoto")
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
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/*
 * Dependency versions are pinned rather than ranged.
 *
 * This SDK ships platform 36 and nothing higher, so every Android library has to
 * declare `minCompileSdk <= 36`. Several current releases (Compose 1.12, core 1.19,
 * lifecycle 2.11, Coil 3.6, OkHttp 5.5) require compileSdk 37 and Android Gradle
 * Plugin 9.1+, which cannot be satisfied here. tools/check-deps.ps1 reads that
 * requirement straight out of each AAR's metadata, and these are its newest picks
 * that still build against platform 36.
 */
dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Compose 1.11.0 via the BOM. The TV libraries below request 1.10.3, so the
    // BOM raises them to 1.11.0 and nothing reaches the compileSdk-37 line.
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")

    // Image loading. Coil shares the token-bearing OkHttp client.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("com.squareup.okhttp3:okhttp:5.2.3")

    // Video playback for the video entries that live in the NAS gallery.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in JVM unit tests: every method throws
    // "not mocked". The real implementation is needed to test response parsing.
    testImplementation("org.json:json:20231013")
}
