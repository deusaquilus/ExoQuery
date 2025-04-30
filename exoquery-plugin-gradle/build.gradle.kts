plugins {
  kotlin("jvm") version "2.1.20"

  // No inclusion of `publish` here because this project is not published to maven directly
  id("maven-publish")
  id("conventions")
  id("publish-jvm")
  id("java-gradle-plugin")
  id("com.gradle.plugin-publish") version "1.1.0"
  id("com.github.gmazzo.buildconfig") version "3.1.0"
}

version = extra["pluginProjectVersion"].toString()

kotlin {
  jvmToolchain(17)
}

val decomatVersion = extra["decomatVersion"]

buildConfig {
  buildConfigField("String", "VERSION", "\"${project.version}\"")
  buildConfigField("String", "DECOMAT_VERSION", "\"${decomatVersion}\"")
}

dependencies {
  implementation(kotlin("gradle-plugin-api"))
}

gradlePlugin {
  website.set("https://github.com/exoquery/exoquery")
  vcsUrl.set("https://github.com/exoquery/exoquery.git")

  plugins {
    create("exoqueryPlugin") {
      id = "io.exoquery.exoquery-plugin"
      displayName = "ExoQuery Plugin"
      description = "Kotlin exoquery Compiler Plugin"
      implementationClass = "io.exoquery.GradlePlugin"

      tags.set(listOf("kotlin", "exoquery", "jvm"))
    }
  }
}
