package ghidracopilot.ai;

import java.util.Locale;

/**
 * Enumeration of supported AI providers for Spring AI integration.
 */
public enum AiProvider {

	OPENAI("openai", "OpenAI"),

	AZURE_OPENAI("azure-openai", "Azure OpenAI"),

	ANTHROPIC("anthropic", "Anthropic"),

	OLLAMA("ollama", "Ollama"),

	GITHUB_COPILOT("github-copilot", "GitHub Copilot");

	private final String id;

	private final String displayName;

	AiProvider(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/**
	 * Resolve a provider from user supplied text. Falls back to {@link #OPENAI}
	 * when the value is null or blank.
	 *
	 * @param value user supplied identifier or name
	 * @return resolved provider (never null)
	 */
	public static AiProvider fromUserValue(String value) {
		if (value == null || value.trim().isEmpty()) {
			return OPENAI;
		}
		String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "OPENAI" -> OPENAI;
			case "AZURE", "AZURE_OPENAI" -> AZURE_OPENAI;
			case "ANTHROPIC" -> ANTHROPIC;
			case "OLLAMA" -> OLLAMA;
			case "GITHUB_COPILOT", "COPILOT" -> GITHUB_COPILOT;
			default -> OPENAI;
		};
	}
}
