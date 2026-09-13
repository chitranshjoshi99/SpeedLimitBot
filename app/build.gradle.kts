plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.speedlimitbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.speedlimitbot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { debugSymbolLevel = "NONE" }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = false
        resValues = false
        shaders = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version", "META-INF/**/*.kotlin_module",
            "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "**/*.properties"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.car.app:app:1.7.0-beta02")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
