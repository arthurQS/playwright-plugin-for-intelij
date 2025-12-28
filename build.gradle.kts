plugins {
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.stumpfdev"
version = "0.1.1"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

intellij {
    version.set("2024.2.4")
    type.set("IC")
    plugins.set(listOf("java"))
    sandboxDir.set("$buildDir/idea-sandbox2")
}

tasks {
    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set("")
    }
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "17"
    }
    runIde {
        // Disable bundled Gradle plugin to avoid GradleJvmSupportMatrix state errors in sandbox
        systemProperty("idea.plugins.disabled", "org.jetbrains.plugins.gradle,com.intellij.gradle")
    }
}
