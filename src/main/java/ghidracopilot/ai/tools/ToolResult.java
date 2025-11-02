package ghidracopilot.ai.tools;

/**
 * Standardized response emitted by Copilot tool methods.
 */
public record ToolResult(boolean success, String message, String data) {

	public static ToolResult success(String message) {
		return new ToolResult(true, message, null);
	}

	public static ToolResult success(String message, String data) {
		return new ToolResult(true, message, data);
	}

	public static ToolResult error(String message) {
		return new ToolResult(false, message, null);
	}

	public static ToolResult error(String message, String data) {
		return new ToolResult(false, message, data);
	}
}
