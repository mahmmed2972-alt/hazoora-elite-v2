plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hazoora.quizgame"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hazoora.quizgame"
        minSdk = 24
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
    create("release") {
        storeFile = file("hazoora-upload-new.jks")
        storePassword = "Zcxv2972Zcxv2972"
        keyAlias = "hazoora-upload"
        keyPassword = "Zcxv2972Zcxv2972"
    }
}

buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        optimization {
            enable = false
        }
    }
}
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
implementation("com.google.android.gms:play-services-ads:24.7.0")
}