# Agent Notes

- Always build the project with gradle to validate.
- Build via `GhidraCopilot> .\gradlew buildExtension`
- When debugging data types from PDBs, use headless analyze on samples to reproduce:  
  - Create/overwrite a test project dir (e.g., `samples/openssl-3.6.0/ghidra-project-libssl`).  
  - Run `support/analyzeHeadless.bat <projDir> <projName> -import <dll> -overwrite -scriptPath <repoRoot> -postScript <script>`; subsequent runs can use `-process <progName>` instead of re-import.  
  - Jython scripts can iterate `dtm.getAllDataTypes()` to find structs by name, inspect typedef bases, and test `addDataType(..., REPLACE_HANDLER)` to see whether replacements or duplicates occur.
- For `update_struct` issues, confirm the resolved struct’s category path (PDB imports often live under `/binary.pdb`) and ensure updates keep the same name/path; a mismatched category causes add-new behavior instead of replace.
- General headless iteration strategy:  
  - Write a small throwaway Jython script in the repo root that prints the findings you need (e.g., matching structs, typedef bases, `addDataType` results).  
  - Create a disposable project dir under `samples/` and run `analyzeHeadless` with `-import ... -overwrite` the first time, then `-process <progName>` for quicker re-runs while tweaking the script.  
  - Inspect console output for struct/category paths and whether `REPLACE_HANDLER` replaced or duplicated the target.  
  - Delete the temporary script and project dir when done to avoid noise in `git status`.
