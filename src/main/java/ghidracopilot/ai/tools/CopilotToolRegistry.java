package ghidracopilot.ai.tools;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import ghidracopilot.GhidraCopilotPlugin;
import ghidracopilot.ai.PermissionManager;

/**
 * Holds the set of tool objects that are exposed to the LLM.
 */
public final class CopilotToolRegistry {

	private static final AtomicReference<List<Object>> registeredTools =
		new AtomicReference<>(List.of());

	private static volatile ReportIntentTool reportIntentTool;
	private static volatile PermissionManager permissionManager;

	private CopilotToolRegistry() {
		// static holder
	}

	public static void configureForPlugin(GhidraCopilotPlugin plugin) {
		if (plugin == null) {
			registeredTools.set(List.of());
			reportIntentTool = null;
			return;
		}
		CopilotToolContext context = new CopilotToolContext(plugin);
		reportIntentTool = new ReportIntentTool();
		registeredTools.set(List.of(
			reportIntentTool,
			new NavigationTool(context),
			new DisassemblyTool(context),
			new DecompileTool(context),
			new DecompiledCommentTool(context),
			new RenameFunctionTool(context),
			new RetypeFunctionTool(context),
			new RenameVariableTool(context),
			new StructureTool(context),
			new SymbolSearchTool(context),
			new ReferenceTool(context),
			new ControlFlowTool(context),
			new CallGraphTool(context),
			new AnnotationTool(context),
			new PatchTool(context),
			new DataUsageTool(context),
			new DataStringTool(context),
			new ProgramMetadataTool(context),
			new AnalysisTool(context)));
	}

	public static List<Object> tools() {
		return registeredTools.get();
	}

	/** Returns true if the given tool object modifies the program. */
	public static boolean isMutationTool(Object tool) {
		return tool instanceof MutationTool;
	}

	/** Read-only methods that live inside MutationTool classes. */
	private static final Set<String> READ_ONLY_OVERRIDES = Set.of(
		"read_bytes",
		"list_analyzers",
		"list_decompiled_lines",
		"describe_decompiled_line",
		"describe_struct",
		"find_struct",
		"list_structs"
	);

	/**
	 * Returns true if invoking the named tool method requires user permission.
	 * Read-only methods on MutationTool classes are excluded.
	 */
	public static boolean requiresPermission(Object toolObj, String methodName) {
		if (!(toolObj instanceof MutationTool)) return false;
		return !READ_ONLY_OVERRIDES.contains(methodName);
	}

	/**
	 * Name-only variant — resolves the tool name to its owning object, then checks.
	 */
	public static boolean requiresPermissionByName(String toolName) {
		if (READ_ONLY_OVERRIDES.contains(toolName)) return false;
		for (Object toolObj : registeredTools.get()) {
			for (java.lang.reflect.Method m : toolObj.getClass().getMethods()) {
				org.springframework.ai.tool.annotation.Tool ann =
					m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
				if (ann == null) continue;
				String name = ann.name().isEmpty() ? m.getName() : ann.name();
				if (name.equals(toolName)) {
					return toolObj instanceof MutationTool;
				}
			}
		}
		return false;
	}

	public static ReportIntentTool reportIntentTool() {
		return reportIntentTool;
	}

	public static void setPermissionManager(PermissionManager pm) {
		permissionManager = pm;
	}

	public static PermissionManager permissionManager() {
		return permissionManager;
	}

	public static void clear() {
		registeredTools.set(List.of());
		reportIntentTool = null;
	}
}
