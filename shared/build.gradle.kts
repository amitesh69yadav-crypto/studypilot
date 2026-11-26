plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kmp.library)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
}

kotlin {
  androidLibrary {
    namespace = "com.example.shared"
    compileSdk = 37
    minSdk = 24
    androidResources { enable = true }
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
  }
  jvm("desktop")

  sourceSets {
    commonMain.dependencies {
      api(libs.cmp.runtime)
      api(libs.cmp.foundation)
      api(libs.cmp.material3)
      api(libs.cmp.icons.extended)
      api(libs.cmp.resources)
      api(libs.lifecycle.viewmodel.kmp)
      api(libs.lifecycle.runtime.kmp)
      api(libs.room.kmp.runtime)
      api(libs.kotlinx.coroutines.core)
      api(libs.okhttp)
      // Android ships org.json; the desktop app bundles it.
      compileOnly(libs.org.json)
    }
    androidMain.dependencies {
      implementation(libs.androidx.activity.compose)
    }
    val desktopMain by getting {
      dependencies {
        api(libs.org.json)
        api(libs.sqlite.bundled)
      }
    }
  }
}

compose.resources {
  publicResClass = true
  packageOfResClass = "com.example.shared.resources"
}

dependencies {
  add("kspAndroid", libs.room.kmp.compiler)
  add("kspDesktop", libs.room.kmp.compiler)
}
