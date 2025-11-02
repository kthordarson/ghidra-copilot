package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.StringData;
import ghidra.program.model.listing.StringIterator;

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

	private ToolResult doListStrings(Program program, String contains, Integer maxResults) {
		Listing listing = program.getListing();
		StringIterator iterator = listing.getDefinedStrings(true);
		if (iterator == null) {
			return ToolResult.error("String enumeration is not supported for this program.");
		}
		boolean hasFilter = contains != null && !contains.isBlank();
		String filter = hasFilter ? contains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<String> rows = new ArrayList<>();
		try {
			while (iterator.hasNext() && rows.size() < limit) {
				StringData data = iterator.next();
				String value = data.getString();
				if (value == null) {
					continue;
				}
				if (hasFilter && !value.toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				rows.add(context.formatAddress(data.getAddress()) + " : \"" + sanitize(value) + "\"");
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate strings: " + ex.getMessage());
		}

		if (rows.isEmpty()) {
			return ToolResult.success("No matching strings found.");
		}
		return ToolResult.success("Found " + rows.size() + " strings.", String.join("\n", rows));
	}

	private ToolResult doListFunctions(Program program, String nameContains, Integer maxResults) {
		FunctionIterator iterator = program.getFunctionManager().getFunctions(true);
		if (iterator == null) {
			return ToolResult.error("Function enumeration is not supported for this program.");
		}

		boolean hasFilter = nameContains != null && !nameContains.isBlank();
		String filter = hasFilter ? nameContains.toLowerCase(Locale.ROOT) : null;
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);

		List<String> rows = new ArrayList<>();
		try {
			while (iterator.hasNext() && rows.size() < limit) {
				Function function = iterator.next();
				String name = function.getName();
				if (hasFilter && (name == null || !name.toLowerCase(Locale.ROOT).contains(filter))) {
					continue;
				}
				StringBuilder row = new StringBuilder();
				row.append(context.formatAddress(function.getEntryPoint()))
					.append(" : ")
					.append(name != null ? name : "<unnamed>");
				if (function.isExternal()) {
					row.append(" [external]");
				}
				rows.add(row.toString());
			}
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to enumerate functions: " + ex.getMessage());
		}

		if (rows.isEmpty()) {
			return ToolResult.success("No matching functions found.");
		}
		return ToolResult.success("Found " + rows.size() + " functions.", String.join("\n", rows));
	}

	private String sanitize(String input) {
		return input.replace("\r", "\\r").replace("\n", "\\n");
	}
}
