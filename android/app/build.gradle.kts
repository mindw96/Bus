plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val firebaseEnabled = providers.gradleProperty("unistFirebaseEnabled")
    .map { value -> value.toBooleanStrictOrNull() ?: false }
    .getOrElse(false)

if (firebaseEnabled) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "kr.ac.unist.bus"
    compileSdk = 37

    defaultConfig {
        applicationId = "kr.ac.unist.bus"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.1"

        buildConfigField(
            "String",
            "UNIST_BUS_API_BASE_URL",
            "\"https://unist-bus-api-60408467370.asia-northeast3.run.app\"",
        )
        buildConfigField("boolean", "FCM_ENABLED", firebaseEnabled.toString())
        manifestPlaceholders["fcmEnabled"] = firebaseEnabled.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    if (firebaseEnabled) {
        implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
        implementation("com.google.firebase:firebase-messaging")
    } else {
        // Keep Firebase types available to the compiler while omitting the SDK
        // from normal APKs until a matching google-services.json is supplied.
        compileOnly(platform("com.google.firebase:firebase-bom:34.16.0"))
        compileOnly("com.google.firebase:firebase-messaging")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
