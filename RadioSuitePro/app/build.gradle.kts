plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.radiosuite.pro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.radiosuite.pro"
        minSdk = 26          // Oboe AAudio requiere API 26+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17 -fno-exceptions -fno-rtti -ffast-math")
                arguments("-DANDROID_STL=c++_shared")
                // ABIs objetivo: arm64 (modernos), armeabi-v7a (legacy), x86_64 (emulador)
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Oboe (audio baja latencia)
    implementation(libs.oboe)

    // ExoPlayer (decodificación MP3/OGG/Opus/WAV)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    // HTTP para Icecast/Shoutcast
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.coroutines.android)

    // Supabase (persistencia: playlists, perfiles de emisión, ajustes DSP)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Perfiles Icecast con contraseña cifrada mediante Android Keystore
    implementation(libs.security.crypto)
}
