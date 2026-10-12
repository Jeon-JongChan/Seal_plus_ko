@file:Suppress("UnstableApiUsage")

import com.android.build.api.instrumentation.*
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.objectweb.asm.*
import org.objectweb.asm.tree.*


import com.android.build.api.variant.FilterConfiguration
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktfmt.gradle)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")

val splitApks = !project.hasProperty("noSplits")

val abiFilterList = (properties["ABI_FILTERS"] as String).split(';')

val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

val baseVersionName = currentVersion.name

android {
    compileSdk = 35

    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
        signingConfigs {
            create("githubPublish") {
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
                storeFile = file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"].toString()
            }
        }
    }

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.junkfood.seal"
        minSdk = 24
        targetSdk = 35
        // 표시 버전과 독립된 번호를 올려 이전 2.0 개발판 APK보다 낮아지지 않게 합니다.
        versionCode = 200_000_160

        versionName = baseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    isUniversalApk = true
                }
            }
        } else {
            ndk { abiFilters.addAll(abiFilterList) }
        }
    }

    // KSP 인자 공급자의 공백 파싱 제한을 피하도록 경로를 키·값으로 직접 전달합니다.
    ksp {
        arg("room.schemaLocation", file("schemas").absolutePath)
        arg("room.incremental", "true")
    }

    androidComponents {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                val name =
                    if (splitApks) {
                        output.filters
                            .find { it.filterType == FilterConfiguration.FilterType.ABI }
                            ?.identifier
                    } else {
                        abiFilterList.firstOrNull()
                    }

                val baseAbiCode = abiCodes[name]

                if (baseAbiCode != null) {
                    output.versionCode.set(baseAbiCode + (output.versionCode.get() ?: 0))
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
        }
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("githubPublish")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Seal Plus Debug")
        }
    }

    flavorDimensions += "publishChannel"

    productFlavors {
        create("generic") {
            dimension = "publishChannel"
            isDefault = true
        }

        create("githubPreview") {
            dimension = "publishChannel"
            applicationIdSuffix = ".preview"
            resValue("string", "app_name", "Seal Plus Preview")
        }

        create("fdroid") {
            dimension = "publishChannel"
            versionName = "$baseVersionName-(F-Droid)"
        }
    }

    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")) }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "Seal-${defaultConfig.versionName}-${name}.apk"
        }
    }

    kotlinOptions { freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs.useLegacyPackaging = true
    }
    androidResources { generateLocaleConfig = true }

    namespace = "com.junkfood.seal"

    // 분리 이전 플러그인 필드를 참조하는 보존 테스트는 플러그인 회귀 검사로 대체합니다.
    sourceSets.getByName("androidTest").java.setSrcDirs(listOf("src/androidTest/java/com/plugin"))
}

kotlin.sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("src/androidTest/java/com/plugin"))

// 3차 개선안의 옛 화질 경계는 플러그인의 6차 표 경계 검사로 대체합니다.
tasks.withType<Test>().configureEach {
    filter.excludeTestsMatching("com.junkfood.seal.util.MediaRulesTest.revisionThreeClassificationAndPageIdentity")
}

ktfmt { kotlinLangStyle() }

kotlin { jvmToolchain(21) }

// 경로 인자를 직접 전달해도 스키마 변경이 증분 빌드 입력에 반영되도록 추적합니다.
tasks.matching { it.name.startsWith("ksp") }.configureEach {
    inputs.dir(layout.projectDirectory.dir("schemas")).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":color"))

    // 이미지 AI는 별도 프로세스에서 실행하며 모델은 사용자가 내려받습니다.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("com.google.android.gms:play-services-media-effect-enhancement:16.0.0-beta04")

    implementation(libs.bundles.core)

    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidxCompose)
    implementation(libs.bundles.accompanist)

    implementation(libs.coil.kt.compose)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)

    implementation(libs.bundles.youtubedlAndroid)

    implementation(libs.mmkv)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.compose.ui.tooling)
}

/** 원본 소스는 그대로 두고 저장 루트 함수의 빌드 산출물만 공통 플러그인으로 연결합니다. */
abstract class StoragePrivacyVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    /** 다른 원본 클래스와 다른 함수는 변환하지 않습니다. */
    override fun isInstrumentable(classData: ClassData): Boolean = classData.className == "com.junkfood.seal.util.FileUtil"

    /** 대상 함수의 서명·접근 제한·Kotlin 메타데이터를 유지하며 실행 본문만 교체합니다. */
    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            /** 내부 함수의 변형 이름도 인식하고 File 반환 함수 하나만 선택합니다. */
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val target = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (!name.startsWith("getExternalDownloadDirectory") || descriptor != "()Ljava/io/File;") return target
                return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
                    /** 새 본문의 프레임은 AGP가 계산하며 원본 지역 변수·예외 테이블을 남기지 않습니다. */
                    override fun visitEnd() {
                        instructions.clear(); tryCatchBlocks.clear(); localVariables?.clear()
                        instructions.add(FieldInsnNode(Opcodes.GETSTATIC, "com/plugin/compatibility/StorageDirectories", "INSTANCE", "Lcom/plugin/compatibility/StorageDirectories;"))
                        instructions.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/plugin/compatibility/StorageDirectories", "externalDownloadDirectory", "()Ljava/io/File;", false))
                        instructions.add(InsnNode(Opcodes.ARETURN))
                        maxStack = 1; maxLocals = 1
                        accept(target)
                    }
                }
            }
        }
}

// 빌드 시에만 원본 함수의 실행 연결을 바꾸며 원본 파일은 읽기 전용으로 보존합니다.
val components = extensions.getByType<ApplicationAndroidComponentsExtension>()
components.onVariants(components.selector().all()) { variant ->
    variant.instrumentation.transformClassesWith(StoragePrivacyVisitorFactory::class.java, InstrumentationScope.PROJECT) {}
    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS)
}
