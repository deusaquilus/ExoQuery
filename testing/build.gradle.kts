plugins {
    //id("io.exoquery.terpal-plugin") version "2.1.0-2.0.0.PL"
    id("conventions-multiplatform")
    kotlin("multiplatform") version "2.1.0"
    id("io.kotest.multiplatform") version "6.0.0.M1"
    id("io.exoquery.exoquery-plugin") version "2.1.0-2.0.0.PL"

    // NEED serialization to be able to read the encoded XR, in the future the GradlePlugin should probably add this to the classpath
    kotlin("plugin.serialization") version "2.1.0"
}

kotlin {
    jvm()
    linuxX64()
    //@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    //wasmJs {
    //    nodejs()
    //}
    //iosX64()

    sourceSets {
        val commonMain by getting {
        }

        val commonTest by getting {
            kotlin.srcDir("$buildDir/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                // TODO probably the gradle plugin should add these? They don't seem to be just inherited from the exoquery-runtime project
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
                api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")

                // Used to ad-hoc some examples but not needed.
                //api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
                //implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
                //implementation(kotlin("test"))
                //implementation(kotlin("test-common"))
                //implementation(kotlin("test-annotations-common"))

                implementation("io.kotest:kotest-framework-engine:6.0.0.M1")
                implementation("io.kotest:kotest-assertions-core:6.0.0.M1")
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation("io.kotest:kotest-runner-junit5:6.0.0.M1")
            }
        }
    }
}

//tasks.withType<GenerateProjectFileTask> {
//    gradleArgs = "--info"
//}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions{
        freeCompilerArgs = listOf(
            "-Xcontext-receivers",
            "-P",
            "plugin:io.exoquery.terpal-plugin:traceWrappers=true"
        )
        // Otherwise will have: Could not resolve io.exoquery:pprint-kotlin:2.0.1.
        // Incompatible because this component declares a component, compatible with Java 11 and the consumer needed a component, compatible with Java 8
        java {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    // These settings are set in GradlePlugin.kt. Otherwise would need to set them here:
    //kotlinNativeCompilerPluginClasspath("io.exoquery:terpal-runtime:${...}")
    //kotlinNativeCompilerPluginClasspath("io.exoquery:decomat-core-jvm:${...}")
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
