plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.blockwork.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
