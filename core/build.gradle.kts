import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // JNA classes come from the platform at runtime: jna@aar on Android, the jar in tests.
    compileOnly(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.jna)
    // Only for its bundled desktop libsodium binaries; tests call them through our own binding.
    testImplementation(libs.lazysodium.java)
}

tasks.test {
    // Argon2id runs at the real 64 MiB.
    maxHeapSize = "512m"
    if (project.hasProperty("writeVectors")) {
        systemProperty("vectorsDir", rootProject.file("test-vectors").absolutePath)
        outputs.upToDateWhen { false }
    }
}
