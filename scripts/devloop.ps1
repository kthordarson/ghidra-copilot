if (-not $env:GHIDRA_INSTALL_DIR) {
    Write-Error "GHIDRA_INSTALL_DIR is not set. Set it to your ghidra installation directory first."
    exit 1
}

$gradleWrapper = Join-Path $PSScriptRoot "..\gradlew.bat"

if (-not (Test-Path $gradleWrapper)) {
    Write-Error "Gradle wrapper not found at $gradleWrapper. Make sure the repository includes gradlew/gradlew.bat."
    exit 1
}

Write-Host "Starting Gradle continuous build via gradlew (Ctrl+C to stop)..."
& $gradleWrapper --continuous classes
