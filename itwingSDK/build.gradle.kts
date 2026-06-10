import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.itwingtech.itwingsdk"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        viewBinding = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.shahzamansurani"
                artifactId = "ITWingSDK"
                version = "v1.5"
            }
        }
    }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk:1.1.1")
    implementation("androidx.core:core-ktx:1.18.0")

    implementation("com.intuit.sdp:sdp-android:1.1.1")
    implementation("com.intuit.ssp:ssp-android:1.1.1")
    implementation("com.airbnb.android:lottie:6.7.1")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.github.bumptech.glide:glide:5.0.7")
    api("com.android.billingclient:billing-ktx:9.0.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
    api(platform("com.google.firebase:firebase-bom:34.14.0"))
    implementation("com.google.firebase:firebase-analytics")
    api("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")

}
