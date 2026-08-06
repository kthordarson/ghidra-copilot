package ghidracopilot.ai.tools;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;

/**
 * Tool for inspecting raw bytes and strings at a given address.
 */
final class DataStringTool {

	private static final int DEFAULT_MAX_BYTES = 256;
	private static final int ABSOLUTE_MAX_BYTES = 1024;

	private final CopilotToolContext context;

	DataStringTool(CopilotToolContext context) {
		this.context = context;
	}

	@Tool(name = "show_data_at_address",
		description = "Read bytes at the given address, decode a likely string (if present), and optionally include nearby packed strings.")
	ToolResult showDataAtAddress(
		@ToolParam(description = "Hexadecimal address to read from.", required = true) String addressText,
		@ToolParam(description = "Maximum number of bytes to read (default 256, max 1024).") Integer maxBytes,
		@ToolParam(description = "Include nearby sequential strings (default false).") Boolean includeNeighbors) {
		return context.withCurrentProgram(
			program -> doShowDataAtAddress(program, addressText, maxBytes, includeNeighbors != null && includeNeighbors));
	}

	private ToolResult doShowDataAtAddress(Program program, String addressText, Integer maxBytes, boolean includeNeighbors) {
		Address address = context.parseAddress(program, addressText);
		if (address == null) {
			return ToolResult.error("Unable to parse address: " + addressText);
		}

		int limit = context.normalizeLimit(maxBytes, DEFAULT_MAX_BYTES, ABSOLUTE_MAX_BYTES);
		Memory memory = program.getMemory();
		byte[] buffer = new byte[limit];
		int read;
		try {
			read = memory.getBytes(address, buffer);
		}
		catch (MemoryAccessException ex) {
			return ToolResult.error("Memory read failed: " + ex.getMessage());
		}
		if (read <= 0) {
			return ToolResult.error("No bytes could be read at " + context.formatAddress(address));
		}
		if (read < buffer.length) {
			byte[] resized = new byte[read];
			System.arraycopy(buffer, 0, resized, 0, read);
			buffer = resized;
		}

		StringBuilder data = new StringBuilder();
		data.append("Decoded (UTF-8, null-terminated scan): ").append(decodeString(buffer)).append("\n");
		data.append("Raw bytes: ").append(toHex(buffer));

		if (includeNeighbors) {
			List<String> neighborLines = collectNeighborStrings(buffer, address);
			if (!neighborLines.isEmpty()) {
				data.append("\nNearby strings:");
				for (String line : neighborLines) {
					data.append("\n  ").append(line);
				}
			}
		}

		return ToolResult.success(
			"Read " + buffer.length + " byte(s) starting at " + context.formatAddress(address),
			data.toString());
	}

	private String decodeString(byte[] bytes) {
		int end = 0;
		while (end < bytes.length && bytes[end] != 0) {
			end++;
		}
		if (end == 0) {
			return "<no printable string>";
		}
		String text = new String(bytes, 0, end, StandardCharsets.UTF_8);
		if (isMostlyPrintable(text)) {
			return text;
		}
		return "<non-printable or binary data>";
	}

	private List<String> collectNeighborStrings(byte[] bytes, Address start) {
		List<String> lines = new ArrayList<>();
		int offset = 0;
		while (offset < bytes.length) {
			int nextNull = findNextNull(bytes, offset);
			int length = nextNull - offset;
			if (length >= 3) {
				String candidate = new String(bytes, offset, length, StandardCharsets.UTF_8);
				if (isMostlyPrintable(candidate)) {
					Address addr = addOffset(start, offset);
					lines.add(context.formatAddress(addr) + " : \"" + candidate + "\"");
				}
			}
			offset = nextNull + 1;
		}
		return lines;
	}

	private int findNextNull(byte[] bytes, int start) {
		for (int i = start; i < bytes.length; i++) {
			if (bytes[i] == 0) {
				return i;
			}
		}
		return bytes.length;
	}

	private Address addOffset(Address base, long delta) {
		return base.add(delta);
	}

	private boolean isMostlyPrintable(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		int printable = 0;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch >= 0x20 && ch <= 0x7e) {
				printable++;
			}
		}
		return printable >= Math.max(3, text.length() * 0.6);
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
