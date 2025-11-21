# Ghidra Copilot

AI-assisted companion for Ghidra. It embeds a chat panel that can answer questions about your current program, suggest next steps, and run small analysis tools directly from the conversation.

## What It Does
- Chat inside Ghidra that stays in context with the open program.
- Tool calls for symbol search, data extraction, and refactoring helpers.
- Works with OpenAI, Azure OpenAI, Anthropic, and Ollama through Spring AI.

## Screenshots
- Chat panel: _screenshot coming soon_
- Tool results: _screenshot coming soon_

## Requirements
- Ghidra 11.4.2 or compatible
- Java 17+
- Network access or an on-prem model endpoint supported by Spring AI

## Install
1) Set `GHIDRA_INSTALL_DIR` to your Ghidra path.  
   - Linux/WSL: `export GHIDRA_INSTALL_DIR=/absolute/path/to/ghidra_11.4.2_PUBLIC`  
   - Windows PowerShell: `$env:GHIDRA_INSTALL_DIR = "D:\\tools\\ghidra_11.4.2_PUBLIC"`
2) Build the extension:  
   - Linux/WSL: `./gradlew buildExtension`  
   - Windows PowerShell: `.\\gradlew buildExtension`
3) Install the generated zip from `dist/` through Ghidra’s Install Extensions dialog.

## Configure Models
Inside Ghidra, open `Tool Options > Ghidra Copilot` to pick a provider and enter the API key/base URL/model names. Settings are stored in your Ghidra user directory and reused on next launch:
- OpenAI: API key (optional base URL/model override)
- Azure OpenAI: API key, endpoint, deployment, optional model
- Anthropic: API key, model
- Ollama: base URL, model

## Use It
1) Open Ghidra and enable Ghidra Copilot in the tool window.  
2) Ask questions about the current program; tool calls run automatically when needed.  
3) Use the panel settings to switch model providers or keys.
