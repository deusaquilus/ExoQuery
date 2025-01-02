plugins {
    kotlin("multiplatform") version "2.1.0" apply false
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://oss.sonatype.org/content/repositories/releases")
        maven("https://s01.oss.sonatype.org/content/repositories/releases")
        mavenLocal()
    }
}


//plugins {
//    kotlin("jvm") version "2.1.0" apply false
//}
//
//subprojects {
//    repositories {
//        mavenCentral()
//        maven("https://oss.sonatype.org/content/repositories/releases")
//        mavenLocal()
//    }
//}