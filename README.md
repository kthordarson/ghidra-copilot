# ghidra-copilot

## Local Development Loop (without IDE plugins)

1. **Point to your Ghidra install**  
   - Linux/WSL:
     ```bash
     export GHIDRA_INSTALL_DIR=/absolute/path/to/ghidra_11.4.2_PUBLIC
     ```
   - Windows (PowerShell):
     ```powershell
     $env:GHIDRA_INSTALL_DIR = "D:\tools\ghidra_11.4.2_PUBLIC"
     ```
   Gradle tasks in this project rely on the variable being set.

2. **Expose the project as a module**  
   Either move the project under `…/Ghidra/Extensions/` or let the helper script create the link:
   - Linux/WSL:
     ```bash
     ln -s /path/to/GhidraCopilot "$GHIDRA_INSTALL_DIR/Ghidra/Extensions/GhidraCopilot"
     ```
   - Windows (PowerShell, run from repo root):
     ```powershell
     ./scripts/link-module.ps1
     ```
   With the module on the classpath, Ghidra picks up new class files without an
   install/uninstall cycle.

3. **Run a continuous build**  
   In a terminal inside the project directory:
   - Linux/WSL:
     ```bash
     ./scripts/devloop.sh
     ```
   - Windows (PowerShell):
     ```powershell
     ./scripts/devloop.ps1
     ```
   Gradle recompiles the extension every time you save a file. Artifacts land in
   `build/classes/java/main` and `build/libs/GhidraCopilot.jar`.

4. **Launch Ghidra in debug/dev mode**  
   Use the bundled script so the JVM knows about development modules:
   - Linux/WSL:
     ```bash
     ./scripts/run-ghidra.sh
     ```
   - Windows (PowerShell):
     ```powershell
     ./scripts/run-ghidra.ps1
     ```
   Keep this instance running while you code. Closing/reopening your tool window
   (or using `File → Restart`) picks up the freshly built classes.

5. **Run tests when you add them**  
   Place tests under `src/test/java` and execute:
   ```bash
   gradle test
   ```

6. **Package for distribution (when ready)**  
   ```bash
   gradle buildExtension
   ```
   The distributable zip is written to `dist/`.

This setup gives you a quick “edit → save → auto-build → refresh in Ghidra”
cycle without relying on the IntelliJ or Eclipse Ghidra plugins.
