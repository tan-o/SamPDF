$ErrorActionPreference = "Stop"
$target = "aarch64-linux-android"
$ndkRoot = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk\27.1.12297006"
$linker = Join-Path $ndkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang.cmd"
if (-not (Test-Path -LiteralPath $linker)) {
    throw "Android NDK 27.1.12297006 linker was not found: $linker"
}

Push-Location $PSScriptRoot
try {
    rustup target add $target
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $linker
    cargo build --manifest-path "Cargo.toml" --release --target $target
} finally {
    Pop-Location
}

$destination = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force $destination | Out-Null
Copy-Item -LiteralPath "$PSScriptRoot\target\$target\release\libsamreader_layout.so" `
    -Destination "$destination\libsamreader_layout.so" -Force
