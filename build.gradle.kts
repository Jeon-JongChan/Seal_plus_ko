plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.room) apply false
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
}

// 루트 Gradle 보고서는 포터블 폴더에 모으되 clean이 도구·캐시까지 지우지 않게 분리합니다.
layout.buildDirectory.set(layout.projectDirectory.dir(".build/gradle-output"))

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

