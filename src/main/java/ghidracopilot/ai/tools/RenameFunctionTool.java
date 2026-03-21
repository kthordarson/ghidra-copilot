package ghidracopilot.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.InvalidInputException;

/**
 * Tool to rename a function at a specific address.
 */
final class RenameFunctionTool implements MutationTool {

	private final CopilotToolContext context;

	RenameFunctionTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "rename_function",
		description = "Rename the function at the given address to a new user-defined name.")
	ToolResult renameFunction(
		@ToolParam(description = "Hexadecimal entry point address of the function", required = true)
		String addressText,
		@ToolParam(description = "New name to assign to the function", required = true)
		String newName) {
		return context.withCurrentProgram(program -> doRename(program, addressText, newName));
	}

	private ToolResult doRename(Program program, String addressText, String newName) {
		if (newName == null || newName.isBlank()) {
			return ToolResult.error("New name must not be blank.");
		}
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionAt(address);
		if (function == null) {
			return ToolResult.error("No function exists at address " + address);
		}

		boolean commit = false;
		int tx = program.startTransaction("Copilot Rename Function");
		try {
			SourceType source = context.userSourceType();
			function.setName(newName.trim(), source);
			commit = true;
			return ToolResult.success("Renamed function to " + newName.trim());
		}
		catch (InvalidInputException ex) {
			return ToolResult.error("Invalid function name: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Rename failed: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}
}
