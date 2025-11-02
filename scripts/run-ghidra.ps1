if (-not $env:GHIDRA_INSTALL_DIR) {
    Write-Error "GHIDRA_INSTALL_DIR is not set. Set it to your ghidra installation directory first."
    exit 1
}

& "$env:GHIDRA_INSTALL_DIR\support\ghidraDebug.bat" @Args
