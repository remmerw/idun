
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlin.serialization)
}

group = "io.github.remmerw"
version = "0.2.6"

kotlin {

    jvm()
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()


    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.io.core)
                implementation(libs.uri.kmp)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.network)
                implementation(libs.atomicfu)
                implementation("io.github.remmerw:asen:0.2.6")
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}


android {
    namespace = "io.github.remmerw.idun"
    compileSdk = 36
    defaultConfig {
        minSdk = 27
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

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
