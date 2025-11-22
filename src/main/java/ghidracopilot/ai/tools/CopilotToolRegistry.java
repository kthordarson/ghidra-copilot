package ghidracopilot.ai.tools;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import ghidracopilot.GhidraCopilotPlugin;
import ghidracopilot.ai.InteractionMode;

/**
 * Holds the set of tool objects that are exposed to the LLM.
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

	public static List<Object> toolsForMode(InteractionMode mode) {
		List<Object> tools = registeredTools.get();
		if (mode == null || !mode.isReadOnly()) {
			return tools;
		}
		return tools.stream()
				.filter(CopilotToolRegistry::isReadOnlyTool)
				.toList();
	}

	private static boolean isReadOnlyTool(Object tool) {
		return !(tool instanceof AnnotationTool)
			&& !(tool instanceof PatchTool)
			&& !(tool instanceof AnalysisTool)
			&& !(tool instanceof RenameFunctionTool)
			&& !(tool instanceof RetypeFunctionTool)
			&& !(tool instanceof RenameVariableTool)
			&& !(tool instanceof StructureTool)
			&& !(tool instanceof DecompiledCommentTool);
	}

	public static void clear() {
		registeredTools.set(List.of());
	}
}
