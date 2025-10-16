package ghidracopilot.ai;

import java.util.Objects;

/**
 * Encapsulates the information required to submit a chat request to a provider.
 *
 * @param prompt       user supplied text (never {@code null} or blank)
 * @param modelId      identifier of the target model (may be {@code null})
 */
public record ChatRequest(String prompt, String modelId) {

	public ChatRequest {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be null or blank");
		}
		prompt = prompt.trim();
		modelId = (modelId != null && !modelId.isBlank()) ? modelId.trim() : null;
	}

	public ChatRequest(String prompt) {
		this(prompt, null);
	}

	public ChatRequest withModel(String newModelId) {
		return new ChatRequest(prompt, newModelId);
	}

	@Override
	public String toString() {
		return "ChatRequest[prompt=" + preview(prompt) + ", modelId=" +
			(modelId != null ? modelId : "<default>") + "]";
	}

	private static String preview(String value) {
		String trimmed = Objects.requireNonNull(value, "value");
		if (trimmed.length() <= 32) {
			return trimmed;
		}
		return trimmed.substring(0, 29) + "...";
	}
}
