package ghidracopilot.ai;

import java.util.Objects;

/**
 * Represents a single chat message exchanged with the AI provider.
 */
public record ChatMessage(Role role, String content) {

	public enum Role {
		SYSTEM,
		USER,
		ASSISTANT
	}

	public ChatMessage {
		role = Objects.requireNonNull(role, "role must not be null");
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be null or blank");
		}
		content = content.trim();
	}

	public static ChatMessage system(String content) {
		return new ChatMessage(Role.SYSTEM, content);
	}

	public static ChatMessage user(String content) {
		return new ChatMessage(Role.USER, content);
	}

	public static ChatMessage assistant(String content) {
		return new ChatMessage(Role.ASSISTANT, content);
	}
}
