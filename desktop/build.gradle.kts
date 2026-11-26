import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  jvm()
  sourceSets {
    jvmMain.dependencies {
      implementation(project(":shared"))
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.swing)
      implementation(libs.ktor.server.core)
      implementation(libs.ktor.server.cio)
      implementation(libs.zxing.core)
    }
  }
}

compose.desktop {
  application {
    mainClass = "com.example.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Exe)
      packageName = "StudyPilot"
      packageVersion = "1.0.0"
      description = "StudyPilot for desktop"
      modules("java.sql", "java.naming", "jdk.unsupported", "java.net.http")
      windows {
        menuGroup = "StudyPilot"
        shortcut = true
        perUserInstall = true
        upgradeUuid = "4b3f2a7c-9d1e-4c55-8a0b-6f2e1d9c7a31"
      }
    }
  }
}
