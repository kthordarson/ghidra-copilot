package ghidracopilot.ai;

/**
 * Simple abstraction for sending chat prompts and receiving responses from an AI model.
 */
public interface ChatService {

	/**
	 * Submit a user prompt to the backing AI model (blocking).
	 *
	 * @param request structured chat request containing the prompt and optional runtime
	 *            overrides
	 * @return generated response text
	 * @throws ChatServiceException when the call fails or the response cannot be produced
	 */
	String chat(ChatRequest request) throws ChatServiceException;

	/**
	 * Submit a user prompt with streaming response delivery.
	 * <p>
	 * Content deltas, tool call updates, and completion events are delivered
	 * via the provided {@link ChatEventListener}. This method blocks until the
	 * full response (including any tool-call loops) has completed.
	 *
	 * @param request  structured chat request
	 * @param listener callback receiver for streaming events
	 * @throws ChatServiceException when the call fails
	 */
	default void streamChat(ChatRequest request, ChatEventListener listener) throws ChatServiceException {
		// Default implementation falls back to blocking chat and emits a single
		// onComplete event, so existing implementations still work.
		String response = chat(request);
		if (listener != null) {
			listener.onComplete(response != null ? response : "");
		}
	}
}

