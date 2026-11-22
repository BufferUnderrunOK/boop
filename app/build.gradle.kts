plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Release signing comes from the environment (CI secrets); without it, release builds fall back
// to the local debug key so `./gradlew assembleRelease` still works on any machine.
val releaseKeystore: String? = System.getenv("BOOP_KEYSTORE")

android {
    namespace = "net.tileringer.boop"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.tileringer.boop"
        minSdk = 33
        targetSdk = 36
        // CI passes these from the git tag (v1.2.3 -> 1.2.3) and the run number.
        versionCode = (findProperty("boopVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("boopVersionName") as String?) ?: "dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("BOOP_KEYSTORE_PASSWORD")
                // PKCS12 keystore: the key shares the store's password.
                keyAlias = System.getenv("BOOP_KEY_ALIAS") ?: "boop"
                keyPassword = System.getenv("BOOP_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
