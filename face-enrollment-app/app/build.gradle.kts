import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

val faceApiKey = providers.gradleProperty("FACE_API_KEY").orNull
    ?: providers.environmentVariable("FACE_API_KEY").orNull
    ?: localProperties.getProperty("FACE_API_KEY").orEmpty()

check(faceApiKey.isNotBlank()) {
    "FACE_API_KEY is not configured. Add it to local.properties or set the FACE_API_KEY environment variable."
}

android {
    namespace = "cn.edu.xxq.faceenrollment"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.edu.xxq.faceenrollment"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FACE_API_KEY", faceApiKey.asBuildConfigString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.exifinterface)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
