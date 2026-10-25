plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sickdash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sickdash"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("SICKDASH_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SICKDASH_VERSION_NAME") ?: "1.0.0"
    }

    // Release signing comes from the environment (CI secrets or your shell), never from the repo:
    // SICKDASH_KEYSTORE (path), SICKDASH_KEYSTORE_PASSWORD, SICKDASH_KEY_ALIAS, SICKDASH_KEY_PASSWORD.
    val releaseKeystore = System.getenv("SICKDASH_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SICKDASH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SICKDASH_KEY_ALIAS")
                keyPassword = System.getenv("SICKDASH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release key (e.g. a fork's CI), fall back to the debug key so it still builds.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
