pluginManagement {
    // Kotlin 2.4 메타데이터의 D8·R8 처리를 공식 최소 지원 버전으로 맞춥니다.
    buildscript {
        repositories {
            maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        }
        dependencies { classpath("com.android.tools:r8:9.1.29") }
    }
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.4.0")
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}
rootProject.name = "Seal"
include (":app")
include(":color")