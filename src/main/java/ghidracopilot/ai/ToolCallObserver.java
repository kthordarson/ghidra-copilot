package ghidracopilot.ai;

/**
 * Listener that receives updates about tool call execution while a chat request
 * is processed.
 */
@FunctionalInterface
public interface ToolCallObserver {

	/**
	 * Invoked when a tool call state changes.
	 *
	 * @param update details about the tool call transition (never {@code null})
	 */
	void onToolCallUpdate(ToolCallUpdate update);
}

