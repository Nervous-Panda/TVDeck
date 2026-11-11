plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tvdeck.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvdeck.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs directly (sideloading).
            // For a Play Store release, replace this with your own signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // "standard" is the normal app for TV boxes and phones. "car" is the same app plus a screen on the
    // Android Auto display (src/car), built against Google's unofficial Android Auto SDK (libs/aauto.aar).
    // It has its own package name so both can be installed side by side.
    flavorDimensions += "target"
    productFlavors {
        create("standard") {
            dimension = "target"
        }
        create("car") {
            dimension = "target"
            applicationIdSuffix = ".car"
            proguardFile("proguard-car.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Android Auto screen (car flavor only). The SDK hosts its screens with androidx fragments.
    add("carImplementation", files("libs/aauto.aar"))
    add("carImplementation", "androidx.fragment:fragment-ktx:1.8.5")
    // Its list layouts use CardView (referenced only from XML, so the build wouldn't notice it missing).
    add("carImplementation", "androidx.cardview:cardview:1.0.0")
}
