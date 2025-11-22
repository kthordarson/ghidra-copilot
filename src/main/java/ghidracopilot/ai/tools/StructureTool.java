package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.TypeDef;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.data.DataTypeParser;
import ghidra.util.data.DataTypeParser.AllowedDataTypes;
import ghidra.util.exception.CancelledException;

/**
 * Tools to define a struct and apply it to memory or stack variables.
 */
final class StructureTool {

	private final CopilotToolContext context;

	StructureTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "describe_struct",
		description = "Show the fields of an existing struct data type (offsets, types, and names).")
	ToolResult describeStruct(
		@ToolParam(description = "Struct name to inspect.", required = true)
		String structName,
		@ToolParam(description = "Optional category path where the struct lives.") String categoryPath) {
		return context.withCurrentProgram(program -> doDescribeStruct(program, structName, categoryPath));
	}

	@Tool(name = "find_struct",
		description = "Check if a struct exists (case-insensitive) and report its category path and size.")
	ToolResult findStruct(
		@ToolParam(description = "Struct name to find.", required = true)
		String structName,
		@ToolParam(description = "Optional category path to restrict the search.") String categoryPath) {
		return context.withCurrentProgram(program -> doFindStruct(program, structName, categoryPath));
	}

	@Tool(name = "list_structs",
		description = "List structs in the Datatypes tree, optionally restricted to a category and limited in count.")
	ToolResult listStructs(
		@ToolParam(description = "Optional category path prefix (e.g., '/MyTypes') to filter results.") String categoryPath,
		@ToolParam(description = "Maximum results to return (default 100).") Integer maxResults) {
		return context.withCurrentProgram(program -> doListStructs(program, categoryPath, maxResults));
	}

	@Tool(name = "define_struct",
		description = """
			Create or replace a struct from a C-style field list (one field per line or separated by semicolons).
			Do not include the 'struct' keyword in field names/identifiers (keep it in the type only if needed).
			Tips: replace unknown typedefs/macros with concrete primitive sizes (use uint64_t for pointers/size_t, void * as a placeholder for function/data pointers); for self-referencing pointers use a fixed-width integer (e.g., uint64_t on x64); avoid recursive types or references to undefined types; collapse bit-fields into a byte or multi-byte flag; after creation verify offsets/alignment in Ghidra and adjust.
			""")
	ToolResult defineStruct(
		@ToolParam(description = "Struct name to create.", required = true)
		String structName,
		@ToolParam(description = "Field list, e.g., 'int count; char *name; double value'.", required = true)
		String fieldsText,
		@ToolParam(description = "Optional category path such as '/MyTypes'. Defaults to root.") String categoryPath) {
		return context.withCurrentProgram(program -> doDefineStruct(program, structName, fieldsText, categoryPath));
	}

	@Tool(name = "update_struct",
		description = """
			Replace the fields of an existing struct with a new C-style field list.
			Do not include the 'struct' keyword in field names/identifiers (keep it in the type only if needed).
			Tips: normalize headers (no unknown typedefs/macros; use concrete primitives, uint64_t for pointers/size_t, void * placeholders for function/data pointers); represent self-referencing pointers with fixed-width integers (e.g., uint64_t on x64); avoid recursive/undefined type references; collapse bit-fields into flag bytes; verify offsets in the struct editor after updating.
			""")
	ToolResult updateStruct(
		@ToolParam(description = "Struct name to update.", required = true)
		String structName,
		@ToolParam(description = "New field list, e.g., 'int count; char *name; double value'.", required = true)
		String fieldsText,
		@ToolParam(description = "Optional category path where the struct lives.") String categoryPath) {
		return context.withCurrentProgram(program -> doUpdateStruct(program, structName, fieldsText, categoryPath));
	}

	@Tool(name = "apply_struct_to_address",
		description = "Apply an existing struct data type as a data item at the given address.")
	ToolResult applyStructToAddress(
		@ToolParam(description = "Hexadecimal address where the struct should be created.", required = true)
		String addressText,
		@ToolParam(description = "Struct name to apply.", required = true)
		String structName,
		@ToolParam(description = "Optional category path where the struct lives.") String categoryPath) {
		return context.withCurrentProgram(program -> doApplyStructToAddress(program, addressText, structName, categoryPath));
	}

	@Tool(name = "apply_struct_to_stack_variable",
		description = "Change a parameter or local variable's data type to the given struct.")
	ToolResult applyStructToStackVariable(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Variable name (parameter or local).", required = true)
		String variableName,
		@ToolParam(description = "Struct name to apply.", required = true)
		String structName,
		@ToolParam(description = "Optional category path where the struct lives.") String categoryPath,
		@ToolParam(description = "Restrict search to 'parameter' or 'local'. Defaults to both.") String scope) {
		return context.withCurrentProgram(program -> doApplyStructToVariable(
			program, addressText, variableName, structName, categoryPath, scope));
	}

	private ToolResult doDefineStruct(Program program, String structName, String fieldsText, String categoryPathText) {
		DataTypeManager dtm = program.getDataTypeManager();
		CategoryPath category = parseCategoryPath(categoryPathText);
		BuildResult build = buildStructure(program, structName, fieldsText, category);
		if (!build.ok()) {
			return ToolResult.error(build.error());
		}
		StructureDataType structure = build.struct();

		boolean commit = false;
		int tx = program.startTransaction("Copilot Define Struct");
		try {
			DataType added = dtm.addDataType(structure, DataTypeConflictHandler.REPLACE_HANDLER);
			commit = true;
			return ToolResult.success("Defined struct '" + added.getName() + "' in " + added.getCategoryPath());
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to define struct: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doUpdateStruct(Program program, String structName, String fieldsText, String categoryPathText) {
		Structure existing = resolveStructure(program, structName, categoryPathText);
		if (existing == null) {
			return ToolResult.error("Struct not found: " + structName);
		}

		CategoryPath targetCategory = parseCategoryPath(categoryPathText);
		if (CategoryPath.ROOT.equals(targetCategory)) {
			targetCategory = existing.getCategoryPath();
		}

		BuildResult build = buildStructure(program, structName, fieldsText, targetCategory);
		if (!build.ok()) {
			return ToolResult.error(build.error());
		}
		StructureDataType replacement = build.struct();

		DataTypeManager dtm = program.getDataTypeManager();

		boolean commit = false;
		int tx = program.startTransaction("Copilot Update Struct");
		try {
			DataType added = dtm.addDataType(replacement, DataTypeConflictHandler.REPLACE_HANDLER);
			commit = true;
			return ToolResult.success(
				"Updated struct '" + added.getName() + "' in " + added.getCategoryPath() + " with new fields.");
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to update struct: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doApplyStructToAddress(
			Program program, String addressText, String structName, String categoryPathText) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		Structure struct = resolveStructure(program, structName, categoryPathText);
		if (struct == null) {
			return ToolResult.error("Struct not found: " + structName);
		}
		if (struct.getLength() <= 0) {
			return ToolResult.error("Struct has zero length.");
		}

		Listing listing = program.getListing();
		boolean commit = false;
		int tx = program.startTransaction("Copilot Apply Struct");
		try {
			Address end = address.add(struct.getLength() - 1);
			listing.clearCodeUnits(address, end, false);
			listing.createData(address, struct);
			commit = true;
			return ToolResult.success(
				"Applied struct '" + struct.getName() + "' at " + context.formatAddress(address));
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to apply struct: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doApplyStructToVariable(Program program, String addressText, String variableName,
			String structName, String categoryPathText, String scope) {
		if (variableName == null || variableName.isBlank()) {
			return ToolResult.error("Variable name must not be blank.");
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

		Structure struct = resolveStructure(program, structName, categoryPathText);
		if (struct == null) {
			return ToolResult.error("Struct not found: " + structName);
		}

		Variable target = resolveVariable(function, variableName.trim(), normalizeScope(scope));
		if (target == null) {
			return ToolResult.error("Variable '" + variableName + "' not found.");
		}

		boolean commit = false;
		int tx = program.startTransaction("Copilot Apply Struct To Variable");
		try {
			target.setDataType(struct, SourceType.USER_DEFINED);
			commit = true;
			return ToolResult.success(
				"Set " + describeScope(target) + " '" + target.getName() + "' to struct '" + struct.getName()
					+ "' in function " + function.getName());
		}
		catch (Exception ex) {
			return ToolResult.error("Failed to set variable type: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private Structure resolveStructure(Program program, String structName, String categoryPathText) {
		if (structName == null || structName.isBlank()) {
			return null;
		}
		DataTypeManager dtm = program.getDataTypeManager();
		CategoryPath category = parseCategoryPath(categoryPathText);
		String trimmedName = structName.trim();

		DataType dt = dtm.getDataType(category, trimmedName);
		Structure struct = asStructure(dt);
		if (struct != null) {
			return struct;
		}

		dt = dtm.getDataType(trimmedName);
		struct = asStructure(dt);
		if (struct != null) {
			return struct;
		}

		for (var it = dtm.getAllDataTypes(); it.hasNext();) {
			DataType candidate = it.next();
			if (candidate == null) {
				continue;
			}
			if (trimmedName.equalsIgnoreCase(candidate.getName())) {
				struct = asStructure(candidate);
				if (struct != null) {
					return struct;
				}
			}
		}

		return null;
	}

	private Structure asStructure(DataType dt) {
		if (dt instanceof Structure structure) {
			return structure;
		}
		if (dt instanceof TypeDef typeDef) {
			DataType base = typeDef.getBaseDataType();
			if (base instanceof Structure structure) {
				return structure;
			}
		}
		return null;
	}

	private ToolResult doDescribeStruct(Program program, String structName, String categoryPathText) {
		Structure struct = resolveStructure(program, structName, categoryPathText);
		if (struct == null) {
			return ToolResult.error("Struct not found: " + structName);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Struct ").append(struct.getName()).append(" @ ").append(struct.getCategoryPath())
			.append(" (size ").append(struct.getLength()).append(" bytes)").append(System.lineSeparator());
		var components = struct.getDefinedComponents();
		if (components.length == 0) {
			sb.append("  <no fields>");
		}
		else {
			for (var comp : components) {
				sb.append(String.format(Locale.ROOT, "  [0x%X] %-3d %-30s %s",
					comp.getOffset(), comp.getLength(),
					comp.getDataType().getDisplayName(), comp.getFieldName()))
					.append(System.lineSeparator());
			}
		}

		return ToolResult.success(sb.toString().trim());
	}

	private ToolResult doFindStruct(Program program, String structName, String categoryPathText) {
		if (structName == null || structName.isBlank()) {
			return ToolResult.error("Struct name must not be blank.");
		}
		CategoryPath restrict = parseCategoryPath(categoryPathText);
		CategoryPath restriction = CategoryPath.ROOT.equals(restrict) && (categoryPathText == null || categoryPathText.isBlank())
			? null
			: restrict;

		List<Structure> matches = new ArrayList<>();
		DataTypeManager dtm = program.getDataTypeManager();
		for (var it = dtm.getAllDataTypes(); it.hasNext();) {
			DataType candidate = it.next();
			Structure struct = asStructure(candidate);
			if (struct == null) {
				continue;
			}
			if (!struct.getName().equalsIgnoreCase(structName.trim())) {
				continue;
			}
			if (restriction != null && !restriction.equals(struct.getCategoryPath())) {
				continue;
			}
			matches.add(struct);
		}

		if (matches.isEmpty()) {
			return ToolResult.error("Struct not found: " + structName);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Found ").append(matches.size()).append(" struct(s):").append(System.lineSeparator());
		for (Structure struct : matches) {
			sb.append("- ").append(struct.getName())
				.append(" @ ").append(struct.getCategoryPath())
				.append(" size ").append(struct.getLength())
				.append(" bytes");
			sb.append(System.lineSeparator());
		}
		return ToolResult.success(sb.toString().trim());
	}

	private ToolResult doListStructs(Program program, String categoryPathText, Integer maxResults) {
		int limit = context.normalizeLimit(maxResults, 100, 1000);
		CategoryPath restrict = parseCategoryPath(categoryPathText);
		String restrictPath = restrict.getPath();
		boolean hasRestriction = !(CategoryPath.ROOT.equals(restrict) && (categoryPathText == null || categoryPathText.isBlank()));

		List<String> rows = new ArrayList<>();
		DataTypeManager dtm = program.getDataTypeManager();
		for (var it = dtm.getAllDataTypes(); it.hasNext();) {
			DataType candidate = it.next();
			Structure struct = asStructure(candidate);
			if (struct == null) {
				continue;
			}
			String path = struct.getCategoryPath().getPath();
			if (hasRestriction && (path == null || !path.startsWith(restrictPath))) {
				continue;
			}
			rows.add(struct.getName() + " @ " + struct.getCategoryPath() + " size " + struct.getLength() + " bytes");
			if (rows.size() >= limit) {
				break;
			}
		}

		if (rows.isEmpty()) {
			return ToolResult.error("No structs found" + (hasRestriction ? " under " + restrictPath : "") + ".");
		}

		StringBuilder sb = new StringBuilder();
		sb.append("Structs").append(hasRestriction ? " under " + restrictPath : "").append(":")
			.append(System.lineSeparator());
		for (String row : rows) {
			sb.append("- ").append(row).append(System.lineSeparator());
		}
		if (rows.size() >= limit) {
			sb.append("(truncated at ").append(limit).append(" results)");
		}
		return ToolResult.success(sb.toString().trim());
	}

	private BuildResult buildStructure(Program program, String structName, String fieldsText, CategoryPath category) {
		if (structName == null || structName.isBlank() || fieldsText == null || fieldsText.isBlank()) {
			return BuildResult.error("Struct name and field list must not be blank.");
		}

		DataTypeManager dtm = program.getDataTypeManager();
		DataTypeParser parser =
			new DataTypeParser(dtm, dtm, context.dataTypeQueryService(), AllowedDataTypes.FIXED_LENGTH);

		StructureDataType structure = new StructureDataType(category, structName.trim(), 0, dtm);
		String[] parts = fieldsText.replace(';', '\n').split("\n");

		for (String raw : parts) {
			String entry = raw.trim();
			if (entry.isEmpty()) {
				continue;
			}
			int split = Math.max(entry.lastIndexOf(' '), entry.lastIndexOf('\t'));
			if (split <= 0 || split >= entry.length() - 1) {
				return BuildResult.error("Unable to parse field: '" + entry + "'. Use 'type name'.");
			}
			String typeText = entry.substring(0, split).trim();
			String fieldName = entry.substring(split + 1).trim();
			if (fieldName.isBlank()) {
				return BuildResult.error("Missing field name for entry: '" + entry + "'.");
			}
			try {
				DataType fieldType = parser.parse(typeText);
				structure.add(fieldType, fieldName, null);
			}
			catch (CancelledException ex) {
				return BuildResult.error("Struct creation cancelled while parsing field types.");
			}
			catch (InvalidDataTypeException ex) {
				return BuildResult.error("Invalid field type '" + typeText + "': " + ex.getMessage());
			}
			catch (IllegalArgumentException ex) {
				return BuildResult.error("Failed adding field '" + fieldName + "': " + ex.getMessage());
			}
		}
		return BuildResult.success(structure);
	}

	private Variable resolveVariable(Function function, String name, Scope scope) {
		String targetName = name.trim();
		if (scope == Scope.ALL || scope == Scope.PARAMETER) {
			for (Variable parameter : function.getParameters()) {
				if (equalsName(parameter.getName(), targetName)) {
					return parameter;
				}
			}
		}
		if (scope == Scope.ALL || scope == Scope.LOCAL) {
			for (Variable local : function.getLocalVariables()) {
				if (equalsName(local.getName(), targetName)) {
					return local;
				}
			}
		}
		return null;
	}

	private boolean equalsName(String candidate, String target) {
		if (candidate == null) {
			return false;
		}
		return candidate.equals(target) || candidate.equalsIgnoreCase(target);
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

	private CategoryPath parseCategoryPath(String categoryPathText) {
		if (categoryPathText == null || categoryPathText.isBlank()) {
			return CategoryPath.ROOT;
		}
		try {
			return new CategoryPath(categoryPathText.trim());
		}
		catch (Exception ignored) {
			return CategoryPath.ROOT;
		}
	}

	private String describeScope(Variable variable) {
		if (variable == null) {
			return "variable";
		}
		VariableStorage storage = variable.getVariableStorage();
		if (storage != null && storage.isStackStorage()) {
			return "stack variable";
		}
		return "variable";
	}

	private enum Scope {
		PARAMETER, LOCAL, ALL
	}

	private record BuildResult(StructureDataType struct, String error) {
		static BuildResult success(StructureDataType struct) {
			return new BuildResult(struct, null);
		}

		static BuildResult error(String message) {
			return new BuildResult(null, message);
		}

		boolean ok() {
			return struct != null && error == null;
		}
	}
}
