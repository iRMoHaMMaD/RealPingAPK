import org.gradle.kotlin.dsl.implementation

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.realping.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.realping.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig = signingConfigs.getByName("release")  // اگر با Wizard ست شده، لازم نیست دستکاری کنید
        }
    }

    buildFeatures { compose = true }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"   // ← با Kotlin 1.9.24 سازگار
    }

    splits {
        abi {
            isEnable = true              // ساخت APK بر اساس ABI
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true        // علاوه بر APKهای جداگانه، یک universal هم بساز
        }
    }
    // برای AAB، گوگل خودش بر اساس ABI اسپلیت می‌کند
    bundle {
        abi { enableSplit = true }
    }

    buildFeatures { compose = true }

    // چون Kotlin 1.9.x داری:
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17  // ✅
        targetCompatibility = JavaVersion.VERSION_17  // ✅
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }               // ✅

    packaging { resources.excludes += setOf("META-INF/*") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation ("androidx.compose:compose-bom:1.0.0")
    implementation ("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.palette:palette:1.0.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // WireGuard tunnel
    implementation("com.wireguard.android:tunnel:1.0.20230706")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
