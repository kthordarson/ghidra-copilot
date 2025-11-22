package ghidracopilot.ui.context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Method;

import docking.ComponentProvider;
import ghidracopilot.ai.InteractionMode;
import ghidra.app.decompiler.ClangLine;
import ghidra.app.decompiler.ClangToken;
import ghidra.app.decompiler.ClangTokenGroup;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.CodeViewerService;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;
import ghidra.util.Msg;
import ghidra.util.task.TaskMonitorAdapter;
import org.springframework.util.StringUtils;

/**
 * Builds a dynamic system context string that includes program state, active view,
 * and slices of disassembly and decompiler output with the current line indicated.
 */
public final class ContextSnapshotBuilder {

	private static final int MAX_DISASSEMBLY_LINES = 256;
	private static final int MAX_DECOMPILED_LINES = 256;
	private static final int DECOMPILE_TIMEOUT_SECONDS = 30;
	private static final int MAX_SELECTION_LINES = 128;

	private static final AtomicReference<ViewInfo> lastNonChatView = new AtomicReference<>();

	private ContextSnapshotBuilder() {
		// static helper
	}

	public static String build(ProgramPlugin programPlugin, InteractionMode mode) {
		if (programPlugin == null) {
			return buildModeGuidance(mode);
		}
		Program program = programPlugin.getCurrentProgram();
		if (program == null) {
			String guidance = buildModeGuidance(mode);
			return guidance != null
					? "Current context: no program is active.\n\n" + guidance
					: "Current context: no program is active.";
		}

		StringBuilder builder = new StringBuilder("Current Ghidra context:\n");
		builder.append("- Program: ").append(program.getName());
		String executablePath = program.getExecutablePath();
		if (StringUtils.hasText(executablePath)) {
			builder.append(" (").append(executablePath).append(")");
		}
		builder.append('\n');

		var tool = programPlugin.getTool();
		ComponentProvider activeProvider = tool != null ? tool.getActiveComponentProvider() : null;
		DecompilerLocationInfo decompLocation = resolveDecompilerLocation(activeProvider, tool);
		if (activeProvider != null) {
			ViewInfo viewInfo = ViewInfo.from(activeProvider);
			if (!viewInfo.isChat()) {
				lastNonChatView.set(viewInfo);
			}
			builder.append("- Active view: ").append(viewInfo.displayName());
			if (viewInfo.isChat()) {
				ViewInfo fallback = lastNonChatView.get();
				if (fallback != null) {
					builder.append(" (chat focused; last code view was ").append(fallback.displayName()).append(')');
				}
				else {
					builder.append(" (chat focused)");
				}
			}
			builder.append('\n');
		}

		CodeViewerService codeViewer = tool != null ? tool.getService(CodeViewerService.class) : null;
		ProgramLocation location = codeViewer != null ? codeViewer.getCurrentLocation() : null;
		ProgramSelection selection = codeViewer != null ? codeViewer.getCurrentSelection() : null;
				Address address = location != null ? location.getAddress() : null;
				if (address != null) {
					builder.append("- Address: ").append(address).append('\n');
					FunctionManager functionManager = program.getFunctionManager();
					Function function = functionManager != null ? functionManager.getFunctionContaining(address) : null;
			if (function != null) {
				builder.append("- Function: ").append(function.getName())
					.append(" @ ").append(function.getEntryPoint()).append('\n');
				String disassembly = buildDisassemblySnippet(program, function, address);
				if (!disassembly.isBlank()) {
					builder.append("\nDisassembly (current function, current line marked with '>'):\n");
					builder.append(disassembly).append('\n');
				}
					Integer caretLine = decompLocation != null ? decompLocation.lineNumber() : null;
					Snippet decompilation =
						buildDecompiledSnippet(program, function, address, selection, caretLine, decompLocation);
				if (decompilation != null && StringUtils.hasText(decompilation.text())) {
					if (decompilation.highlightLine() > 0) {
						builder.append("- Decompiler cursor line: ").append(decompilation.highlightLine()).append('\n');
					}
					if (decompLocation != null && StringUtils.hasText(decompLocation.tokenText())) {
						builder.append("- Decompiler token: \"").append(decompLocation.tokenText()).append('"');
						if (decompLocation.columnStart() != null && decompLocation.columnEnd() != null) {
							builder.append(" (cols ").append(decompLocation.columnStart()).append('-')
								.append(decompLocation.columnEnd()).append(')');
						}
						builder.append('\n');
					}
					builder.append("\nDecompiler (current function, windowed to ~")
						.append(MAX_DECOMPILED_LINES)
						.append(" lines; current line marked with '>'):\n");
					builder.append(decompilation.text()).append('\n');
				}
			}
		}
		else if (location != null) {
			builder.append("- Location: ").append(location).append('\n');
		}

		if (selection != null && !selection.isEmpty()) {
			builder.append("- Selection size: ")
				.append(selection.getNumAddresses())
				.append(" addresses\n");
			String selectionSnippet = buildSelectionSnippet(program, selection, address);
			if (!selectionSnippet.isBlank()) {
				builder.append("\nSelection preview (current instruction marked '>', selected '*'):\n");
				builder.append(selectionSnippet).append('\n');
			}
		}

		String guidance = buildModeGuidance(mode);
		if (guidance != null) {
			builder.append('\n').append('\n').append(guidance);
		}

		return builder.toString().trim();
	}

	private static String buildDisassemblySnippet(Program program, Function function, Address currentAddress) {
		if (program == null || function == null) {
			return "";
		}
		StringBuilder snippet = new StringBuilder();
		var listing = program.getListing();
		var instructions = listing.getInstructions(function.getBody(), true);
		int count = 0;
		boolean truncated = false;

		while (instructions.hasNext()) {
			Instruction instruction = instructions.next();
			String prefix = instruction.getMinAddress().equals(currentAddress) ? "> " : "  ";
			snippet.append(prefix)
				.append(instruction.getMinAddress())
				.append(": ")
				.append(instruction)
				.append(System.lineSeparator());
			count++;
			if (count >= MAX_DISASSEMBLY_LINES) {
				if (instructions.hasNext()) {
					truncated = true;
				}
				break;
			}
		}

		if (truncated) {
			snippet.append("  ... (truncated after ").append(MAX_DISASSEMBLY_LINES).append(" instructions)")
				.append(System.lineSeparator());
		}

		return snippet.toString().stripTrailing();
	}

	private static Snippet buildDecompiledSnippet(Program program, Function function, Address currentAddress,
			ProgramSelection selection, Integer caretLineOverride, DecompilerLocationInfo locationInfo) {
		DecompInterface iface = new DecompInterface();
		try {
			if (!iface.openProgram(program)) {
				Msg.warn(ContextSnapshotBuilder.class, "Decompiler refused program: " + iface.getLastMessage());
				return null;
			}
			DecompileResults results = iface.decompileFunction(function, DECOMPILE_TIMEOUT_SECONDS,
				TaskMonitorAdapter.DUMMY_MONITOR);
			if (results == null || !results.decompileCompleted()) {
				return null;
			}
			String c = results.getDecompiledFunction() != null
				? results.getDecompiledFunction().getC()
				: null;
			List<String> lines = splitLines(c);
			if (lines.isEmpty()) {
				return null;
			}

			Map<Integer, Set<Address>> addressesByLine = mapAddresses(results.getCCodeMarkup());
			Set<Integer> selectedLines = findSelectedLines(addressesByLine, selection);
			int highlightLine = caretLineOverride != null && caretLineOverride.intValue() > 0
				? caretLineOverride.intValue()
				: findLineForAddress(addressesByLine, currentAddress);

			int totalLines = lines.size();
			int limit = Math.max(1, MAX_DECOMPILED_LINES);
			int start = 1;
			int end = totalLines;
			if (totalLines > limit) {
				if (highlightLine > 0) {
					start = Math.max(1, highlightLine - limit / 2);
					end = Math.min(totalLines, start + limit - 1);
					if (end - start + 1 < limit) {
						start = Math.max(1, end - limit + 1);
					}
				}
				else {
					end = limit;
				}
			}

			StringBuilder sb = new StringBuilder();
			for (int i = start; i <= end; i++) {
				boolean isHighlight = i == highlightLine;
				boolean isSelected = selectedLines.contains(i);
				String prefix = isHighlight ? "> " : (isSelected ? "* " : "  ");
				String rawText = lines.get(i - 1);
				String text = rawText;
				if (isHighlight && locationInfo != null && locationInfo.lineNumber() != null
					&& locationInfo.lineNumber().intValue() == i
					&& locationInfo.columnStart() != null && locationInfo.columnEnd() != null) {
					int selStart = Math.max(0, locationInfo.columnStart().intValue());
					int selEnd = Math.max(selStart, locationInfo.columnEnd().intValue());
					int len = rawText.length();
					selStart = Math.min(selStart, len);
					selEnd = Math.min(selEnd, len);
					if (selEnd > selStart) {
						text = rawText.substring(0, selStart)
							+ "[["
							+ rawText.substring(selStart, selEnd)
							+ "]]"
							+ rawText.substring(selEnd);
					}
				}
				sb.append(prefix)
					.append(String.format(Locale.ROOT, "%4d | ", i))
					.append(text)
					.append(System.lineSeparator());
			}
			if (start > 1 || end < totalLines) {
				sb.append("  ... (windowed lines ").append(start).append('-').append(end)
					.append(" of ").append(totalLines).append(")").append(System.lineSeparator());
			}
			return new Snippet(sb.toString().stripTrailing(), highlightLine);
		}
		catch (Exception ex) {
			Msg.warn(ContextSnapshotBuilder.class, "Decompiler error: " + ex.getMessage(), ex);
			return null;
		}
		finally {
			iface.dispose();
		}
	}

	private static Map<Integer, Set<Address>> mapAddresses(ClangTokenGroup markup) {
		Map<Integer, Set<Address>> addresses = new HashMap<>();
		if (markup == null) {
			return addresses;
		}
		for (var it = markup.tokenIterator(true); it.hasNext();) {
			ClangToken token = it.next();
			ClangLine line = token.getLineParent();
			if (line == null) {
				continue;
			}
			int lineNumber = line.getLineNumber();
			if (lineNumber < 0) {
				continue;
			}
			addAddress(addresses, lineNumber, token.getMinAddress());
			addAddress(addresses, lineNumber, token.getMaxAddress());
		}
		return addresses;
	}

	private static void addAddress(Map<Integer, Set<Address>> map, int lineNumber, Address address) {
		if (!isValidAddress(address)) {
			return;
		}
		map.computeIfAbsent(lineNumber, key -> new HashSet<>()).add(address);
	}

	private static int findLineForAddress(Map<Integer, Set<Address>> addressesByLine, Address target) {
		if (!isValidAddress(target)) {
			return -1;
		}
		return addressesByLine.entrySet().stream()
			.filter(entry -> entry.getValue().contains(target))
			.map(Map.Entry::getKey)
			.findFirst()
			.orElse(-1);
	}

	private static Set<Integer> findSelectedLines(Map<Integer, Set<Address>> addressesByLine, ProgramSelection selection) {
		Set<Integer> selected = new HashSet<>();
		if (selection == null || selection.isEmpty()) {
			return selected;
		}
		for (Map.Entry<Integer, Set<Address>> entry : addressesByLine.entrySet()) {
			for (Address addr : entry.getValue()) {
				if (selection.contains(addr)) {
					selected.add(entry.getKey());
					break;
				}
			}
		}
		return selected;
	}

	private static boolean isValidAddress(Address address) {
		return address != null && !Address.NO_ADDRESS.equals(address);
	}

	private static List<String> splitLines(String c) {
		if (c == null || c.isBlank()) {
			return List.of();
		}
		String[] parts = c.split("\\R", -1);
		List<String> lines = new ArrayList<>(parts.length);
		java.util.Collections.addAll(lines, parts);
		while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
			lines.remove(lines.size() - 1);
		}
		return lines;
	}

	private static String buildModeGuidance(InteractionMode mode) {
		if (mode == null) {
			return null;
		}
		return switch (mode) {
			case ASK -> """
				Interaction mode: Ask (read-only).
				Do not request or perform any actions that modify the project. Avoid renaming, patching, annotating, or otherwise changing program data. Use navigation, decompilation, and analysis only.
				""".trim();
			case AGENT -> """
				Interaction mode: Agent (full autonomy).
				Take initiative to improve clarity: rename functions/variables, apply annotations, and use available tools without asking for confirmation. Prefer focusing on the current function and the functions it directly calls or is called by; read additional functions only when needed for understanding.
				""".trim();
		};
	}

	private record Snippet(String text, int highlightLine) {
	}

	private record DecompilerLocationInfo(Integer lineNumber, String tokenText, Integer columnStart, Integer columnEnd) {
	}

	private static DecompilerLocationInfo resolveDecompilerLocation(ComponentProvider activeProvider,
			ghidra.framework.plugintool.PluginTool tool) {
		ComponentProvider target = activeProvider;
		if (target == null) {
			return null;
		}
		try {
			Object panel = invoke(target, "getDecompilerPanel");
			if (panel == null) {
				return null;
			}
			Object location = invoke(panel, "getCurrentLocation");
			if (location == null) {
				return null;
			}
			Integer line = extractInt(location, "getDecompiledLineNumber");
			if (line == null) {
				line = extractInt(location, "getLineNumber");
			}
			String token = extractString(location, "getTokenText");
			Integer colStart = extractInt(location, "getCharIndex");
			Integer colEnd = extractInt(location, "getCharIndexEnd");
			return new DecompilerLocationInfo(line, token, colStart, colEnd);
		}
		catch (Exception ex) {
			Msg.debug(ContextSnapshotBuilder.class, "Unable to resolve decompiler location", ex);
			return null;
		}
	}

	private static Object invoke(Object target, String method) throws Exception {
		Method m = findZeroArgMethod(target.getClass(), method);
		if (m == null) {
			return null;
		}
		return m.invoke(target);
	}

	private static Method findZeroArgMethod(Class<?> type, String name) {
		for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
			try {
				Method method = cursor.getDeclaredMethod(name);
				method.setAccessible(true);
				return method;
			}
			catch (NoSuchMethodException ignored) {
				// ignore and continue search
			}
		}
		return null;
	}

	private static Integer extractInt(Object target, String method) {
		try {
			Object value = invoke(target, method);
			if (value instanceof Number number) {
				return number.intValue();
			}
		}
		catch (Exception ignored) {
			// ignore and fallback
		}
		return null;
	}

	private static String extractString(Object target, String method) {
		try {
			Object value = invoke(target, method);
			if (value instanceof String s) {
				return s;
			}
		}
		catch (Exception ignored) {
			// ignore and fallback
		}
		return null;
	}

	private static String buildSelectionSnippet(Program program, ProgramSelection selection, Address currentAddress) {
		if (program == null || selection == null || selection.isEmpty()) {
			return "";
		}
		var listing = program.getListing();
		var instructions = listing.getInstructions(selection, true);
		int count = 0;
		boolean truncated = false;
		StringBuilder sb = new StringBuilder();
		while (instructions.hasNext()) {
			Instruction instruction = instructions.next();
			String prefix = instruction.getMinAddress().equals(currentAddress) ? ">" : "*";
			sb.append(prefix).append(' ')
				.append(instruction.getMinAddress()).append(": ")
				.append(instruction)
				.append(System.lineSeparator());
			count++;
			if (count >= MAX_SELECTION_LINES) {
				if (instructions.hasNext()) {
					truncated = true;
				}
				break;
			}
		}
		if (truncated) {
			sb.append("  ... (selection preview truncated after ").append(MAX_SELECTION_LINES).append(" lines)")
				.append(System.lineSeparator());
		}
		return sb.toString().stripTrailing();
	}

	private record ViewInfo(String name, String className) {

		static ViewInfo from(ComponentProvider provider) {
			if (provider == null) {
				return new ViewInfo("unknown", "");
			}
			return new ViewInfo(provider.getName(), provider.getClass().getSimpleName());
		}

		boolean isChat() {
			return "Ghidra Copilot".equalsIgnoreCase(name)
				|| className.toLowerCase(Locale.ROOT).contains("copilotprovider");
		}

		String displayName() {
			if (!StringUtils.hasText(className)) {
				return name;
			}
			return name + " [" + className + "]";
		}
	}
}
