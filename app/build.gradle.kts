plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 4_002_002
val appVersionName = providers.gradleProperty("appVersionName").orNull ?: "4.2.2"

android {
    namespace = "com.obsidianwidget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.obsidianwidget"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}
