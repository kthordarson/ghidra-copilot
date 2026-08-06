package ghidracopilot.ai.tools;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.block.BasicBlockModel;
import ghidra.program.model.block.CodeBlock;
import ghidra.program.model.block.CodeBlockReference;
import ghidra.program.model.block.CodeBlockReferenceIterator;
import ghidra.program.model.listing.Program;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * Tools for extracting basic block and control flow information.
 */
final class ControlFlowTool {

	private final CopilotToolContext context;

	ControlFlowTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "describe_basic_block",
		description = "Summarize the basic block at the supplied address, including predecessors and successors.")
	ToolResult describeBasicBlock(
		@ToolParam(description = "Hexadecimal address within the target basic block.", required = true)
		String addressText) {
		return context.withCurrentProgram(program -> doDescribeBasicBlock(program, addressText));
	}

	private ToolResult doDescribeBasicBlock(Program program, String addressText) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		try {
			BasicBlockModel model = new BasicBlockModel(program);
			CodeBlock block = model.getCodeBlockAt(address, TaskMonitorAdapter.DUMMY);
			if (block == null) {
				return ToolResult.error("No basic block found at " + context.formatAddress(address));
			}

			List<String> lines = new ArrayList<>();
			lines.add("Basic block starting at " + context.formatAddress(block.getFirstStartAddress()));
			lines.add("Length: " + block.getNumAddresses() + " address(es)");

			lines.add("Destinations:");
			appendReferences(lines, "  ", block.getDestinations(TaskMonitorAdapter.DUMMY));

			lines.add("Sources:");
			appendReferences(lines, "  ", block.getSources(TaskMonitorAdapter.DUMMY));

			return ToolResult.success("Control flow summary generated.", String.join("\n", lines));
		}
		catch (CancelledException ex) {
			return ToolResult.error("Basic block traversal cancelled: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to describe basic block: " + ex.getMessage());
		}
	}

	private void appendReferences(List<String> lines, String indent, CodeBlockReferenceIterator iterator)
		throws CancelledException {
		if (iterator == null || !iterator.hasNext()) {
			lines.add(indent + "<none>");
			return;
		}
		while (iterator.hasNext()) {
			CodeBlockReference ref = iterator.next();
			lines.add(indent + context.formatAddress(ref.getSourceAddress()) + " -> "
				+ context.formatAddress(ref.getDestinationAddress()) + " (" + ref.getFlowType() + ")");
		}
	}
}
