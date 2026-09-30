import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The release workflow passes the version; local builds use the defaults.
val gyroVersionName = (findProperty("gyroVersionName") as String?) ?: "0.1.0"
val gyroVersionCode = (findProperty("gyroVersionCode") as String?)?.toInt() ?: 100

// Release signing key comes from the environment (GitHub secrets, see README); never from the repo.
val releaseKeystore: String? = System.getenv("GYRO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.gyro"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.gyro"
        minSdk = 26
        targetSdk = 36
        versionCode = gyroVersionCode
        versionName = gyroVersionName
    }

    signingConfigs {
        releaseKeystore?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("GYRO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GYRO_KEY_ALIAS")
                keyPassword = System.getenv("GYRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off until a shrunk build has been tried on a real wheel.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is signed with the debug key so it still installs.
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
