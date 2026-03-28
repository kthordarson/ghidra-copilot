package ghidracopilot.ai;

/**
 * Callback interface for receiving streaming chat events as they arrive from the model.
 * All methods may be called from a background thread; UI consumers should
 * marshal to the EDT as needed.
 */
public interface ChatEventListener {

	/**
	 * A new chunk of assistant text has arrived.
	 */
	void onDelta(String delta);

	/**
	 * A new chunk of reasoning/thinking text has arrived.
	 */
	default void onThinking(String delta) {}

	/**
	 * The model reported what it is currently doing.
	 */
	default void onIntent(String intent) {}

	/**
	 * A tool call lifecycle event occurred.
	 */
	void onToolCallUpdate(ToolCallUpdate update);

	/**
	 * Token usage information is available for this turn.
	 */
	default void onUsage(int promptTokens, int completionTokens) {}

	/**
	 * The streaming request completed successfully.
	 */
	void onComplete(String fullResponse);

	/**
	 * The streaming request encountered an error.
	 */
	void onError(String errorMessage);
}
