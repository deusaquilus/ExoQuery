import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm") version "2.1.20"
  id("maven-publish")
  id("conventions")
  id("publish-jvm")
  kotlin("plugin.serialization") version "2.1.20"
  kotlin("kapt") version "2.1.20"
}

version = extra["pluginProjectVersion"].toString()
val runtimeVersion = extra["pluginProjectVersion"].toString()

kotlin {
  jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
  compilerOptions {
    freeCompilerArgs.add("-Xcontext-receivers")
    // Otherwise will have: Could not resolve io.exoquery:pprint-kotlin:2.0.1.
    // Incompatible because this component declares a component, compatible with Java 11 and the consumer needed a component, compatible with Java 8
    java {
      sourceCompatibility = JavaVersion.VERSION_11
      targetCompatibility = JavaVersion.VERSION_11
    }
    // If I remove this I get:
    //  'compileJava' task (current target is 11) and 'kaptGenerateStubsKotlin' task (current target is 1.8) jvm target compatibility should be set to the same Java version.
    // Not sure why
    jvmTarget.set(JvmTarget.JVM_11)
  }
}

val thisVersion = version

dependencies {
  api("io.exoquery:exoquery-engine:${runtimeVersion}")

  compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable")

  kapt("com.google.auto.service:auto-service:1.0.1")
  compileOnly("com.google.auto.service:auto-service-annotations:1.0.1")
  //implementation("com.facebook:ktfmt:0.43") <-- requires kotlin-test:1.6.10 so need to add to the GradlePlugin libs if we want to use this

  // Actually this is going to be 0.0.5 - using an unpublished one now
  api("io.exoquery:decomat-core:0.4.0")
  api("io.exoquery:pprint-kotlin:3.0.0")
  api("com.github.vertical-blank:sql-formatter:2.0.4")
  api(kotlin("reflect"))
}

repositories {
  mavenCentral()
  mavenLocal()
  maven("https://s01.oss.sonatype.org/content/repositories/releases")
}
