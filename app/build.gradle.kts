plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.proton.android.lumo"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.proton.android.lumo"
        minSdk = 29
        targetSdk = 35
        versionCode = 34
        versionName = "0.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Default production environment
        buildConfigField("String", "ENV_NAME", "\"\"")
        buildConfigField("String", "BASE_DOMAIN", "\"proton.me\"")
    }

    signingConfigs {
        create("release") {
            keyAlias = System.getenv("LUMO_KEY_ALIAS") ?: "lumo"
            keyPassword = System.getenv("LUMO_KEY_PASSWORD")
            storeFile = System.getenv("LUMO_KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("LUMO_STORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    flavorDimensions += listOf("environment", "debugging")
    productFlavors {
        create("production") {
            dimension = "environment"
            // Uses default values from defaultConfig
        }
        
        // Debugging variants for WebView debugging capability
        create("standard") {
            dimension = "debugging"
            // Allows WebView debugging in debug builds (for development)
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUG", "true")
        }
        
        create("noWebViewDebug") {
            dimension = "debugging"
            // Never enables WebView debugging (for GrapheneOS and privacy-focused users)
            buildConfigField("boolean", "ENABLE_WEBVIEW_DEBUG", "false")
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
        buildConfig = true
    }
    
    // Optimize build performance
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
    
    // Custom APK naming
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val appName = "lumo"
            val versionName = variant.versionName
            val buildType = variant.buildType.name
            val flavor = variant.flavorName
            
            // Format: lumo-v0.1.2-production-debug.apk
            output.outputFileName = "${appName}-v${versionName}-${flavor}-${buildType}.apk"
        }
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${libs.versions.lifecycleRuntimeKtx.get()}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${libs.versions.lifecycleRuntimeKtx.get()}")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${libs.versions.lifecycleRuntimeKtx.get()}")
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.billing.ktx)
    implementation("io.coil-kt:coil-compose:2.4.0")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    // Hilt removed - using lightweight DependencyProvider instead

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("io.mockk:mockk-android:1.13.11")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.uiautomator)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}