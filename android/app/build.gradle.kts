plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.webstas.sleepsense"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.webstas.sleepsense"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Bundle the watch app so the "Install watch app" button can hand it to the Pebble app. Run
// `pebble build` first; if the watch app has not been built, this leaves the bundle as it is.
val syncWatchApp by tasks.registering(Copy::class) {
    val source = rootProject.file("../build/PebbleSleepTracker.pbw")
    onlyIf { source.exists() }
    from(source)
    rename { "watch.pbw" }
    into("src/main/assets")
}
tasks.named("preBuild") { dependsOn(syncWatchApp) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.health.connect)
    implementation(libs.pebblekit.client)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paho.mqtt)
}
