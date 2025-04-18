import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
  id("conventions")
  kotlin("multiplatform")
}


repositories {
  //mavenLocal() // Don't include this, it causes all sorts of build horror
  mavenCentral()
  mavenLocal()
}


/*
This thing is necessary because we can't use conventions-multiplatform with exoquery-native because conventions-multiplatform
enables JVM by default (I need this for testing an exoquery-runtime functionality) which we can't do for exoquery-native as it
has no JVM-target native libraries i.e. (Terpal's) controller-native doesn't work for JVM (that's what controller-jvm is for)
and the sqlite libraries that it depends on don't have JVM targets either.
 */
kotlin {

  val isCI = project.hasProperty("isCI")
  // I.e. set this environment variable specifically to true to build (most) targets
  val fullLocal = !isCI && ((System.getenv("EXOQUERY_FULL_LOCAL")?.toBoolean() ?: false) || project.hasProperty("isLocalMultiplatform"))

  linuxX64()
  macosX64()

  val linuxCI = HostManager.hostIsLinux && isCI
  val mingCI = HostManager.hostIsMingw && isCI
  val macCI = HostManager.hostIsMac && isCI

  // No Controller for JS yet
  //if (linuxCI)
  //  js {
  //    browser()
  //    nodejs()
  //  }
  if (linuxCI) linuxArm64()
  // LinuxCI Needs to know the OSX and MingW dependencies exist even though it does not build them so it can put them in the mmodules-list metadata in maven-central.
  if (linuxCI || macCI) macosArm64()
  if (linuxCI || macCI) iosX64()
  if (linuxCI || macCI) iosArm64()
  if (linuxCI || macCI) tvosX64()
  if (linuxCI || macCI) tvosArm64()
  if (linuxCI || macCI) watchosX64()
  if (linuxCI || macCI) watchosArm32()
  if (linuxCI || macCI) watchosArm64()
  if (linuxCI || macCI) iosSimulatorArm64()
  //if (linux || mac) watchosSimulatorArm64()
  //if (linux || mac) watchosDeviceArm64()
  //if (linux || mac) tvosSimulatorArm64()
  //if (linux || mac) watchosSimulatorArm64()
  if (linuxCI || mingCI) mingwX64()
}

tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = TestExceptionFormat.SHORT
        events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}
