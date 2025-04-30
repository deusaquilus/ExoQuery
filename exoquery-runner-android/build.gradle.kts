import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
  id("conventions")
  kotlin("multiplatform")
  id("com.android.library")
  kotlin("plugin.serialization") version "2.1.20"
  id("io.kotest.multiplatform") version "6.0.0.M1"
  id("io.exoquery.exoquery-plugin") version "2.1.20-2.0.0.PL"
  // Already on the classpath
  //id("org.jetbrains.kotlin.android") version "1.9.23"
}

version = extra["controllerProjectVersion"].toString()

configurations.forEach {
  it.exclude(group = "com.sschr15.annotations", module = "jb-annotations-kmp")
}

android {
  namespace = "io.exoquery"

  compileSdk = 34
  defaultConfig {
    minSdk = 26
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  testOptions {
    targetSdk = 34
    unitTests.isIncludeAndroidResources = true
    //unitTests.all {
    //  it.useJUnitPlatform()
    //}
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  androidTarget {
    compilations.all {
      kotlinOptions {
        jvmTarget = "17"
      }
    }
    publishLibraryVariants("release", "debug")
  }

  sourceSets {

    androidMain.dependencies {
      dependencies {
        implementation(project(":exoquery-runner-core"))

        api("io.exoquery:controller-android:3.2.0")

        api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
        api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        implementation("androidx.sqlite:sqlite-framework:2.4.0")
      }
    }

    val androidInstrumentedTest by getting {
      dependencies {
        implementation(project(":exoquery-runner-core"))

        //implementation(kotlin("test-junit"))
        //implementation("junit:junit:4.13.2")
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))

        implementation("org.robolectric:robolectric:4.13")
        implementation("androidx.test:core:1.6.1")
        implementation("androidx.test:runner:1.6.1")
        implementation("app.cash.sqldelight:android-driver:2.0.2")
        implementation("androidx.test.ext:junit:1.1.3")
        implementation("androidx.test.espresso:espresso-core:3.4.0")
      }
    }

    val androidUnitTest by getting {
      dependencies {
        implementation(project(":exoquery-runner-core"))

        //implementation(kotlin("test-junit"))
        //implementation("junit:junit:4.13.2")
        implementation(kotlin("test"))
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))

        implementation("org.robolectric:robolectric:4.13")
        implementation("androidx.test:core:1.6.1")
        implementation("androidx.test:runner:1.6.1")
        implementation("app.cash.sqldelight:android-driver:2.0.2")
        implementation("androidx.test.ext:junit:1.1.3")
        implementation("androidx.test.espresso:espresso-core:3.4.0")
      }
    }
  }

}

dependencies {
  commonMainApi("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
}

repositories {
  mavenCentral()
  mavenLocal()
}
