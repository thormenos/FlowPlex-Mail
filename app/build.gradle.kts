plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.fcarreau.flowplexmail"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fcarreau.flowplexmail"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")

    // Sign-in Google avec les scopes Gmail/Drive
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Client officiel Gmail API + Drive API pour Android
    implementation("com.google.api-client:google-api-client-android:2.6.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-gmail:v1-rev20240520-2.0.0")
    implementation("com.google.apis:google-api-services-drive:v3-rev20240521-2.0.0")
    implementation("com.google.http-client:google-http-client-android:1.44.1") {
        exclude(group = "org.apache.httpcomponents")
    }

    // Coroutines pour les appels reseau hors du thread principal
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cache local des messages et regles utilisateur
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Synchronisation periodique en arriere-plan
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
