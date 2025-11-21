package ghidracopilot.ai;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the information required to submit a chat request to a provider.
 *
 * @param prompt           user supplied text (never {@code null} or blank)
 * @param modelId          identifier of the target model (may be {@code null})
 * @param systemContext    optional contextual system prompt additions (may be {@code null})
 * @param history          prior conversation exchanged with the model (never {@code null},
 *            but may be empty)
 * @param toolCallObserver optional observer that receives tool call lifecycle updates
 * @param interactionMode  how assertive the agent should be (never {@code null})
 */
public record ChatRequest(String prompt, String modelId, String systemContext, List<ChatMessage> history,
		ToolCallObserver toolCallObserver, InteractionMode interactionMode) {

	public ChatRequest {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("prompt must not be null or blank");
		}
		prompt = prompt.trim();
		modelId = (modelId != null && !modelId.isBlank()) ? modelId.trim() : null;
		systemContext = (systemContext != null && !systemContext.isBlank()) ? systemContext.trim() : null;

		if (history == null || history.isEmpty()) {
			history = List.of();
		}
		else {
			if (history.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("history must not contain null messages");
		}
			history = List.copyOf(history);
		}

		if (toolCallObserver == null) {
			toolCallObserver = update -> {
				// default no-op observer
			};
		}

		interactionMode = interactionMode != null ? interactionMode : InteractionMode.ASK;
	}

	public ChatRequest(String prompt) {
		this(prompt, null, null, List.of(), null, InteractionMode.ASK);
	}

	public ChatRequest(String prompt, String modelId) {
		this(prompt, modelId, null, List.of(), null, InteractionMode.ASK);
	}

	public ChatRequest withModel(String newModelId) {
		return new ChatRequest(prompt, newModelId, systemContext, history, toolCallObserver, interactionMode);
	}

	public ChatRequest withSystemContext(String newSystemContext) {
		return new ChatRequest(prompt, modelId, newSystemContext, history, toolCallObserver, interactionMode);
	}

	public ChatRequest withHistory(List<ChatMessage> newHistory) {
		return new ChatRequest(prompt, modelId, systemContext, newHistory, toolCallObserver, interactionMode);
	}

	@Override
	public String toString() {
		return "ChatRequest[prompt=" + preview(prompt) + ", modelId=" +
			(modelId != null ? modelId : "<default>") +
			", mode=" + interactionMode +
			(systemContext != null ? ", context=" + preview(systemContext) : "") +
			(history.isEmpty() ? "" : ", history=" + history.size() + " messages") + "]";
	}

	private static String preview(String value) {
		String trimmed = Objects.requireNonNull(value, "value");
		if (trimmed.length() <= 32) {
			return trimmed;
		}
		return trimmed.substring(0, 29) + "...";
	}
}
