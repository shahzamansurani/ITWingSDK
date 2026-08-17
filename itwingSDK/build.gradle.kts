import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val sdkPublicationGroup = providers.gradleProperty("group")
    .orElse("com.github.shahzamansurani")
val sdkPublicationVersion = providers.gradleProperty("version")
    .orElse("v1.44")

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.itwingtech.itwingsdk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26

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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
                groupId = sdkPublicationGroup.get()
                artifactId = "ITWingSDK"
                version = sdkPublicationVersion.get()
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ads.mobile.sdk)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.viewpager2)

    implementation(libs.sdp.android)
    implementation(libs.ssp.android)
    api(libs.lottie)
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
    implementation(libs.app.review.ktx)
    implementation(libs.shimmer)
    implementation(libs.material)
    implementation(libs.glide)
    api(libs.billing.ktx)
    implementation(libs.installreferrer)
    implementation(libs.firebase.messaging)

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.ui)

}
