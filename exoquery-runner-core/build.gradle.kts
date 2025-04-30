plugins {
  //id("io.exoquery.terpal-plugin") version "2.1.20-2.0.0.PL"
  id("conventions-multiplatform-nativeonly")
  kotlin("multiplatform") version "2.1.20"
  id("io.kotest.multiplatform") version "6.0.0.M1"
  id("io.exoquery.exoquery-plugin") version "2.1.20-2.0.0.PL"

  // NEED serialization to be able to read the encoded XR, in the future the GradlePlugin should probably add this to the classpath
  kotlin("plugin.serialization") version "2.1.20"
}

version = extra["controllerProjectVersion"].toString()

kotlin {
  jvmToolchain(11)

  jvm {
    compilerOptions {
      java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
      }
    }
  }
  linuxX64()

  sourceSets {
    val commonMain by getting {
      dependencies {
        api("io.exoquery:controller-core:3.2.0")
        api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")

        // Used to ad-hoc some examples but not needed.
        //api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
        //implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
        //implementation(kotlin("test"))
        //implementation(kotlin("test-common"))
        //implementation(kotlin("test-annotations-common"))

        implementation("org.jetbrains.kotlinx:atomicfu:0.23.1")
        implementation("app.cash.sqldelight:runtime:2.0.2")

        implementation("io.kotest:kotest-framework-engine:6.0.0.M1")
        implementation("io.kotest:kotest-assertions-core:6.0.0.M1")
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }

    val jvmMain by getting {
      dependencies {
        api("io.exoquery:controller-jdbc:3.2.0")
      }
    }

    nativeMain.dependencies {
      dependencies {
        api("io.exoquery:controller-native:3.2.0")
      }
    }


    // This is a library that tests will reuse so we need to put everything into the commonMain part
    // KMP doesn't allow reusing commonTest code even in upstream commonTest dependencies
    //val commonTest by getting {
    //    dependencies {
    //    }
    //}
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
