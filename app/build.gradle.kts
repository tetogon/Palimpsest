import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Rust core (core-rs) -----------------------------------------------------------------
// cargo-ndk builds libpalimpsest_core.so per ABI (16 KB-aligned, see core-rs/.cargo);
// uniffi-bindgen generates the Kotlin API from an unstripped host build of the same crate.
// Needs cargo, cargo-ndk and ANDROID_NDK_HOME on the environment (scripts/env.ps1 locally).

val rustDir: File = rootProject.file("core-rs")
val rustAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val rustJniLibs: Provider<Directory> = layout.buildDirectory.dir("rust/jniLibs")
val uniffiKotlin: Provider<Directory> = layout.buildDirectory.dir("generated/uniffi")
val cargoTarget: File = System.getenv("CARGO_TARGET_DIR")?.let(::File) ?: rustDir.resolve("target")
val hostOs: String = System.getProperty("os.name").lowercase()
val hostLib = when {
    "win" in hostOs -> "palimpsest_core.dll"
    "mac" in hostOs -> "libpalimpsest_core.dylib"
    else -> "libpalimpsest_core.so"
}
val bindgenExe = if ("win" in hostOs) "uniffi-bindgen.exe" else "uniffi-bindgen"
// cargo from PATH, else from a rustup install in the home folder (F-Droid's build server
// installs rustup there without putting it on Gradle's PATH).
val cargo: String = System.getenv("CARGO")
    ?: File(System.getProperty("user.home"), ".cargo/bin/" + if ("win" in hostOs) "cargo.exe" else "cargo")
        .takeIf { it.exists() && System.getenv("PATH").orEmpty().split(File.pathSeparator).none { File(it, "cargo").exists() } }?.path
    ?: "cargo"
val rustSources = fileTree(rustDir) { include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml", ".cargo/**") }

val cargoNdk by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the Rust core for every Android ABI."
    inputs.files(rustSources)
    outputs.dir(rustJniLibs)
    workingDir(rustDir)
    // Panic messages carry source paths; mapping the checkout and the cargo registry to fixed
    // names keeps the build machine's folders out of the .so, so the APK is reproducible.
    // These flags replace target.*.rustflags from core-rs/.cargo, hence the page size again.
    val cargoHome = System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo")
    val rustFlags = listOf(
        "-C", "link-arg=-Wl,-z,max-page-size=16384",
        "--remap-path-prefix=${rustDir.absolutePath}=/palimpsest",
        "--remap-path-prefix=${cargoHome.absolutePath}=/cargo",
    ).joinToString("\u001f")
    inputs.property("rustflags", rustFlags)
    environment("CARGO_ENCODED_RUSTFLAGS", rustFlags)
    commandLine(
        listOf(cargo, "ndk", "-P", "26") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", rustJniLibs.get().asFile.absolutePath, "build", "--release"),
    )
}

val cargoHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the host library and uniffi-bindgen."
    inputs.files(rustSources)
    outputs.files(cargoTarget.resolve("debug/$hostLib"), cargoTarget.resolve("debug/$bindgenExe"))
    workingDir(rustDir)
    commandLine(cargo, "build", "--lib", "--bin", "uniffi-bindgen")
}

val uniffiBindgen by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings for the Rust core."
    dependsOn(cargoHost)
    inputs.files(rustSources)
    outputs.dir(uniffiKotlin)
    workingDir(rustDir)
    commandLine(
        cargoTarget.resolve("debug/$bindgenExe").absolutePath, "generate",
        "--library", cargoTarget.resolve("debug/$hostLib").absolutePath,
        "--crate", "palimpsest_core", "--metadata-no-deps",
        "--language", "kotlin", "--no-format",
        "--out-dir", uniffiKotlin.get().asFile.absolutePath,
    )
}

tasks.named("preBuild") { dependsOn(cargoNdk, uniffiBindgen) }

android {
    namespace = "app.palimpsest"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.palimpsest"
        minSdk = 26
        targetSdk = 34
        // Written out, not computed: F-Droid reads them from this file. A release bumps both
        // (1.2.3 -> 10203) and is tagged v<versionName>; release.yml checks that they match.
        versionCode = 204
        versionName = "0.2.4"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir(rustJniLibs)
        java.srcDir(uniffiKotlin)
    }

    signingConfigs {
        // The release key is read from keystore.properties (never committed). Without it the
        // release APK is left unsigned: CI drafts are signed offline afterwards and F-Droid
        // signs with its own key. Either way the APK
        // is not debuggable, so `adb run-as` cannot read the container.
        val props = rootProject.file("keystore.properties")
        if (props.exists()) {
            val p = Properties().apply { props.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8; the keep rules for JNA, the UniFFI bindings and zxcvbn are in proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // No Google-encrypted dependency report in the APK or bundle (F-Droid requires this).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.zxcvbn)

    testImplementation(libs.junit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // UniFFI's generated Kotlin calls the Rust core through JNA (Android flavour, @aar).
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
}
