import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.services)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun configuredValue(name: String, fallback: String): String {
    return (project.findProperty(name) as String?)
        ?: System.getenv(name)
        ?: localProps.getProperty(name)
        ?: fallback
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
        buildConfigField(
            "String",
            "ITWING_SDK_KEY",
            "\"${configuredValue("ITWING_SDK_KEY", "itw_test_example_android_sdk_key_change_me_1234567890")}\"",
        )
        buildConfigField(
            "String",
            "ITWING_SDK_ENDPOINT",
            "\"${configuredValue("ITWING_SDK_ENDPOINT", "https://sdk.itwingtech.com/api/sdk/v1")}\"",
        )
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
