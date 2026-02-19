plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.jetbrainsCompose)
}

android {
    namespace = "com.simplogics.chat.android"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.simplogics.chat.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8" // Valid for Kotlin 1.9.22
    }
}

dependencies {
    implementation(project(":mobile:shared-ui"))
    implementation(project(":common"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.material3)
}
