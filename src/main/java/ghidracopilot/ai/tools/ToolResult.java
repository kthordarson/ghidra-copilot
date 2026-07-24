package ghidracopilot.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Standardized response emitted by Copilot tool methods.
 */
public record ToolResult(
	boolean success,
	String message,
	Object data,
	String toolSummary,
	String errorMessage) {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	public ToolResult {
		if ((toolSummary == null || toolSummary.isBlank())) {
			toolSummary = success
				? message
				: (errorMessage != null && !errorMessage.isBlank() ? errorMessage : message);
		}
		if (!success && (errorMessage == null || errorMessage.isBlank())) {
			errorMessage = message;
		}
	}

	public ToolResult(boolean success, String message, Object data) {
		this(success, message, data, null, success ? null : message);
	}

	public ToolResult(boolean success, String message, Object data, String toolSummary) {
		this(success, message, data, toolSummary, success ? null : message);
	}

	public static ToolResult success(String message) {
		return new ToolResult(true, message, null, message, null);
	}

	public static ToolResult success(String message, Object data) {
		return new ToolResult(true, message, data, message, null);
	}

	public static ToolResult success(String message, Object data, String toolSummary) {
		return new ToolResult(true, message, data, toolSummary, null);
	}

	public static ToolResult error(String message) {
		return new ToolResult(false, message, null, message, message);
	}

	public static ToolResult error(String message, Object data) {
		return new ToolResult(false, message, data, message, message);
	}

	public static ToolResult error(String message, Object data, String toolSummary) {
		return new ToolResult(false, message, data, toolSummary, message);
	}

	public static ToolResult tryParse(String json) {
		if (json == null || json.isBlank() || json.charAt(0) != '{') {
			return null;
		}
		try {
			return MAPPER.readValue(json, ToolResult.class);
		}
		catch (Exception ignored) {
			return null;
		}
	}

	public String toJson() {
		try {
			return MAPPER.writeValueAsString(this);
		}
		catch (JsonProcessingException ex) {
			String escapedMessage = message == null ? "" : message.replace("\"", "\\\"");
			return "{\"success\":" + success + ",\"message\":\"" + escapedMessage + "\"}";
		}
	}

	@Override
	public String toString() {
		return toJson();
	}
}
