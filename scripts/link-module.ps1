param(
    [string]$ExtensionName = "GhidraCopilot"
)

if (-not $env:GHIDRA_INSTALL_DIR) {
    Write-Error "GHIDRA_INSTALL_DIR is not set. Set it to your ghidra_XX directory before running."
    exit 1
}

$extensionRoot = Join-Path -Path $env:GHIDRA_INSTALL_DIR -ChildPath "Ghidra\\Extensions\\$ExtensionName"

if (-not (Test-Path $extensionRoot)) {
    $parent = Split-Path -Parent $extensionRoot
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }

    $targetPath = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
    New-Item -ItemType Junction -Path $extensionRoot -Target $targetPath | Out-Null
    Write-Host "Created junction $extensionRoot -> $targetPath"
}
else {
    Write-Host "Extension path already exists at $extensionRoot"
}
