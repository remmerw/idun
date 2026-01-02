

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.remmerw"
version = "0.6.4"

kotlin {

    androidLibrary {
        namespace = "io.github.remmerw.idun"
        compileSdk = 36
        minSdk = 27


        // Opt-in to enable and configure device-side (instrumented) tests
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            execution = "ANDROIDX_TEST_ORCHESTRATOR"
        }
    }

    jvm()
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()
    // linuxArm64()
    // linuxX64()
    // linuxArm64()
    // wasmJs()
    // wasmWasi()
    // js()


    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.io.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.uri.kmp)


                implementation(libs.nott)
                implementation(libs.buri)
                implementation(libs.borr)
                implementation(libs.dagr)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }


    }
}



mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "idun", version.toString())

    pom {
        name = "idun"
        description = "A client-server implementation which supports pns URIs"
        inceptionYear = "2025"
        url = "https://github.com/remmerw/idun/"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "remmerw"
                name = "Remmer Wilts"
                url = "https://github.com/remmerw/"
            }
        }
        scm {
            url = "https://github.com/remmerw/idun/"
            connection = "scm:git:git://github.com/remmerw/idun.git"
            developerConnection = "scm:git:ssh://git@github.com/remmerw/idun.git"
        }
    }
}
