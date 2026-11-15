plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

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

// Publishing. Coordinates must match the FL SDK's dependency: com.exoplayerprofiler:profiler.
// Before publishing to a real repository, the owner must fill in the license, SCM and developer
// entries below (required by Maven Central) and set the release repository credentials.
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
            groupId = "com.exoplayerprofiler"
            artifactId = "profiler"
            version = "0.1.0"
            pom {
                name.set("ExoPlayer Playback Profiler")
                description.set("Records ExoPlayer playback sessions to on-device JSON files.")
                // TODO(owner): licenses { license { ... } }, scm { ... }, developers { ... }
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
