package ghidracopilot.ai.tools;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Extracts a short human-readable summary of why a tool was called,
 * following the Copilot CLI {@code summariseIntention} pattern.
 * <p>
 * Tool-specific extractors produce better summaries (e.g. "Rename FUN_00401000 → processInput")
 * while the generic fallback tries common field names.
 */
public final class IntentionSummariser {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Priority list mirroring the Copilot CLI: description, path, pattern, command, ... */
	private static final List<String> GENERIC_FIELDS = List.of(
		"intent", "description", "reason", "query", "question", "summary");

	private IntentionSummariser() {}

	/**
	 * Derive a short summary from tool name and raw JSON arguments.
	 * @return human-readable string, or {@code null} if nothing useful can be extracted
	 */
	public static String summarise(String toolName, String argsJson) {
		if (argsJson == null || argsJson.isBlank()) {
			return null;
		}
		try {
			JsonNode node = MAPPER.readTree(argsJson);
			String specific = toolSpecific(toolName, node);
			if (specific != null) {
				return specific;
			}
			return genericFallback(node);
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String toolSpecific(String toolName, JsonNode n) {
		if (toolName == null) return null;
		return switch (toolName) {
			// Decompilation
			case "decompile_function", "decompile_location" -> addr(n);
			case "list_decompiled_lines" -> addr(n);
			case "describe_decompiled_line", "comment_decompiled_line" -> {
				String a = addr(n);
				String line = text(n, "lineNumber");
				yield a != null && line != null ? a + " line " + line : a;
			}

			// Rename / retype
			case "rename_function" -> {
				String a = addr(n);
				String name = text(n, "newName");
				yield a != null && name != null ? a + " → " + name : a;
			}
			case "rename_stack_variable" -> {
				String cur = text(n, "currentName");
				String nw = text(n, "newName");
				yield cur != null && nw != null ? cur + " → " + nw : addr(n);
			}
			case "retype_function" -> {
				String sig = text(n, "signatureText");
				yield sig != null ? truncate(sig, 60) : addr(n);
			}

			// Navigation
			case "navigate_to_address", "set_listing_cursor" -> addr(n);

			// Annotation
			case "set_comment" -> {
				String a = addr(n);
				String type = text(n, "commentType");
				yield type != null && a != null ? type + " at " + a : a;
			}
			case "rename_label" -> {
				String a = addr(n);
				String name = text(n, "newName");
				yield a != null && name != null ? a + " → " + name : a;
			}
			case "flag_address" -> {
				String a = addr(n);
				String cat = text(n, "category");
				yield cat != null && a != null ? cat + " at " + a : a;
			}

			// References / call graph
			case "references_to_address", "references_from_address",
				"data_references_from_address" -> addr(n);
			case "references_to_symbol" -> text(n, "symbolName");
			case "describe_call_graph", "describe_basic_block" -> addr(n);

			// Data
			case "show_data_at_address", "read_bytes" -> addr(n);
			case "list_stack_variables" -> addr(n);
			case "list_disassembly" -> addr(n);
			case "patch_bytes" -> {
				String a = addr(n);
				String hex = text(n, "hexBytes");
				yield a != null && hex != null ? a + " ← " + truncate(hex, 20) : a;
			}
			case "fill_pattern" -> addr(n);

			// Structs
			case "describe_struct", "find_struct", "define_struct",
				"update_struct" -> text(n, "structName");
			case "apply_struct_to_address" -> {
				String a = addr(n);
				String s = text(n, "structName");
				yield s != null && a != null ? s + " at " + a : (s != null ? s : a);
			}
			case "apply_struct_to_stack_variable" -> {
				String v = text(n, "variableName");
				String s = text(n, "structName");
				yield v != null && s != null ? v + " → " + s : (s != null ? s : addr(n));
			}
			case "list_structs" -> text(n, "categoryPath");

			// Search tools
			case "list_strings", "list_functions", "list_imports",
				"list_exports", "list_labels", "list_namespaces",
				"list_classes" -> {
				String filter = text(n, "nameContains");
				if (filter == null) filter = text(n, "contains");
				yield filter != null ? "\"" + filter + "\"" : null;
			}

			// Analysis
			case "run_analysis" -> text(n, "analyzerNames");
			case "reanalyze_function" -> addr(n);

			// Metadata
			case "dump_program_metadata" -> null;

			// Report intent (handled separately, but just in case)
			case "report_intent" -> text(n, "intent");

			default -> null;
		};
	}

	private static String genericFallback(JsonNode node) {
		for (String key : GENERIC_FIELDS) {
			String val = text(node, key);
			if (val != null) return val;
		}
		// Final fallback: address field
		return addr(node);
	}

	private static String addr(JsonNode n) {
		// Tools use "addressText" (Java param name) in JSON
		String val = text(n, "addressText");
		if (val == null) val = text(n, "address");
		return val != null ? "at " + val : null;
	}

	private static String text(JsonNode n, String key) {
		if (n.has(key)) {
			JsonNode child = n.get(key);
			if (child.isTextual()) {
				String val = child.asText().trim();
				return val.isEmpty() ? null : val;
			}
			if (child.isNumber()) {
				return String.valueOf(child.asLong());
			}
		}
		return null;
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max - 3) + "..." : s;
	}
}
