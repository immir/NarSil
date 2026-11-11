plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read-only game data from the repository's lib/, packaged as assets/lib.
val narsilLib = rootProject.file("../lib")
val generatedAssets = layout.buildDirectory.dir("generated/narsilAssets")
val syncGameData by tasks.registering(Sync::class) {
    from(narsilLib) {
        include("gamedata/**", "customize/**", "help/**", "screens/**")
        exclude("**/Makefile")
    }
    into(generatedAssets.map { it.dir("lib") })
}

android {
    namespace = "org.narsil.android"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.narsil.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-spike"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(generatedAssets)
        }
    }
}

tasks.named("preBuild") { dependsOn(syncGameData) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2023.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
