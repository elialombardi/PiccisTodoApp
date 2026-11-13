plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.elialombardi.piccistodo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.elialombardi.piccistodo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Override with -PBASE_URL=https://your-backend.example/ (HTTPS only).
        val baseUrl = providers.gradleProperty("BASE_URL").orElse("https://example.invalid/").get()
        require(baseUrl.startsWith("https://") && !baseUrl.contains('"') && !baseUrl.contains('\\'))
        buildConfigField("String", "BASE_URL", "\"${baseUrl.trimEnd('/')}/\"")
    }

    buildTypes {
        release {
            optimization {
                // Scoped optimization repackaged a Kotlin subclass outside its
                // package-private parent's package, causing IllegalAccessError on startup.
                // Keep disabled until an optimizer update is verified on a device.
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(libs.reorderable)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.hilt.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Reference libraries from the version catalog using libs.xxx
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation("com.squareup.retrofit2:converter-moshi:3.0.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation(libs.okhttp.logging)

    implementation(libs.hilt.android)
    // Note: Hilt's annotation processor uses ksp instead of kapt
    ksp(libs.hilt.compiler)
}