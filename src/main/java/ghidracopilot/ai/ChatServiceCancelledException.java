package ghidracopilot.ai;

/**
 * Marker exception for user initiated cancellation of an in-flight chat request.
 */
public class ChatServiceCancelledException extends ChatServiceException {

	public ChatServiceCancelledException(String message) {
		super(message);
	}

	public ChatServiceCancelledException(String message, Throwable cause) {
		super(message, cause);
	}
}
