package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.model.pcode.HighFunction;
import ghidra.program.model.pcode.HighFunctionDBUtil;
import ghidra.program.model.pcode.HighSymbol;
import ghidra.program.model.pcode.LocalSymbolMap;
import ghidra.program.model.symbol.SymbolUtilities;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Tool to rename parameters or local stack variables within a function.
 *
 * <p>Variable names are resolved against the decompiler's {@link HighFunction} model (the same
 * "param_1"/"local_10" style names shown by {@code decompile_function}), since those decompiler-
 * generated names frequently do not exist as committed {@link ghidra.program.model.listing.Variable}
 * entries on the underlying {@link Function} until the decompiler's analysis is committed.</p>
 */
final class RenameVariableTool implements MutationTool {

	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;

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
		Scope requestedScope = normalizeScope(scope);

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
			HighFunction highFunction = results.getHighFunction();
			if (highFunction == null) {
				return ToolResult.error("Decompiler produced no high-level function for " + function.getName());
			}

			HighSymbol target = resolveHighSymbol(highFunction, currentName, requestedScope);
			if (target == null) {
				return ToolResult.error("Variable '" + currentName + "' not found.",
					summarizeVariables(highFunction));
			}

			String originalName = target.getName();
			boolean commit = false;
			int tx = program.startTransaction("Copilot Rename Variable");
			try {
				HighFunctionDBUtil.updateDBVariable(target, cleanedNewName, null, context.userSourceType());
				commit = true;
				return ToolResult.success(
					"Renamed " + (target.isParameter() ? "parameter" : "local") + " '" + originalName + "' to "
						+ cleanedNewName + " in function " + function.getName());
			}
			catch (InvalidInputException | DuplicateNameException ex) {
				return ToolResult.error("Invalid variable name: " + ex.getMessage());
			}
			catch (Exception ex) {
				return ToolResult.error("Rename failed: " + ex.getMessage());
			}
			finally {
				program.endTransaction(tx, commit);
			}
		}
		finally {
			decompiler.dispose();
		}
	}

	private HighSymbol resolveHighSymbol(HighFunction highFunction, String name, Scope scope) {
		String targetName = name.trim();
		LocalSymbolMap symbolMap = highFunction.getLocalSymbolMap();
		Iterator<HighSymbol> symbols = symbolMap.getSymbols();
		HighSymbol caseInsensitiveMatch = null;
		while (symbols.hasNext()) {
			HighSymbol symbol = symbols.next();
			if (scope == Scope.PARAMETER && !symbol.isParameter()) {
				continue;
			}
			if (scope == Scope.LOCAL && symbol.isParameter()) {
				continue;
			}
			String symbolName = symbol.getName();
			if (symbolName.equals(targetName)) {
				return symbol;
			}
			if (caseInsensitiveMatch == null && symbolName.equalsIgnoreCase(targetName)) {
				caseInsensitiveMatch = symbol;
			}
		}
		return caseInsensitiveMatch;
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

	private String summarizeVariables(HighFunction highFunction) {
		List<String> names = new ArrayList<>();
		Iterator<HighSymbol> symbols = highFunction.getLocalSymbolMap().getSymbols();
		while (symbols.hasNext()) {
			HighSymbol symbol = symbols.next();
			names.add((symbol.isParameter() ? "param: " : "local: ") + symbol.getName());
		}
		if (names.isEmpty()) {
			return "No parameters or locals found.";
		}
		return String.join("; ", names);
	}

	private enum Scope {
		PARAMETER, LOCAL, ALL
	}
}
