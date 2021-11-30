import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinMetadataTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget


val coroutinesVersion = "1.5.2-native-mt"// "1.5.2"
val atomicfuVersion = "0.17.0"
val statelyVersion = "1.2.0"

plugins {
    kotlin("multiplatform") version "1.6.0"
    id("org.jetbrains.dokka") version "0.10.0"
    id("maven-publish")
    id("signing")
}
 
repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}


enum class test_bins { cat, fixedlink, iopoll, lfsopenat, link, opath, register, stdout, teardown, timeout, }

kotlin {
    linuxX64 { binaries { test_bins.values().forEach { executable(it.name, listOf(DEBUG/*, RELEASE*/)) { baseName = it.name;entryPoint = "test.$it.main" } } }
         println( "compilations: ${compilations}")
        compilations.first()     .cinterops {
            println( "compilation: $this")

            create(name) {
                defFile = project.file("src/nativeInterop/cinterop/linux_uring.def")
            }
        }
    }
    targets.withType<KotlinNativeTarget> {
        val main by compilations.getting {
            kotlinOptions.freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
        }
    }

/*
    targets.withType<KotlinJsTarget> {
        val test by compilations.getting {
            kotlinOptions.freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
        }
    }
*/

    // do this in afterEvaluate, when nativeMain compilation becomes available
    afterEvaluate {
        targets.withType<KotlinMetadataTarget> {
            for (compilation in compilations) {
                if (compilation.name == "nativeMain") {
                    compilation.kotlinOptions.freeCompilerArgs = listOf("-Xopt-in=kotlin.RequiresOptIn")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {//1.5.2-native-mt
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
                implementation("co.touchlab:stately-common:$statelyVersion")
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val nativeTest by creating {
            dependsOn(commonTest)
        }

        /*
        val appleMain by creating

        val appleTargets = listOf(
            "iosX64",
            "iosArm64",
            "iosArm32",
            "iosSimulatorArm64",
            "macosArm64",
            "macosX64",
            "tvosArm64",
            "tvosSimulatorArm64",
            "tvosX64",
            "watchosArm32",
            "watchosArm64",
            "watchosSimulatorArm64",
            "watchosX86"
            // waiting on https://github.com/Kotlin/kotlinx.coroutines/pull/2679
            //"watchosX64"
        )
        appleTargets.forEach {
            getByName("${it}Main") {
                dependsOn(appleMain)
            }
        }*/

        (/*appleTargets +*/ listOf(/*"mingwX64", */"linuxX64")).forEach {
            getByName("${it}Main") {
                dependsOn(nativeMain)
            }
            getByName("${it}Test") {
                dependsOn(nativeTest)
            }
        }
    }
}

kotlin {
    targets.all {
        compilations.all {
            // https://youtrack.jetbrains.com/issue/KT-46257
            kotlinOptions.allWarningsAsErrors = false
        }
    }
}

val ktlintConfig by configurations.creating

dependencies {
    ktlintConfig("com.pinterest:ktlint:0.41.0")
}

val ktlint by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Check Kotlin code style."
    classpath = ktlintConfig
    main = "com.pinterest.ktlint.Main"
    args = listOf("src/**/*.kt")
}

val ktlintformat by tasks.registering(JavaExec::class) {
    group = "formatting"
    description = "Fix Kotlin code style deviations."
    classpath = ktlintConfig
    main = "com.pinterest.ktlint.Main"
    args = listOf("-F", "src/**/*.kt", "*.kts")
}

val checkTask = tasks.named("check")
checkTask.configure {
    dependsOn(ktlint)
}

apply("publish.gradle")
