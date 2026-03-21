package ghidracopilot.ai.tools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.app.services.Analyzer;
import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;

/**
 * Tools to kick off Ghidra analysis for the entire program or a specific function.
 */
final class AnalysisTool implements MutationTool {

	private final CopilotToolContext context;

	AnalysisTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "run_analysis",
		description = "Trigger auto-analysis. If analyzers are supplied, only those analyzers run over the provided address range. Call 'list_analyzers' first if you need valid names.")
	ToolResult runAnalysis(
		@ToolParam(description = "Optional comma/semicolon/newline separated analyzer names. If omitted, full analysis is triggered.")
		String analyzerNames,
		@ToolParam(description = "Optional start address for limiting analysis scope.")
		String startAddressText,
		@ToolParam(description = "Optional end address for limiting analysis scope.")
		String endAddressText) {
		return context.withCurrentProgram(
			program -> doRunAnalysis(program, analyzerNames, startAddressText, endAddressText));
	}

	@Tool(name = "reanalyze_function",
		description = "Re-run analysis for the function containing the supplied address. If analyzers are supplied, only those analyzers are scheduled for that function.")
	ToolResult reanalyzeFunction(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Optional comma/semicolon/newline separated analyzer names to run just for this function.")
		String analyzerNames) {
		return context.withCurrentProgram(program -> doReanalyzeFunction(program, addressText, analyzerNames));
	}

	@Tool(name = "list_analyzers",
		description = "List available analyzers by name. Useful before requesting targeted analysis.")
	ToolResult listAnalyzers() {
		return context.withCurrentProgram(this::doListAnalyzers);
	}

	private ToolResult doRunAnalysis(Program program, String analyzerNames, String startAddressText,
			String endAddressText) {
		try {
			AutoAnalysisManager manager = AutoAnalysisManager.getAnalysisManager(program);
			AddressSet targetSet = resolveAddressSet(program, startAddressText, endAddressText);
			if (targetSet == null) {
				return ToolResult.error("Unable to resolve address range for analysis.");
			}

			List<String> names = parseAnalyzerNames(analyzerNames);
			if (names.isEmpty()) {
				manager.reAnalyzeAll(targetSet);
				manager.startAnalysis(TaskMonitor.DUMMY);
				return ToolResult.success("Analysis started for program range " + describeRange(targetSet));
			}

			List<Analyzer> analyzers = resolveAnalyzers(manager, names);
			if (analyzers.isEmpty()) {
				return ToolResult.error("No analyzers matched the supplied names: " + String.join(", ", names));
			}
			for (Analyzer analyzer : analyzers) {
				manager.scheduleOneTimeAnalysis(analyzer, targetSet);
			}
			manager.startAnalysis(TaskMonitor.DUMMY);
			return ToolResult.success(
				"Scheduled analyzers [" + joinAnalyzerNames(analyzers) + "] for range " + describeRange(targetSet));
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to start analysis: " + ex.getMessage());
		}
	}

	private ToolResult doReanalyzeFunction(Program program, String addressText, String analyzerNames) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function found containing address " + context.formatAddress(address));
		}

		try {
			AutoAnalysisManager manager = AutoAnalysisManager.getAnalysisManager(program);
			List<String> names = parseAnalyzerNames(analyzerNames);
			AddressSet body = new AddressSet(function.getBody());
			if (names.isEmpty()) {
				manager.reAnalyzeAll(body);
				manager.startAnalysis(TaskMonitor.DUMMY);
				return ToolResult.success(
					"Analysis started for function " + function.getName() + " at "
						+ context.formatAddress(function.getEntryPoint()));
			}

			List<Analyzer> analyzers = resolveAnalyzers(manager, names);
			if (analyzers.isEmpty()) {
				return ToolResult.error("No analyzers matched the supplied names: " + String.join(", ", names));
			}
			for (Analyzer analyzer : analyzers) {
				manager.scheduleOneTimeAnalysis(analyzer, body);
			}
			manager.startAnalysis(TaskMonitor.DUMMY);
			return ToolResult.success(
				"Scheduled analyzers [" + joinAnalyzerNames(analyzers) + "] for function " + function.getName()
					+ " at " + context.formatAddress(function.getEntryPoint()));
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to start function analysis: " + ex.getMessage());
		}
	}

	private AddressSet resolveAddressSet(Program program, String startText, String endText) {
		Address start = parseOptionalAddress(program, startText);
		if (startText != null && !startText.isBlank() && start == null) {
			return null;
		}
		Address end = parseOptionalAddress(program, endText);
		if (endText != null && !endText.isBlank() && end == null) {
			return null;
		}

		if (start == null && end == null) {
			Address min = program.getMinAddress();
			Address max = program.getMaxAddress();
			if (min == null || max == null) {
				return null;
			}
			return new AddressSet(min, max);
		}

		if (start == null) {
			start = end;
		}
		if (end == null) {
			end = start;
		}
		return new AddressSet(start, end);
	}

	private Address parseOptionalAddress(Program program, String addressText) {
		if (addressText == null || addressText.isBlank()) {
			return null;
		}
		return context.parseAddress(program, addressText);
	}

	private List<String> parseAnalyzerNames(String analyzerNames) {
		if (analyzerNames == null || analyzerNames.isBlank()) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		Arrays.stream(analyzerNames.split("[,;\\n]"))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.forEach(names::add);
		return names;
	}

	private List<Analyzer> resolveAnalyzers(AutoAnalysisManager manager, List<String> requestedNames) {
		Map<String, Analyzer> available = listAnalyzersInternal(manager);
		Map<String, Analyzer> cache = new HashMap<>();
		List<Analyzer> resolved = new ArrayList<>();
		for (String name : requestedNames) {
			String trimmed = name.trim();
			Analyzer analyzer = cache.get(trimmed);
			if (analyzer == null) {
				analyzer = available.get(trimmed);
			}
			if (analyzer == null) {
				for (Map.Entry<String, Analyzer> entry : available.entrySet()) {
					if (entry.getKey().equalsIgnoreCase(trimmed)) {
						analyzer = entry.getValue();
						break;
					}
				}
			}
			if (analyzer == null) {
				analyzer = manager.getAnalyzer(trimmed);
			}
			if (analyzer == null) {
				analyzer = manager.getAnalyzer(trimmed.toLowerCase(Locale.ROOT));
			}
			if (analyzer == null) {
				analyzer = manager.getAnalyzer(trimmed.toUpperCase(Locale.ROOT));
			}
			if (analyzer != null) {
				cache.put(trimmed, analyzer);
				if (!resolved.contains(analyzer)) {
					resolved.add(analyzer);
				}
			}
		}
		return resolved;
	}

	private String joinAnalyzerNames(List<Analyzer> analyzers) {
		return analyzers.stream()
				.filter(Objects::nonNull)
				.map(Analyzer::getName)
				.toList()
				.toString();
	}

	private String describeRange(AddressSet set) {
		if (set == null || set.isEmpty()) {
			return "<empty>";
		}
		return context.formatAddress(set.getMinAddress()) + "-" + context.formatAddress(set.getMaxAddress());
	}

	private ToolResult doListAnalyzers(Program program) {
		try {
			AutoAnalysisManager manager = AutoAnalysisManager.getAnalysisManager(program);
			Map<String, Analyzer> available = listAnalyzersInternal(manager);
			if (available.isEmpty()) {
				return ToolResult.error("No analyzers discovered.");
			}
			List<String> names = available.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
			return ToolResult.success("Available analyzers (" + names.size() + ")", String.join("\n", names));
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to list analyzers: " + ex.getMessage());
		}
	}

	private Map<String, Analyzer> listAnalyzersInternal(AutoAnalysisManager manager) {
		Map<String, Analyzer> map = new HashMap<>();
		try {
			Field taskField = AutoAnalysisManager.class.getDeclaredField("taskArray");
			taskField.setAccessible(true);
			Object value = taskField.get(manager);
			if (value instanceof Object[] taskLists) {
				for (Object taskListObj : taskLists) {
					if (!(taskListObj instanceof Object taskList)) {
						continue;
					}
					Method iteratorMethod = taskList.getClass().getMethod("iterator");
					@SuppressWarnings("unchecked")
					java.util.Iterator<Object> it = (java.util.Iterator<Object>) iteratorMethod.invoke(taskList);
					while (it != null && it.hasNext()) {
						Object scheduler = it.next();
						if (scheduler == null) {
							continue;
						}
						Method getAnalyzer = scheduler.getClass().getDeclaredMethod("getAnalyzer");
						getAnalyzer.setAccessible(true);
						Object analyzerObj = getAnalyzer.invoke(scheduler);
						if (analyzerObj instanceof Analyzer analyzer) {
							map.putIfAbsent(analyzer.getName(), analyzer);
						}
					}
				}
			}
		}
		catch (Exception ignored) {
			// fall back: try a few known analyzer names if provided later
		}
		return map;
	}
}
