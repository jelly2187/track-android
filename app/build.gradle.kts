import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val signing = Properties().apply {
    val source = rootProject.file("signing.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
android {
    namespace = "com.jelly.habittrack"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.jelly.habittrack"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.1.6"
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://github.com/jelly2187/track-android/releases/latest/download/latest.json\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signing.isNotEmpty()) signingConfigs.create("personal") {
        storeFile = rootProject.file(signing.getProperty("storeFile"))
        storePassword = signing.getProperty("storePassword")
        keyAlias = signing.getProperty("keyAlias")
        keyPassword = signing.getProperty("keyPassword")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("personal")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
