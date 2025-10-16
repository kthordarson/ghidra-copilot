package ghidracopilot.ai;

/**
 * Simple abstraction for sending chat prompts and receiving responses from an AI model.
 */
public interface ChatService {

	/**
	 * Submit a user prompt to the backing AI model.
	 *
	 * @param request structured chat request containing the prompt and optional runtime
	 *            overrides
	 * @return generated response text
	 * @throws ChatServiceException when the call fails or the response cannot be produced
	 */
	String chat(ChatRequest request) throws ChatServiceException;
}
