package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Tools for exploring caller/callee relationships.
 */
final class CallGraphTool {

	private static final int DEFAULT_LIMIT = 25;
	private static final int MAX_LIMIT = 200;

	private final CopilotToolContext context;

	CallGraphTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "describe_call_graph",
		description = "Return the callers and callees for the function containing the specified address.")
	ToolResult describeCallGraph(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Maximum number of callers to include (default 25, max 200).")
		Integer maxCallers,
		@ToolParam(description = "Maximum number of callees to include (default 25, max 200).")
		Integer maxCallees) {
		return context.withCurrentProgram(
			program -> doDescribeCallGraph(program, addressText, maxCallers, maxCallees));
	}

	private ToolResult doDescribeCallGraph(Program program, String addressText, Integer maxCallers, Integer maxCallees) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}
		FunctionManager functionManager = program.getFunctionManager();
		Function function = functionManager.getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function found containing address " + context.formatAddress(address));
		}

		int callerLimit = context.normalizeLimit(maxCallers, DEFAULT_LIMIT, MAX_LIMIT);
		int calleeLimit = context.normalizeLimit(maxCallees, DEFAULT_LIMIT, MAX_LIMIT);

		try {
			List<String> lines = new ArrayList<>();
			lines.add("Function: " + function.getName() + " @ " + context.formatAddress(function.getEntryPoint()));

			Set<Function> callers = function.getCallingFunctions(TaskMonitorAdapter.DUMMY_MONITOR);
			List<Function> callerList = new ArrayList<>(callers);
			callerList.sort(Comparator.comparing(Function::getEntryPoint));
			lines.add("Callers (" + callerList.size() + "):");
			if (callerList.isEmpty()) {
				lines.add("  <none>");
			}
			else {
				for (int i = 0; i < callerList.size() && i < callerLimit; i++) {
					Function caller = callerList.get(i);
					lines.add("  " + caller.getName() + " @ " + context.formatAddress(caller.getEntryPoint()));
				}
				if (callerList.size() > callerLimit) {
					lines.add("  ... " + (callerList.size() - callerLimit) + " additional caller(s) omitted ...");
				}
			}

			Set<Function> callees = function.getCalledFunctions(TaskMonitorAdapter.DUMMY_MONITOR);
			List<Function> calleeList = new ArrayList<>(callees);
			calleeList.sort(Comparator.comparing(Function::getEntryPoint));
			lines.add("Callees (" + calleeList.size() + "):");
			if (calleeList.isEmpty()) {
				lines.add("  <none>");
			}
			else {
				for (int i = 0; i < calleeList.size() && i < calleeLimit; i++) {
					Function callee = calleeList.get(i);
					lines.add("  " + callee.getName() + " @ " + context.formatAddress(callee.getEntryPoint()));
				}
				if (calleeList.size() > calleeLimit) {
					lines.add("  ... " + (calleeList.size() - calleeLimit) + " additional callee(s) omitted ...");
				}
			}

			return ToolResult.success("Call graph summary generated.", String.join("\n", lines));
		}
		catch (CancelledException ex) {
			return ToolResult.error("Call graph traversal cancelled: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to describe call graph: " + ex.getMessage());
		}
	}
}
