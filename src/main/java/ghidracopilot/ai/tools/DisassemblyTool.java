package ghidracopilot.ai.tools;

import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Program;

/**
 * Tool for retrieving disassembly (listing) text for the current function.
 */
final class DisassemblyTool {

	private static final int DEFAULT_MAX_INSTRUCTIONS = 256;

	private final CopilotToolContext context;

	DisassemblyTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "list_disassembly",
		description = "Return the disassembly for the function containing the provided address, up to a limit. "
			+ "Marks the current instruction with '>'.")
	ToolResult listDisassembly(
		@ToolParam(description = "Hexadecimal address within the target function.", required = true)
		String addressText,
		@ToolParam(description = "Maximum instructions to return (default 256).")
		Integer maxInstructions,
		@ToolParam(description = "Include bytes for each instruction (default true).")
		Boolean includeBytes) {
		return context.withCurrentProgram(program -> doList(program, addressText, maxInstructions, includeBytes));
	}

	private ToolResult doList(Program program, String addressText, Integer maxInstructions, Boolean includeBytes) {
		Address address;
		try {
			address = context.parseAddress(program, addressText);
		}
		catch (IllegalArgumentException ex) {
			return ToolResult.error(ex.getMessage());
		}
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		Listing listing = program.getListing();
		Function function = program.getFunctionManager().getFunctionContaining(address);
		if (function == null) {
			return ToolResult.error("No function contains address " + context.formatAddress(address));
		}

		int limit = context.normalizeLimit(maxInstructions, DEFAULT_MAX_INSTRUCTIONS, DEFAULT_MAX_INSTRUCTIONS);
		boolean emitBytes = includeBytes == null || includeBytes.booleanValue();
		StringBuilder data = new StringBuilder();

		var instructions = listing.getInstructions(function.getBody(), true);
		int count = 0;
		boolean truncated = false;
		while (instructions.hasNext()) {
			Instruction instruction = instructions.next();
			String prefix = instruction.getAddress().equals(address) ? "> " : "  ";
			data.append(prefix).append(context.formatAddress(instruction.getAddress())).append("  ");
			if (emitBytes) {
				data.append(formatBytes(instruction)).append("  ");
			}
			data.append(instruction).append(System.lineSeparator());
			count++;
			if (count >= limit) {
				if (instructions.hasNext()) {
					truncated = true;
				}
				break;
			}
		}

		if (truncated) {
			data.append("  ... (truncated after ").append(limit).append(" instructions)").append(System.lineSeparator());
		}

		String message = "Disassembly for function " + function.getName()
			+ " (contains " + context.formatAddress(address) + ")";
		String payload = data.toString().stripTrailing();
		return payload.isEmpty()
			? ToolResult.error("No disassembly available for function " + function.getName())
			: ToolResult.success(message, payload);
	}

	private static String formatBytes(Instruction instruction) {
		int length = instruction.getLength();
		if (length <= 0) {
			return "";
		}
		byte[] buffer = new byte[length];
		instruction.getBytes(buffer, 0);
		StringBuilder sb = new StringBuilder(length * 3);
		for (int i = 0; i < buffer.length; i++) {
			if (i > 0) {
				sb.append(' ');
			}
			sb.append(String.format(Locale.ROOT, "%02X", buffer[i]));
		}
		return sb.toString();
	}
}
