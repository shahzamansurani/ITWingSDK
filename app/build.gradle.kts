import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.itwingtech.itwingsdk.example"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.itwingtech.itwingsdk.example"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val sdkKey = (project.findProperty("ITWING_SDK_KEY") as String?)
            ?: System.getenv("ITWING_SDK_KEY")
            ?: "itw_test_example_android_sdk_key_change_me_1234567890"
        val sdkEndpoint = (project.findProperty("ITWING_SDK_ENDPOINT") as String?)
            ?: System.getenv("ITWING_SDK_ENDPOINT")
            ?: "https://itwing.hsgasmart.com/api/sdk/v1"
        buildConfigField("String", "ITWING_SDK_KEY", "\"$sdkKey\"")
        buildConfigField("String", "ITWING_SDK_ENDPOINT", "\"$sdkEndpoint\"")
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(project(":itwingSDK"))
}