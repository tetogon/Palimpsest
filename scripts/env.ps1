# Local Windows build environment for Palimpsest. Dot-source it before running gradle:
#   . .\scripts\env.ps1; .\gradlew.bat :app:assembleRelease
# Toolchains are portable installs under $Dev (no admin rights, PATH untouched globally).

$Dev = if ($env:PALIMPSEST_DEV) { $env:PALIMPSEST_DEV } else { Join-Path $env:USERPROFILE "dev" }

# Android Gradle Plugin and the MinGW linker both break on non-ASCII paths: build from a
# drive letter mapped onto the project folder, with the Cargo target dir outside it.
$project = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path P:\)) { subst P: $project }
Set-Location P:\

$env:JAVA_HOME = "$Dev\jdk17"
$env:ANDROID_HOME = "$Dev\android-sdk"
$env:ANDROID_NDK_HOME = "$Dev\android-sdk\ndk\27.2.12479018"
$env:RUSTUP_HOME = "$Dev\rustup"
$env:CARGO_HOME = "$Dev\cargo"
$env:CARGO_TARGET_DIR = "$Dev\target\palimpsest"

# Rust's self-contained MinGW lacks an assembler for dlltool; use the full WinLibs MinGW.
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = "$Dev\mingw64\bin\gcc.exe"
$env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS = "-C link-self-contained=no"
$env:Path = "$Dev\mingw64\bin;$Dev\cargo\bin;$env:Path"
