package ghidracopilot.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Tool for retrieving decompiler output for a given function.
 */
final class DecompileTool {

	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;

	private final CopilotToolContext context;

	DecompileTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "decompile_function",
		description = "Decompile the function at the provided address and return the C-like output.")
	ToolResult decompileFunction(
		@ToolParam(description = "Hexadecimal entry point address of the function", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doDecompile(program, addressText, false, false));
	}

	@Tool(name = "decompile_location",
		description = "Decompile the function containing the provided address and optionally include the signature.")
	ToolResult decompileLocation(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Include the function signature in the response (default true).")
		Boolean includeSignature) {
		boolean includeSig = includeSignature == null || includeSignature.booleanValue();
		return context.withCurrentProgram(program -> doDecompile(program, addressText, true, includeSig));
	}

	private ToolResult doDecompile(Program program, String addressText, boolean allowContaining, boolean includeSignature) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = allowContaining
			? functionManager.getFunctionContaining(address)
			: functionManager.getFunctionAt(address);
		if (function == null) {
			return ToolResult.error("No function found at or containing address " + address);
		}

		DecompInterface decompiler = new DecompInterface();
		try {
			if (!decompiler.openProgram(program)) {
				return ToolResult.error("Decompiler refused program: " + decompiler.getLastMessage());
			}
			DecompileResults results = decompiler.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS,
				TaskMonitorAdapter.DUMMY);
			if (!results.decompileCompleted()) {
				return ToolResult.error("Decompilation failed: " + results.getErrorMessage());
			}
			String c = results.getDecompiledFunction().getC();
			StringBuilder message = new StringBuilder("Decompiled function ")
				.append(function.getName());
			if (allowContaining && !function.getEntryPoint().equals(address)) {
				message.append(" (contains ").append(context.formatAddress(address)).append(")");
			}
			if (includeSignature) {
				String signature = null;
				try {
					signature = function.getSignature().toString();
				}
				catch (Exception ignored) {
					// Fall back to omitting the signature if unavailable.
				}
				if (signature != null && !signature.isBlank()) {
					message.append("\nSignature: ").append(signature);
				}
			}
			return ToolResult.success(message.toString(), c);
		}
		catch (Exception ex) {
			return ToolResult.error("Decompilation error: " + ex.getMessage());
		}
		finally {
			decompiler.dispose();
		}
	}
}
