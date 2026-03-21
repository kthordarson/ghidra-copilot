package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Program;
import ghidra.program.model.data.StringDataInstance;
import ghidra.program.util.DefinedStringIterator;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.symbol.SymbolType;
import ghidracopilot.ai.tools.results.ListItemsResult;
import ghidracopilot.ai.tools.results.ListItemsResult.Item;

/**
 * Tools for enumerating strings and functions within the current program.
 */
final class SymbolSearchTool {

	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;

	private final CopilotToolContext context;

	SymbolSearchTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "list_strings",
		description = "Enumerate defined strings in the current program, optionally filtered by substring.")
	ToolResult listStrings(
		@ToolParam(description = "Optional case-insensitive substring to match within each string.")
		String contains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListStrings(program, contains, maxResults));
	}

	@Tool(name = "list_functions",
		description = "Enumerate functions in the current program, optionally filtered by name.")
	ToolResult listFunctions(
		@ToolParam(description = "Optional case-insensitive substring to match within function names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListFunctions(program, nameContains, maxResults));
	}

	@Tool(name = "list_imports",
		description = "Enumerate imported symbols (external) in the current program, optionally filtered by name.")
	ToolResult listImports(
		@ToolParam(description = "Optional case-insensitive substring to match within import names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListImports(program, nameContains, maxResults));
	}

	@Tool(name = "list_exports",
		description = "Enumerate exported entry points in the current program, optionally filtered by name.")
	ToolResult listExports(
		@ToolParam(description = "Optional case-insensitive substring to match within export names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListExports(program, nameContains, maxResults));
	}

	@Tool(name = "list_labels",
		description = "Enumerate user/auto labels in the current program, optionally filtered by name.")
	ToolResult listLabels(
		@ToolParam(description = "Optional case-insensitive substring to match within label names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListSymbols(program, nameContains, maxResults, SymbolType.LABEL));
	}

	@Tool(name = "list_namespaces",
		description = "Enumerate namespaces in the current program, optionally filtered by name.")
	ToolResult listNamespaces(
		@ToolParam(description = "Optional case-insensitive substring to match within namespace names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListSymbols(program, nameContains, maxResults, SymbolType.NAMESPACE));
	}

	@Tool(name = "list_classes",
		description = "Enumerate class namespaces in the current program, optionally filtered by name.")
	ToolResult listClasses(
		@ToolParam(description = "Optional case-insensitive substring to match within class names.")
		String nameContains,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doListSymbols(program, nameContains, maxResults, SymbolType.CLASS));
	}

	private ToolResult doListStrings(Program program, String contains, Integer maxResults) {
		DefinedStringIterator iterator = DefinedStringIterator.forProgram(program);
		if (iterator == null) {
			return ToolResult.error("String enumeration is not supported for this program.");
		}
		boolean hasFilter = contains != null && !contains.isBlank();
		String filter = hasFilter ? contains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<Item> items = new ArrayList<>();
		try {
			while (iterator.hasNext() && items.size() < limit) {
				Data data = iterator.next();
				if (data == null || !StringDataInstance.isString(data)) {
					continue;
				}
				StringDataInstance instance = StringDataInstance.getStringDataInstance(data);
				if (instance == null) {
					continue;
				}
				String value = instance.getStringValue();
				if (value == null) {
					continue;
				}
				if (hasFilter && !value.toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				items.add(new Item("\"" + sanitize(value) + "\"",
					value,
					context.formatAddress(instance.getAddress())));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate strings: " + ex.getMessage());
		}

		if (items.isEmpty()) {
			ListItemsResult result = emptyListResult("string");
			return ToolResult.success("No matching strings found.", result, result.summary());
		}
		ListItemsResult result = new ListItemsResult("string", items.size(), false, items);
		return ToolResult.success("Found " + items.size() + " strings.", result, result.summary());
	}

	private ToolResult doListFunctions(Program program, String nameContains, Integer maxResults) {
		FunctionIterator iterator = program.getFunctionManager().getFunctions(true);
		if (iterator == null) {
			return ToolResult.error("Function enumeration is not supported for this program.");
		}

		boolean hasFilter = nameContains != null && !nameContains.isBlank();
		String filter = hasFilter ? nameContains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<Item> items = new ArrayList<>();
		try {
			while (iterator.hasNext() && items.size() < limit) {
				Function function = iterator.next();
				String name = function.getName();
				if (hasFilter && (name == null || !name.toLowerCase(Locale.ROOT).contains(filter))) {
					continue;
				}
				StringBuilder display = new StringBuilder(name != null ? name : "<unnamed>");
				if (function.isExternal()) {
					display.append(" [external]");
				}
				items.add(new Item(display.toString(),
					name != null ? name : "<unnamed>",
					context.formatAddress(function.getEntryPoint())));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate functions: " + ex.getMessage());
		}

		if (items.isEmpty()) {
			ListItemsResult result = emptyListResult("function");
			return ToolResult.success("No matching functions found.", result, result.summary());
		}
		ListItemsResult result = new ListItemsResult("function", items.size(), false, items);
		return ToolResult.success("Found " + items.size() + " functions.", result, result.summary());
	}

	private ToolResult doListImports(Program program, String nameContains, Integer maxResults) {
		SymbolTable symbolTable = program.getSymbolTable();
		SymbolIterator iterator = symbolTable.getExternalSymbols();
		if (iterator == null) {
			return ToolResult.error("Import enumeration is not supported for this program.");
		}

		boolean hasFilter = nameContains != null && !nameContains.isBlank();
		String filter = hasFilter ? nameContains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<Item> items = new ArrayList<>();
		try {
			while (iterator.hasNext() && items.size() < limit) {
				Symbol symbol = iterator.next();
				if (symbol == null || symbol.getName() == null) {
					continue;
				}
				String name = symbol.getName(true);
				if (hasFilter && !name.toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				items.add(new Item(name, name, null));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate imports: " + ex.getMessage());
		}

		if (items.isEmpty()) {
			ListItemsResult result = emptyListResult("import");
			return ToolResult.success("No matching imports found.", result, result.summary());
		}
		ListItemsResult result = new ListItemsResult("import", items.size(), false, items);
		return ToolResult.success("Found " + items.size() + " imports.", result, result.summary());
	}

	private ToolResult doListExports(Program program, String nameContains, Integer maxResults) {
		SymbolTable symbolTable = program.getSymbolTable();
		AddressIterator iterator = symbolTable.getExternalEntryPointIterator();
		if (iterator == null) {
			return ToolResult.error("Export enumeration is not supported for this program.");
		}

		boolean hasFilter = nameContains != null && !nameContains.isBlank();
		String filter = hasFilter ? nameContains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<Item> items = new ArrayList<>();
		try {
			while (iterator.hasNext() && items.size() < limit) {
				Address address = iterator.next();
				Symbol symbol = symbolTable.getPrimarySymbol(address);
				String name = symbol != null ? symbol.getName(true) : null;
				if (hasFilter && (name == null || !name.toLowerCase(Locale.ROOT).contains(filter))) {
					continue;
				}
				String displayName = name != null ? name : "<unnamed>";
				items.add(new Item(displayName, displayName, context.formatAddress(address)));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate exports: " + ex.getMessage());
		}

		if (items.isEmpty()) {
			ListItemsResult result = emptyListResult("export");
			return ToolResult.success("No matching exports found.", result, result.summary());
		}
		ListItemsResult result = new ListItemsResult("export", items.size(), false, items);
		return ToolResult.success("Found " + items.size() + " exports.", result, result.summary());
	}

	private ToolResult doListSymbols(Program program, String nameContains, Integer maxResults, SymbolType type) {
		SymbolTable symbolTable = program.getSymbolTable();
		SymbolIterator iterator = symbolTable.getAllSymbols(true);
		if (iterator == null) {
			return ToolResult.error("Symbol enumeration is not supported for this program.");
		}

		boolean hasFilter = nameContains != null && !nameContains.isBlank();
		String filter = hasFilter ? nameContains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<Item> items = new ArrayList<>();
		try {
			while (iterator.hasNext() && items.size() < limit) {
				Symbol symbol = iterator.next();
				if (symbol == null || symbol.getSymbolType() != type) {
					continue;
				}
				String name = symbol.getName(true);
				if (hasFilter && !name.toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				if (type == SymbolType.LABEL && symbol.isExternal()) {
					continue;
				}
				items.add(new Item(symbol.getName(true),
					symbol.getName(true),
					symbol.getAddress() != null ? context.formatAddress(symbol.getAddress()) : null));
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate symbols: " + ex.getMessage());
		}

		String typeLabel = typeLabel(type);
		if (items.isEmpty()) {
			ListItemsResult result = emptyListResult(typeLabel);
			return ToolResult.success("No matching " + typeLabel + "s found.", result, result.summary());
		}
		ListItemsResult result = new ListItemsResult(typeLabel, items.size(), false, items);
		return ToolResult.success(
			"Found " + items.size() + " " + typeLabel + "s.",
			result,
			result.summary());
	}

	private static ListItemsResult emptyListResult(String kind) {
		return new ListItemsResult(kind, 0, false, List.of());
	}

	private String typeLabel(SymbolType type) {
		if (type == null) {
			return "symbol";
		}
		if (type == SymbolType.LABEL) {
			return "label";
		}
		if (type == SymbolType.NAMESPACE) {
			return "namespace";
		}
		if (type == SymbolType.CLASS) {
			return "class";
		}
		return type.toString().toLowerCase(Locale.ROOT);
	}

	private String sanitize(String input) {
		return input.replace("\r", "\\r").replace("\n", "\\n");
	}
}
