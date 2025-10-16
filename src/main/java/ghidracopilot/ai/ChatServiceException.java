package ghidracopilot.ai;

/**
 * Checked exception used for failures returned by a {@link ChatService}.
 */
public class ChatServiceException extends Exception {

	public ChatServiceException(String message) {
		super(message);
	}

	public ChatServiceException(String message, Throwable cause) {
		super(message, cause);
	}
}
