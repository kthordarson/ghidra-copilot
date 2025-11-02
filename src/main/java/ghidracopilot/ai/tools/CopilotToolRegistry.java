package ghidracopilot.ai.tools;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ghidracopilot.GhidraCopilotPlugin;

/**
 * Holds the set of tool objects that are exposed to the LLM via Spring AI.
 */
public final class CopilotToolRegistry {

	private static final AtomicReference<List<Object>> registeredTools =
		new AtomicReference<>(List.of());

	private CopilotToolRegistry() {
		// static holder
	}

	public static void configureForPlugin(GhidraCopilotPlugin plugin) {
		if (plugin == null) {
			registeredTools.set(List.of());
			return;
		}
		CopilotToolContext context = new CopilotToolContext(plugin);
		registeredTools.set(List.of(
			new NavigationTool(context),
			new DecompileTool(context),
			new RenameFunctionTool(context),
			new SymbolSearchTool(context),
			new ReferenceTool(context),
			new ControlFlowTool(context),
			new CallGraphTool(context),
			new AnnotationTool(context),
			new PatchTool(context),
			new DataUsageTool(context),
			new ProgramMetadataTool(context)));
	}

	public static List<Object> tools() {
		return registeredTools.get();
	}

	public static void clear() {
		registeredTools.set(List.of());
	}
}
