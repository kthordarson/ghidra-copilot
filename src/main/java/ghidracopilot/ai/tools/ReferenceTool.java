package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolTable;

/**
 * Tools for exploring cross-references within the current program.
 */
final class ReferenceTool {

	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;

	private final CopilotToolContext context;

	ReferenceTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "references_to_address",
		description = "List references that target the provided address.")
	ToolResult referencesToAddress(
		@ToolParam(description = "Hexadecimal address that should be the reference destination.",
			required = true)
		String addressText,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doReferencesToAddress(program, addressText, maxResults));
	}

	@Tool(name = "references_from_address",
		description = "List references originating from the provided address.")
	ToolResult referencesFromAddress(
		@ToolParam(description = "Hexadecimal address that is the source of the references.",
			required = true)
		String addressText,
		@ToolParam(description = "Maximum number of rows to return (default 100, max 500).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doReferencesFromAddress(program, addressText, maxResults));
	}

	@Tool(name = "references_to_symbol",
		description = "List references that point to symbols matching the supplied name.")
	ToolResult referencesToSymbol(
		@ToolParam(description = "Exact or partial symbol name to search for.", required = true)
		String symbolName,
		@ToolParam(description = "Maximum number of rows to return per symbol (default 50, max 200).")
		Integer maxResults) {
		return context.withCurrentProgram(program -> doReferencesToSymbol(program, symbolName, maxResults));
	}

	private ToolResult doReferencesToAddress(Program program, String addressText, Integer maxResults) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}
		ReferenceManager referenceManager = program.getReferenceManager();
		Reference[] references = referenceManager.getReferencesTo(address);
		if (references == null || references.length == 0) {
			return ToolResult.success("No references found to " + context.formatAddress(address));
		}
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < references.length && rows.size() < limit; i++) {
			Reference ref = references[i];
			rows.add(formatReference(ref));
		}
		return ToolResult.success(
			"Found " + rows.size() + " references to " + context.formatAddress(address),
			String.join("\n", rows));
	}

	private ToolResult doReferencesFromAddress(Program program, String addressText, Integer maxResults) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}
		ReferenceManager referenceManager = program.getReferenceManager();
		Reference[] references = referenceManager.getReferencesFrom(address);
		if (references == null || references.length == 0) {
			return ToolResult.success("No references found from " + context.formatAddress(address));
		}
		int limit = context.normalizeLimit(maxResults, DEFAULT_LIMIT, MAX_LIMIT);
		List<String> rows = new ArrayList<>();
		for (int i = 0; i < references.length && rows.size() < limit; i++) {
			Reference ref = references[i];
			rows.add(formatReference(ref));
		}
		return ToolResult.success(
			"Found " + rows.size() + " references from " + context.formatAddress(address),
			String.join("\n", rows));
	}

	private ToolResult doReferencesToSymbol(Program program, String symbolName, Integer maxResults) {
		if (symbolName == null || symbolName.isBlank()) {
			return ToolResult.error("Symbol name must not be blank.");
		}
		SymbolTable symbolTable = program.getSymbolTable();
		Set<Symbol> matches = collectSymbolMatches(symbolTable, symbolName);
		if (matches.isEmpty()) {
			return ToolResult.success("No symbols found matching \"" + symbolName + "\".");
		}

		int limit = context.normalizeLimit(maxResults, 50, 200);
		List<String> rows = new ArrayList<>();
		for (Symbol symbol : matches) {
			rows.add("[Symbol] " + symbol.getName(true) + " @ " + context.formatAddress(symbol.getAddress()));
			Reference[] references = symbol.getReferences();
			if (references != null && references.length > 0) {
				int counter = 0;
				for (Reference ref : references) {
					if (counter++ >= limit) {
						rows.add("  ... additional references truncated ...");
						break;
					}
					rows.add("  " + formatReference(ref));
				}
			}
			else {
				rows.add("  No references.");
			}
		}
		return ToolResult.success("Enumerated references for " + matches.size() + " symbol(s).",
			String.join("\n", rows));
	}

	private Set<Symbol> collectSymbolMatches(SymbolTable symbolTable, String symbolName) {
		Set<Symbol> matches = new LinkedHashSet<>();
		SymbolIterator iterator = symbolTable.getSymbols(symbolName);
		if (iterator != null) {
			while (iterator.hasNext()) {
				matches.add(iterator.next());
			}
		}
		if (!matches.isEmpty()) {
			return matches;
		}

		// Fall back to substring search.
		String lowered = symbolName.toLowerCase(Locale.ROOT);
		SymbolIterator allSymbols = symbolTable.getAllSymbols(false);
		if (allSymbols != null) {
			while (allSymbols.hasNext()) {
				Symbol symbol = allSymbols.next();
				if (symbol.getName() != null && symbol.getName().toLowerCase(Locale.ROOT).contains(lowered)) {
					matches.add(symbol);
				}
				if (matches.size() >= 25) {
					break;
				}
			}
		}
		return matches;
	}

	private String formatReference(Reference reference) {
		StringBuilder builder = new StringBuilder();
		builder.append(context.formatAddress(reference.getFromAddress()))
			.append(" -> ")
			.append(context.formatAddress(reference.getToAddress()));
		String typeName = reference.getReferenceType() != null ? reference.getReferenceType().getName() : "unknown";
		builder.append(" (").append(typeName).append(")");
		return builder.toString();
	}
}
