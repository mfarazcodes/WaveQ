plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.waveq.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.waveq.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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
    testOptions {
        unitTests {
            // android.util.Log throws "not mocked" by default. The sensor poll
            // loop logs every state transition, and those transitions are
            // exactly what the tests exercise.
            isReturnDefaultValues = true
        }
    }
}

// exportSchema = true on WaveQDatabase needs somewhere to write the schema.
// The committed JSON is what makes a real migration reviewable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.nearby)
    implementation(libs.play.services.location)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    // Open-Meteo clients for the flood-risk engine. Retrofit is used with the
    // scalars converter (raw String bodies) rather than a JSON binding, so the
    // responses are parsed with org.json exactly like every other wire format
    // in this app (MeshSerialization, ChannelRepository) - one parsing idiom,
    // and no reflection/codegen dependency added to the build.
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    // Merged from the base branch: OSMDroid renders the evacuation map from
    // free OpenStreetMap tiles (no API key, no billing), and WorkManager
    // flushes locally-stored incident reports once connectivity returns.
    implementation(libs.osmdroid.android)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // org.json ships with the Android SDK, so unit tests get the stub whose
    // methods return null under isReturnDefaultValues. Every wire format in this
    // app parses with org.json, so the real implementation goes on the unit-test
    // classpath to shadow it.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}