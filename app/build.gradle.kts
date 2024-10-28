plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.flowassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.flowassistant"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.espresso.core.v361)
    implementation(libs.androidx.espresso.core.v361)
    implementation(libs.androidx.preference.ktx)
    val composeBomVersion = "2023.08.00"
    
    implementation(platform(libs.androidx.compose.bom.v20230800))
    implementation(libs.androidx.core.ktx.v1131)
    implementation(libs.androidx.appcompat.v170)
    implementation(libs.material.v1120)
    implementation(libs.androidx.compose.material3.material3)
    implementation(libs.androidx.compose.ui.ui)
    implementation(libs.ui.graphics)
    implementation(libs.androidx.compose.ui.ui.tooling.preview)
    implementation(libs.androidx.activity.compose.v193)
    implementation(libs.androidx.lifecycle.runtime.ktx.v286)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto.v110alpha04)
    implementation(libs.json)
    
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.v121)
    androidTestImplementation(libs.androidx.espresso.core.v361)
    androidTestImplementation(platform(libs.androidx.compose.bom.v20230800))
    androidTestImplementation(libs.ui.test.junit4)
}
