import org.jetbrains.dokka.gradle.AbstractDokkaTask

plugins {
  `maven-publish`
  signing
  kotlin("multiplatform") version "2.1.20" apply false
  id("com.android.library") version "8.2.0" apply false
  id("io.github.gradle-nexus.publish-plugin") version "1.1.0" apply false
  id("org.jetbrains.dokka") version "1.9.10" apply false
}

allprojects {
  repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/releases")
    maven("https://s01.oss.sonatype.org/content/repositories/releases")
    google()
    maven(url = "https://plugins.gradle.org/m2/")
    mavenLocal()
  }
}

tasks.register("publishLinux") {
  dependsOn(
    gradle.includedBuild(Release.Project.`exoquery-engine`).task(":publishAllPublicationsToOssRepository"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-gradle`).task(":publish"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-kotlin`).task(":publish"),
    // Even though targets like MacosX64 are not built or published on the linux CI, it needs to know
    // then in order to publish the right Maven-central metadata (i.e. the .module file) that details
    // which build-variants exist
    ":${Release.Project.`exoquery-runner-core`}:publishAllPublicationsToOssRepository",
    ":${Release.Project.`exoquery-runner-android`}:publishAllPublicationsToOssRepository",
    ":${Release.Project.`exoquery-runner-native`}:publishAllPublicationsToOssRepository",
    ":${Release.Project.`exoquery-runner-jdbc`}:publishAllPublicationsToOssRepository",
  )
}

tasks.register("publishLinuxLocal") {
  ":${Release.Project.`exoquery-engine`}:publishToMavenLocal"
  dependsOn(
    gradle.includedBuild(Release.Project.`exoquery-engine`).task(":publishToMavenLocal"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-gradle`).task(":publishToMavenLocal"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-kotlin`).task(":publishToMavenLocal"),
    ":${Release.Project.`exoquery-runner-core`}:publishToMavenLocal",
    ":${Release.Project.`exoquery-runner-android`}:publishToMavenLocal",
    ":${Release.Project.`exoquery-runner-native`}:publishToMavenLocal",
    ":${Release.Project.`exoquery-runner-jdbc`}:publishToMavenLocal",
  )
}

tasks.register("publishMac") {
  Release.macBuildCommands.forEach {
    dependsOn(gradle.includedBuild(Release.Project.`exoquery-engine`).task(":$it"))
  }
  val targets =
    listOf(
      Release.Project.`exoquery-runner-core`,
      Release.Project.`exoquery-runner-native`,
    ).flatMap { project ->
      Release.macBuildCommands.map { ":$project:$it" }
    }
  dependsOn(*targets.toTypedArray())
}

tasks.register("publishWindows") {
  Release.windowsBuildCommands.forEach {
    dependsOn(gradle.includedBuild(Release.Project.`exoquery-engine`).task(":$it"))
  }
  val targets =
    listOf(
      Release.Project.`exoquery-runner-core`,
      Release.Project.`exoquery-runner-native`,
    ).flatMap { project ->
      Release.windowsBuildCommands.map { ":$project:$it" }
    }
  dependsOn(*targets.toTypedArray())
}


tasks.register("publishLinuxNoRunner") {
  dependsOn(
    gradle.includedBuild(Release.Project.`exoquery-engine`).task(":publishAllPublicationsToOssRepository"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-gradle`).task(":publish"),
    gradle.includedBuild(Release.Project.`exoquery-plugin-kotlin`).task(":publish"),
  )
}
tasks.register("publishMacNoRunner") {
  Release.macBuildCommands.forEach {
    dependsOn(gradle.includedBuild(Release.Project.`exoquery-engine`).task(":$it"))
  }
}
tasks.register("publishWindowsNoRunner") {
  Release.windowsBuildCommands.forEach {
    dependsOn(gradle.includedBuild(Release.Project.`exoquery-engine`).task(":$it"))
  }
}


object Release {

  object Project {
    val `exoquery-engine` = "exoquery-engine"
    val `exoquery-plugin-gradle` = "exoquery-plugin-gradle"
    val `exoquery-plugin-kotlin` = "exoquery-plugin-kotlin"

    val `exoquery-runner-core` = "exoquery-runner-core"
    val `exoquery-runner-jdbc` = "exoquery-runner-jdbc"
    val `exoquery-runner-native` = "exoquery-runner-native"
    val `exoquery-runner-android` = "exoquery-runner-android"
  }

  val macBuildCommands =
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
      "iosSimulatorArm64"
    ).map { "publish${it.capitalize()}PublicationToOssRepository" }

  val windowsBuildCommands =
    listOf(
      "mingwX64"
    ).map { "publish${it.capitalize()}PublicationToOssRepository" }

  fun String.capitalize() = this.replaceFirstChar { it.uppercase() }
}





subprojects {
  // Do not publish the testing project
  if (name in listOf("testing")) {
    println("---------- Do not publish: ${name} ----------")
    return@subprojects
  }

  apply {
    plugin("org.jetbrains.dokka")
    plugin("maven-publish")
    plugin("signing")
  }
  tasks.withType<PublishToMavenRepository>().configureEach {
    onlyIf {
      publication.artifactId != "testing"
    }
  }

  val dokkaHtml by tasks.getting(org.jetbrains.dokka.gradle.DokkaTask::class)
  val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(dokkaHtml)
    archiveClassifier.set("javadoc")
    from(dokkaHtml.outputDirectory)
  }

  publishing {
    val user = System.getenv("SONATYPE_USERNAME")
    val pass = System.getenv("SONATYPE_PASSWORD")

    repositories {
      maven {
        name = "Oss"
        setUrl {
          val repositoryId = System.getenv("SONATYPE_REPOSITORY_ID") ?: error("Missing env variable: SONATYPE_REPOSITORY_ID")
          "https://s01.oss.sonatype.org/service/local/staging/deployByRepositoryId/$repositoryId/"
        }
        credentials {
          username = user
          password = pass
        }
      }
      maven {
        name = "Snapshot"
        setUrl { "https://s01.oss.sonatype.org/content/repositories/snapshots/" }
        credentials {
          username = user
          password = pass
        }
      }
    }

    publications.withType<MavenPublication> {
      artifact(javadocJar)

      pom {
        name.set("exoquery")
        description.set("ExoQuery - Language Integrated Query for Kotlin")
        url.set("https://github.com/exoquery/exoquery")

        licenses {
          license {
            name.set("The Apache Software License, Version 2.0")
            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("repo")
          }
        }

        developers {
          developer {
            name.set("Alexander Ioffe")
            email.set("deusaquilus@gmail.com")
            organization.set("github")
            organizationUrl.set("http://www.github.com")
          }
        }

        scm {
          url.set("https://github.com/exoquery/exoquery/tree/main")
          connection.set("scm:git:git://github.com/exoquery/exoquery.git")
          developerConnection.set("scm:git:ssh://github.com:exoquery/exoquery.git")
        }
      }
    }
  }

  val isCI = project.hasProperty("isCI")
  val isLocal = !isCI
  val noSign = project.hasProperty("nosign")
  val doNotSign = isLocal || noSign

  signing {
    // Sign if we're not doing a local build and we haven't specifically disabled it
    if (!doNotSign) {
      val signingKeyRaw = System.getenv("NEW_SIGNING_KEY_ID_BASE64")
      if (signingKeyRaw == null) error("ERROR: No Signing Key Found")
      // Seems like the right way was to have newlines after all the exported (ascii armored) lines
      // and you can put them into the github-var with newlines but if you
      // include the "-----BEGIN PGP PRIVATE KEY BLOCK-----" and "-----END PGP PRIVATE KEY BLOCK-----"
      // parts with that then errors happen. Have a look at https://github.com/gradle/gradle/issues/15718 for more detail
      // Ultimately however `iurysza` is only partially correct and they key-itself does not need to be escaped
      // and can be put into a github-var with newlines.
      val signingKey =
        "-----BEGIN PGP PRIVATE KEY BLOCK-----\n\n${signingKeyRaw}\n-----END PGP PRIVATE KEY BLOCK-----"
      useInMemoryPgpKeys(
        System.getenv("NEW_SIGNING_KEY_ID_BASE64_ID"),
        signingKey,
        System.getenv("NEW_SIGNING_KEY_ID_BASE64_PASS")
      )
      sign(publishing.publications)
    }
  }

// Fix for Kotlin issue: https://youtrack.jetbrains.com/issue/KT-61313
  tasks.withType<Sign>().configureEach {
    val pubName = name.removePrefix("sign").removeSuffix("Publication")

    // These tasks only exist for native targets, hence findByName() to avoid trying to find them for other targets

    // Task ':linkDebugTest<platform>' uses this output of task ':sign<platform>Publication' without declaring an explicit or implicit dependency
    tasks.findByName("linkDebugTest$pubName")?.let {
      mustRunAfter(it)
    }
    // Task ':compileTestKotlin<platform>' uses this output of task ':sign<platform>Publication' without declaring an explicit or implicit dependency
    tasks.findByName("compileTestKotlin$pubName")?.let {
      mustRunAfter(it)
    }
  }

// Was having odd issues happening in CI releases like this:
// e.g. Task ':pprint-kotlin-core:publish<AndroidNativeArm32>PublicationToOssRepository' uses this output of task ':pprint-kotlin-core:sign<AndroidNativeArm64>Publication' without declaring an explicit or implicit dependency.
// I tried a few things that caused other issues. Ultimately the working solution I got from here:
// https://github.com/gradle/gradle/issues/26091#issuecomment-1722947958
  tasks.withType<AbstractPublishToMaven>().configureEach {
    val signingTasks = tasks.withType<Sign>()
    mustRunAfter(signingTasks)

    // Also, do not publish the decomat-examples project
    onlyIf {
      !this.project.name.contains("decomat-examples")
    }
  }

}
