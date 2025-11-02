package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;

/**
 * Tools related to data usage, including stack variables and data references.
 */
final class DataUsageTool {

	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;

	private final CopilotToolContext context;

	DataUsageTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "list_stack_variables",
		description = "List parameters and local variables for the function containing the supplied address.")
	ToolResult listStackVariables(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doListStackVariables(program, addressText));
	}

	@Tool(name = "data_references_from_address",
		description = "List data references that originate from the supplied address.")
	ToolResult dataReferencesFromAddress(
		@ToolParam(description = "Hexadecimal address that is the source of the data references.", required = true)
		String addressText,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doDataReferencesFromAddress(program, addressText, maxResults));
	}

	private ToolResult doListStackVariables(Program program, String addressText) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function found containing address " + context.formatAddress(address));
		}

		try {
			List<String> lines = new ArrayList<>();
			lines.add("Function: " + function.getName() + " @ " + context.formatAddress(function.getEntryPoint()));
			lines.add("Stack Frame Size: " + function.getStackFrame().getFrameSize() + " bytes");

			lines.add("Parameters:");
			Parameter[] parameters = function.getParameters();
			if (parameters == null || parameters.length == 0) {
				lines.add("  <none>");
			}
			else {
				for (Parameter parameter : parameters) {
					lines.add("  " + formatVariable(parameter));
				}
			}

			lines.add("Locals:");
			Variable[] locals = function.getLocalVariables();
			if (locals == null || locals.length == 0) {
				lines.add("  <none>");
			}
			else {
				Arrays.sort(locals, Comparator.comparingInt(this::stackOffsetSafe));
				for (Variable local : locals) {
					lines.add("  " + formatVariable(local));
				}
			}

			return ToolResult.success("Stack variable summary generated.", String.join("\n", lines));
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate variables: " + ex.getMessage());
		}
	}

	private ToolResult doDataReferencesFromAddress(Program program, String addressText, Integer maxResults) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		ReferenceManager referenceManager = program.getReferenceManager();
		Reference[] references = referenceManager.getReferencesFrom(address);
		if (references == null || references.length == 0) {
			return ToolResult.success("No data references found from " + context.formatAddress(address));
		}

		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);
		List<String> rows = new ArrayList<>();
		for (Reference reference : references) {
			if (reference.getReferenceType() == null || !reference.getReferenceType().isData()) {
				continue;
			}
			rows.add(context.formatAddress(reference.getFromAddress()) + " -> "
				+ context.formatAddress(reference.getToAddress()) + " (" + reference.getReferenceType() + ")");
			if (rows.size() >= limit) {
				rows.add("... additional references truncated ...");
				break;
			}
		}

		if (rows.isEmpty()) {
			return ToolResult.success("No data references found from " + context.formatAddress(address));
		}
		return ToolResult.success(
			"Found " + rows.size() + " data reference(s) from " + context.formatAddress(address),
			String.join("\n", rows));
	}

	private String formatVariable(Variable variable) {
		StringBuilder builder = new StringBuilder();
		builder.append(variable.getName())
			.append(" : ")
			.append(variable.getDataType().getDisplayName());
		try {
			VariableStorage storage = variable.getVariableStorage();
			if (storage != null) {
				builder.append(" @ ").append(storage.toString());
			}
		}
		catch (Exception ignored) {
			// Storage may not be resolvable for all variables.
		}
		return builder.toString();
	}

	private int stackOffsetSafe(Variable variable) {
		try {
			return variable.getStackOffset();
		}
		catch (Exception ex) {
			return Integer.MAX_VALUE;
		}
	}
}
