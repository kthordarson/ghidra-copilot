package ghidracopilot.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.cmd.function.ApplyFunctionSignatureCmd;
import ghidra.app.cmd.function.FunctionRenameOption;
import ghidra.app.util.cparser.C.ParseException;
import ghidra.app.util.parser.FunctionSignatureParser;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.FunctionSignature;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Tool to retype or otherwise update a function signature.
 */
final class RetypeFunctionTool {

	private final CopilotToolContext context;

	RetypeFunctionTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "retype_function",
		description = "Retype a function by applying a C-style signature string. Updates return type, parameters, varargs, and optionally the name.")
	ToolResult retypeFunction(
		@ToolParam(description = "Hexadecimal entry point address of the function to update.", required = true)
		String addressText,
		@ToolParam(description = "C-style function signature (e.g., 'int foo(int a, char *b)').", required = true)
		String signatureText,
		@ToolParam(description = "If true, keep the existing calling convention instead of the one in the signature.")
		Boolean preserveCallingConvention,
		@ToolParam(description = "If true, always rename the function to the signature's name (default only renames if the current name is auto-generated).")
		Boolean forceRename) {
		return context.withCurrentProgram(
			program -> doRetype(program, addressText, signatureText, preserveCallingConvention, forceRename));
	}

	private ToolResult doRetype(Program program, String addressText, String signatureText,
			Boolean preserveCallingConvention, Boolean forceRename) {
		if (signatureText == null || signatureText.isBlank()) {
			return ToolResult.error("Signature text must not be blank.");
		}

		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionAt(address);
		if (function == null) {
			return ToolResult.error("No function exists at address " + context.formatAddress(address));
		}

		boolean preserveConvention = preserveCallingConvention != null && preserveCallingConvention;
		boolean renameAggressively = forceRename != null && forceRename;
		FunctionRenameOption renameOption =
			renameAggressively ? FunctionRenameOption.RENAME : FunctionRenameOption.RENAME_IF_DEFAULT;

		FunctionSignatureParser parser =
			new FunctionSignatureParser(program.getDataTypeManager(), context.dataTypeQueryService());
		FunctionSignature parsedSignature;
		try {
			parsedSignature = parser.parse(function.getSignature(), signatureText.trim());
		}
		catch (ParseException | CancelledException ex) {
			return ToolResult.error("Unable to parse signature: " + ex.getMessage());
		}

		SourceType source = context.userSourceType();
		ApplyFunctionSignatureCmd cmd = new ApplyFunctionSignatureCmd(
			address, parsedSignature, source, preserveConvention, false, DataTypeConflictHandler.DEFAULT_HANDLER,
			renameOption);

		int tx = program.startTransaction("Copilot: Apply function signature");
		boolean success = false;
		try {
			success = cmd.applyTo(program, TaskMonitor.DUMMY);
			if (!success) {
				return ToolResult
					.error("Failed to apply signature to function at " + context.formatAddress(address));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Error applying signature: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, success);
		}

		return ToolResult.success(
			"Retyped function at " + context.formatAddress(address) + " with signature '" + signatureText.trim() + "'.");
	}
}
