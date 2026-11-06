plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
  namespace = "com.luiz.vrmr"; compileSdk = 34
  defaultConfig {
    applicationId = "com.luiz.vrmr"; minSdk = 24; targetSdk = 34; versionCode = 2; versionName = "0.2"
    ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
  }
  buildTypes { release { isMinifyEnabled = true; isShrinkResources = true } }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  androidResources { noCompress += "task" }
}
dependencies {
  implementation("androidx.activity:activity-ktx:1.9.0")
  implementation("androidx.camera:camera-core:1.3.4")
  implementation("androidx.camera:camera-camera2:1.3.4")
  implementation("androidx.camera:camera-lifecycle:1.3.4")
  implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
