plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// The Tile auth keys come from ../tile-keys.json (made by mac/get_keys.py, git-ignored) and are
// baked into the APK as an asset. Never committed; a rebuild picks up new Tiles.
val tileKeysDir = layout.buildDirectory.dir("generated/tileKeys")
val copyTileKeys by tasks.registering(Copy::class) {
    from(rootProject.file("tile-keys.json"))
    into(tileKeysDir)
    rename { "tiles.json" }
    doFirst {
        check(rootProject.file("tile-keys.json").isFile) { "tile-keys.json is missing: run mac/get_keys.py first" }
    }
}

android {
    namespace = "net.tileringer.boop"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.tileringer.boop"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal app: sign release with the debug key so it installs without a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].assets.srcDir(tileKeysDir)

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

tasks.named("preBuild") { dependsOn(copyTileKeys) }

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
