plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

// Clean up any empty resource files that cause AAPT2 premature EOF errors
file("src/main/res").walkTopDown().filter { it.isFile && it.length() == 0L }.forEach {
  logger.lifecycle("Removing empty resource file: ${it.path}")
  it.delete()
}

android {
  namespace = "com.hinnka.mycamera"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.photoncamera.pcam"
    minSdk = 30
    targetSdk = 36
    versionCode = 162
    versionName = "1.28.4"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    buildConfigField("String", "FLAVOR", "\"default\"")
    buildConfigField("String", "BUILT_IN_API_URL", "\"https://camera-api.hinnka.me/v1\"")
    buildConfigField("String", "BUILT_IN_API_KEY", "\"\"")
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Use the Android Gradle Plugin's default debug signing configuration.
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin {
    jvmToolchain(17)
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  androidResources {
    noCompress += "tflite"
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}


dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.fragment:fragment-ktx:1.8.5")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("me.saket.telephoto:zoomable-image-coil:0.18.0")
  implementation("androidx.exifinterface:exifinterface:1.3.7")
  implementation("androidx.heifwriter:heifwriter:1.0.0")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.compose.animation:animation-core:1.7.5")
  implementation("androidx.lifecycle:lifecycle-process:2.8.7")
  implementation("androidx.core:core-splashscreen:1.0.1")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("com.google.mediapipe:tasks-vision:0.10.14")
  implementation("sh.calvin.reorderable:reorderable:2.4.3")
  implementation("androidx.media3:media3-exoplayer:1.10.0")
  implementation("androidx.media3:media3-ui:1.10.0")
  implementation("androidx.media3:media3-effect:1.10.0")
  implementation("androidx.media3:media3-transformer:1.10.0")
  implementation("org.tensorflow:tensorflow-lite:2.17.0")
  implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")
  implementation("org.tensorflow:tensorflow-lite-gpu-api:2.17.0")
  implementation("com.google.ai.edge.litert:litert-support-api:1.0.1")

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
