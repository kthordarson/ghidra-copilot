package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.exception.InvalidInputException;

/**
 * Tool to rename parameters or local stack variables within a function.
 */
final class RenameVariableTool {

	private final CopilotToolContext context;

	RenameVariableTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "rename_stack_variable",
		description = "Rename a parameter or local variable in the function containing the supplied address.")
	ToolResult renameStackVariable(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Current variable name (e.g., param_1, local_4).", required = true)
		String currentName,
		@ToolParam(description = "New variable name to apply.", required = true)
		String newName,
		@ToolParam(description = "Restrict search to 'parameter' or 'local'. Defaults to searching both.")
		String scope) {
		return context.withCurrentProgram(program -> doRename(program, addressText, currentName, newName, scope));
	}

	private ToolResult doRename(Program program, String addressText, String currentName, String newName, String scope) {
		if (currentName == null || currentName.isBlank()) {
			return ToolResult.error("Current variable name must not be blank.");
		}
		if (newName == null || newName.isBlank()) {
			return ToolResult.error("New variable name must not be blank.");
		}

		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function found containing address " + context.formatAddress(address));
		}

		String cleanedNewName = SymbolUtilities.replaceInvalidChars(newName.trim(), true);
		Variable target = resolveVariable(function, currentName, normalizeScope(scope));
		if (target == null) {
			return ToolResult.error("Variable '" + currentName + "' not found.", summarizeVariables(function));
		}

		String originalName = target.getName();
		boolean commit = false;
		int tx = program.startTransaction("Copilot Rename Variable");
		try {
			target.setName(cleanedNewName, context.userSourceType());
			commit = true;
			return ToolResult.success(
				"Renamed " + describeScope(target) + " '" + originalName + "' to " + cleanedNewName + " in function "
					+ function.getName());
		}
		catch (InvalidInputException ex) {
			return ToolResult.error("Invalid variable name: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Rename failed: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private Variable resolveVariable(Function function, String name, Scope scope) {
		String targetName = name.trim();
		if (scope == Scope.ALL || scope == Scope.PARAMETER) {
			Parameter[] parameters = function.getParameters();
			for (Parameter parameter : parameters) {
				if (parameter.getName().equals(targetName) || parameter.getName().equalsIgnoreCase(targetName)) {
					return parameter;
				}
			}
		}
		if (scope == Scope.ALL || scope == Scope.LOCAL) {
			Variable[] locals = function.getLocalVariables();
			for (Variable local : locals) {
				if (local.getName().equals(targetName) || local.getName().equalsIgnoreCase(targetName)) {
					return local;
				}
			}
		}
		return null;
	}

	private Scope normalizeScope(String scope) {
		if (scope == null || scope.isBlank()) {
			return Scope.ALL;
		}
		String normalized = scope.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "param", "parameter", "parameters" -> Scope.PARAMETER;
			case "local", "locals", "stack" -> Scope.LOCAL;
			default -> Scope.ALL;
		};
	}

	private String summarizeVariables(Function function) {
		List<String> names = new ArrayList<>();
		Parameter[] parameters = function.getParameters();
		if (parameters != null && parameters.length > 0) {
			Arrays.stream(parameters).forEach(parameter -> names.add("param: " + parameter.getName()));
		}
		Variable[] locals = function.getLocalVariables();
		if (locals != null && locals.length > 0) {
			Arrays.stream(locals).forEach(local -> {
				StringBuilder builder = new StringBuilder("local: ").append(local.getName());
				try {
					VariableStorage storage = local.getVariableStorage();
					if (storage != null) {
						builder.append(" @ ").append(storage);
					}
				}
				catch (Exception ignored) {
					// storage resolution may fail; ignore.
				}
				names.add(builder.toString());
			});
		}
		if (names.isEmpty()) {
			return "No parameters or locals found.";
		}
		return String.join("; ", names);
	}

	private String describeScope(Variable variable) {
		return variable instanceof Parameter ? "parameter" : "local";
	}

	private enum Scope {
		PARAMETER, LOCAL, ALL
	}
}
