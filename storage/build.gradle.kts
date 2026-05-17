import com.android.build.api.dsl.LibraryExtension

plugins {
    id(libs.plugins.androidLibrary.get().pluginId)
    id(libs.plugins.dokka.get().pluginId)
    alias(libs.plugins.ksp)
}

extensions.configure<LibraryExtension>("android") {
    buildFeatures {
        compose = false
    }
}

dependencies {
    implementation(projects.common)
    implementation(libs.kotlin.coroutines)
    implementation(libs.compose.runtime)
    
    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Retrofit & Moshi
    implementation(libs.square.retrofit)
    implementation(libs.square.retrofit.converter.moshi)
    implementation(libs.square.moshi.kotlin)
    ksp(libs.square.moshi.kotlin.codegen)

    implementation(libs.timber)
}
