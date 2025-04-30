import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
  id("conventions-multiplatform-nativeonly")
  kotlin("multiplatform")
  kotlin("plugin.serialization") version "2.1.20"
  id("io.kotest.multiplatform") version "6.0.0.M1"
  id("io.exoquery.exoquery-plugin") version "2.1.20-2.0.0.PL"
}

version = "1.2.0"

// Need to disable native targets here as opposed to in `nativebuild` because gradle seems to override
// what is there with defaults coming from `kotlin("multiplatform")` i.e. re-enabling all of the linking phases.
// This happens despite the fact that linking shouldn't even happen if you're not on the right host type.
tasks.named { it == "linuxX64Test" }.configureEach { enabled = HostManager.hostIsLinux }
tasks.named { it == "linkDebugTestLinuxX64" }.configureEach { enabled = HostManager.hostIsLinux }
tasks.named { it == "mingwX64Test" }.configureEach { enabled = HostManager.hostIsMingw }
tasks.named { it == "linkDebugTestMingwX64" }.configureEach { enabled = HostManager.hostIsMingw }

version = extra["controllerProjectVersion"].toString()

kotlin {

  val thisVersion = version

  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":exoquery-runner-core"))
        api("io.exoquery:controller-native:3.2.0")

        api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.6.2")
        api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        //api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        implementation("app.cash.sqldelight:native-driver:2.0.2")
      }
    }

    val commonTest by getting {
      dependencies {
        api(project(":exoquery-runner-core"))

        implementation(kotlin("test"))
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
        //implementation("io.kotest:kotest-framework-engine:5.9.1")
      }
    }
  }

  val isCI = project.hasProperty("isCI")

  // Note that this will actually setup the kotlin targets,
  // if you don't want to build them (e.g. you're not on an OSX host
  // then you need to keep these disabled, for everyday development,
  // we only want to enable these unless we are building on a CI)
  configure(targets.withType<KotlinNativeTarget>().filter {
    listOf(
      "iosX64",
      "iosArm64",
      "tvosX64",
      "tvosArm64",
      "watchosX64",
      "watchosArm32",
      "watchosArm64",
      "macosX64",
      "macosArm64",
      "iosSimulatorArm64",
      // These are not used yet
      //"watchosDeviceArm64",
      //"watchosSimulatorArm64"
      //"tvosSimulatorArm64",
    ).contains(it.name)
  }) {
    if (HostManager.hostIsMac) {
      binaries.configureEach {
        // we only need to link sqlite for the test binaries
        if (outputKind == NativeOutputKind.TEST) {
          linkerOpts.add("-lsqlite3")
        }
      }
    }
  }

  if (HostManager.hostIsLinux) {
    linuxX64 {
      compilations.configureEach {
        if (name == "test") {
          cinterops {
            val sqlite by creating {
              // use sqlite3 amalgamation on linux tests to prevent linking issues on new linux distros with dependency libraries which are too recent (for example glibc)
              // see: https://github.com/touchlab/SQLiter/pull/38#issuecomment-867171789
              println("------ Using sqlite3 amalgamation for linux tests: $rootDir/libs/linux/cinterop/sqlite3.def (exists: ${file("$rootDir/libs/linux/cinterop/sqlite3.def").exists()}) ------")
              defFile = file("$rootDir/libs/linux/cinterop/sqlite3.def")
            }
          }
        }
      }
    }
  }

  if (HostManager.hostIsMingw) {
    mingwX64 {
      binaries.configureEach {
        // we only need to link sqlite for the test binaries
        if (outputKind == NativeOutputKind.TEST) {
          linkerOpts += listOf("-Lc:\\msys64\\mingw64\\lib", "-L$rootDir\\libs\\windows".toString(), "-lsqlite3")
        }
      }
    }
  }
}

dependencies {
  commonMainApi("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
}
