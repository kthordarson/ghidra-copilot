package ghidracopilot.ai.tools;

import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;

/**
 * Tools for inspecting and patching bytes within the current program.
 */
final class PatchTool implements MutationTool {

	private static final int MAX_PATCH_LENGTH = 1024;

	private final CopilotToolContext context;

	PatchTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "read_bytes",
		description = "Read a sequence of bytes starting at the supplied address.")
	ToolResult readBytes(
		@ToolParam(description = "Hexadecimal address of the first byte.", required = true)
		String addressText,
		@ToolParam(description = "Number of bytes to read (default 16, max 256).")
		Integer length) {
		return context.withCurrentProgram(program -> doReadBytes(program, addressText, length));
	}

	@Tool(name = "patch_bytes",
		description = "Apply a hexadecimal byte patch at the specified address and report the before/after values.")
	ToolResult patchBytes(
		@ToolParam(description = "Hexadecimal address of the first byte to modify.", required = true)
		String addressText,
		@ToolParam(description = "Hexadecimal byte values (e.g., '90 90 90' or '909090').", required = true)
		String hexBytes) {
		return context.withCurrentProgram(program -> doPatchBytes(program, addressText, hexBytes));
	}

	@Tool(name = "fill_pattern",
		description = "Fill a region with a repeated byte pattern, useful for NOP-ing or clearing code/data.")
	ToolResult fillPattern(
		@ToolParam(description = "Hexadecimal start address.", required = true)
		String addressText,
		@ToolParam(description = "Hexadecimal byte value to repeat (e.g., '90').", required = true)
		String byteValue,
		@ToolParam(description = "Number of bytes to write.", required = true)
		Integer count) {
		return context.withCurrentProgram(program -> doFillPattern(program, addressText, byteValue, count));
	}

	private ToolResult doReadBytes(Program program, String addressText, Integer length) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		int len = context.normalizeLimit(length, 16, 256);

		Memory memory = program.getMemory();
		byte[] buffer = new byte[len];
		try {
			int read = memory.getBytes(address, buffer);
			if (read <= 0) {
				return ToolResult.error("No bytes could be read at " + context.formatAddress(address));
			}
			byte[] truncated = buffer;
			if (read < buffer.length) {
				truncated = new byte[read];
				System.arraycopy(buffer, 0, truncated, 0, read);
			}
			return ToolResult.success(
				"Read " + truncated.length + " byte(s) starting at " + context.formatAddress(address),
				toHex(truncated));
		}
		catch (MemoryAccessException ex) {
			return ToolResult.error("Memory read failed: " + ex.getMessage());
		}
	}

	private ToolResult doPatchBytes(Program program, String addressText, String hexBytes) {
		if (hexBytes == null || hexBytes.isBlank()) {
			return ToolResult.error("Hex byte sequence must not be blank.");
		}
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		byte[] newBytes = parseHexBytes(hexBytes);
		if (newBytes.length == 0) {
			return ToolResult.error("No bytes parsed from input: " + hexBytes);
		}
		if (newBytes.length > MAX_PATCH_LENGTH) {
			return ToolResult.error("Patch length exceeds maximum of " + MAX_PATCH_LENGTH + " bytes.");
		}

		Memory memory = program.getMemory();
		byte[] oldBytes = new byte[newBytes.length];
		try {
			int read = memory.getBytes(address, oldBytes);
			if (read < newBytes.length) {
				byte[] resized = new byte[newBytes.length];
				if (read > 0) {
					System.arraycopy(oldBytes, 0, resized, 0, read);
				}
				oldBytes = resized;
			}
		}
		catch (MemoryAccessException ignored) {
			// Continue even if we cannot read previous bytes.
		}

		boolean commit = false;
		int tx = program.startTransaction("Copilot Patch Bytes");
		try {
			memory.setBytes(address, newBytes);
			commit = true;
			StringBuilder data = new StringBuilder();
			data.append("Before: ").append(toHex(oldBytes)).append("\n");
			data.append("After : ").append(toHex(newBytes));
			return ToolResult.success(
				"Patched " + newBytes.length + " byte(s) at " + context.formatAddress(address),
				data.toString());
		}
		catch (MemoryAccessException ex) {
			return ToolResult.error("Memory write failed: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to patch bytes: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private ToolResult doFillPattern(Program program, String addressText, String byteValue, Integer count) {
		if (byteValue == null || byteValue.isBlank()) {
			return ToolResult.error("Byte value must not be blank.");
		}
		if (count == null || count <= 0) {
			return ToolResult.error("Count must be greater than zero.");
		}

		byte[] pattern = parseHexBytes(byteValue);
		if (pattern.length != 1) {
			return ToolResult.error("Pattern must resolve to exactly one byte.");
		}
		if (count > MAX_PATCH_LENGTH) {
			return ToolResult.error("Fill length exceeds maximum of " + MAX_PATCH_LENGTH + " bytes.");
		}

		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		byte[] bytes = new byte[count];
		for (int i = 0; i < count; i++) {
			bytes[i] = pattern[0];
		}

		boolean commit = false;
		int tx = program.startTransaction("Copilot Fill Pattern");
		try {
			program.getMemory().setBytes(address, bytes);
			commit = true;
			return ToolResult.success(
				"Filled " + count + " byte(s) at " + context.formatAddress(address) + " with 0x"
					+ toHex(new byte[] { pattern[0] }),
				"Applied pattern " + toHex(bytes));
		}
		catch (MemoryAccessException ex) {
			return ToolResult.error("Memory write failed: " + ex.getMessage());
		}
		catch (Exception ex) {
			return ToolResult.error("Unable to fill pattern: " + ex.getMessage());
		}
		finally {
			program.endTransaction(tx, commit);
		}
	}

	private byte[] parseHexBytes(String text) {
		String normalized = text.replaceAll("[^0-9A-Fa-f]", "");
		if (normalized.isEmpty() || (normalized.length() % 2) != 0) {
			return new byte[0];
		}
		int length = normalized.length() / 2;
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			int index = i * 2;
			int value = Integer.parseInt(normalized.substring(index, index + 2), 16);
			bytes[i] = (byte) value;
		}
		return bytes;
	}

	private String toHex(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return "<none>";
		}
		StringBuilder builder = new StringBuilder(bytes.length * 3);
		for (int i = 0; i < bytes.length; i++) {
			builder.append(String.format(Locale.ROOT, "%02X", bytes[i]));
			if (i < bytes.length - 1) {
				builder.append(' ');
			}
		}
		return builder.toString();
	}
}
