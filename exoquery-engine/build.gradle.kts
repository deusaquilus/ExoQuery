import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
  kotlin("multiplatform") version "2.1.20"
  id("maven-publish")

  id("io.exoquery.terpal-plugin") version "2.1.20-2.0.0.PL"
  id("io.kotest.multiplatform") version "6.0.0.M1"
  id("conventions-multiplatform")
  id("publish")

  id("com.google.devtools.ksp") version "2.1.20-1.0.31"
  kotlin("plugin.serialization") version "2.1.20"
}

version = extra["pluginProjectVersion"].toString()

dependencies {
  add("kspCommonMainMetadata", "io.exoquery:decomat-ksp:0.5.0")
  commonMainApi("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xwhen-guards")
  }

  jvm()
  linuxX64()

  sourceSets {
    val commonMain by getting {
      kotlin.srcDir("$buildDir/generated/ksp/metadata/commonMain/kotlin")

      dependencies {
        api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")

        api(kotlin("reflect"))
        // Actually this is going to be 0.0.5 - using an unpublished one now
        //ksp("io.exoquery:decomat-ksp:0.5.0")
        api("io.exoquery:pprint-kotlin-kmp:3.0.0")
        // Actually this is going to be 0.0.5 - using an unpublished one now
        api("io.exoquery:decomat-core:0.5.0")
        api("io.exoquery:terpal-runtime:2.1.20-2.0.0.PL")
        // This is a java-only library, I have no idea who it can even be here.
        // maybe if I actually attempt to use it in KMP mode in a non-java target it will actually fail
        api("com.github.vertical-blank:sql-formatter:2.0.4")
        implementation("com.sschr15.annotations:jb-annotations-kmp:24.1.0+apple")
      }
    }

    val commonTest by getting {
      dependencies {
        implementation("io.kotest:kotest-framework-engine:6.0.0.M1")
        implementation("io.kotest:kotest-assertions-core:6.0.0.M1")
        // Used to ad-hoc some examples but not needed.
        //api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        //implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
        implementation(kotlin("test"))
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
        //jvm-only setup for kotest, need to find the KMP way
        //implementation("io.kotest:kotest-runner-junit5:5.8.0")
      }
    }

    val jvmMain by getting {
      dependencies {
        api("com.github.vertical-blank:sql-formatter:2.0.4")
      }
    }

    val jvmTest by getting {
      dependencies {
        implementation("io.kotest:kotest-runner-junit5:6.0.0.M1")
      }
    }
  }

  jvmToolchain(17)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
  if (name != "kspCommonMainKotlinMetadata") {
    dependsOn("kspCommonMainKotlinMetadata")
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
  compilerOptions {
    freeCompilerArgs.add("-Xwhen-guards")

    // DOesn't work in KMP
    //freeCompilerArgs.add("-Xcontext-receivers")
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

repositories {
  mavenCentral()
  mavenLocal()
  maven("https://s01.oss.sonatype.org/content/repositories/releases")
}

ksp {
  arg("matchableName", "Mat")
  arg("componentName", "Slot")
  arg("middleComponentName", "MSlot")
  arg("constructorComponentName", "CS")
  arg("fromHereFunctionName", "cs")
  arg("fromFunctionName", "csf")
  arg("renderAdtFunctions", "true")
  arg("renderFromHereFunction", "false")
  java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

tasks.named<Test>("jvmTest") {
  useJUnitPlatform()
  filter {
    isFailOnNoMatchingTests = false
  }
  testLogging {
    showExceptions = true
    showStandardStreams = true
    events = setOf(
      org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
      org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
    )
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

// Add the kspCommonMainKotlinMetadata dependency to sourcesJar tasks if needed
// for example:
//tasks.findByName("jsSourcesJar")?.dependsOn("kspCommonMainKotlinMetadata")
//tasks.findByName("jvmSourcesJar")?.dependsOn("kspCommonMainKotlinMetadata")
//tasks.findByName("linuxX64SourcesJar")?.dependsOn("kspCommonMainKotlinMetadata")
//tasks.findByName("mingwX64SourcesJar")?.dependsOn("kspCommonMainKotlinMetadata")
//tasks.findByName("sourcesJar")?.dependsOn("kspCommonMainKotlinMetadata")
// Preferably we can just use the following to get every target:
tasks.filter { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.forEach {
  it.dependsOn("kspCommonMainKotlinMetadata")
}
