plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

val publicationVersion = providers.environmentVariable("VERSION").orElse("0.1.0").get()

android {
    namespace = "com.exoplayerprofiler"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Lint runs in CI (./gradlew :profiler:lintDebug). Any finding fails the build.
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        // Version pins are deliberate: Media3 and the toolchain are upgraded through a reviewed
        // change, never by lint output. Newer-version checks would fail every build.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ExoPlayer is the only runtime dependency of the profiler.
    api(libs.androidx.media3.exoplayer)
    testImplementation(libs.junit)
    // android.jar stubs org.json; unit tests need a real implementation.
    testImplementation(libs.org.json)
    // Instrumented engine tests run on a device or emulator.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// JitPack's multi-module coordinate is com.github.<owner>.<repo>:<module>:<tag>.
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            groupId = "com.github.vicky-droid.exoplayer-profiler-android"
            artifactId = "profiler"
            version = publicationVersion
            pom {
                name.set("ExoPlayer Playback Profiler")
                description.set("Records ExoPlayer playback sessions to on-device JSON files.")
                url.set("https://github.com/vicky-droid/exoplayer-profiler-android")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                scm {
                    url.set("https://github.com/vicky-droid/exoplayer-profiler-android")
                    connection.set("scm:git:git://github.com/vicky-droid/exoplayer-profiler-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/vicky-droid/exoplayer-profiler-android.git")
                }
            }
        }
    }
    repositories {
        // Local verification repository. Replace with the real release repository.
        maven {
            name = "buildDir"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
