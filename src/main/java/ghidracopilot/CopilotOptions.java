package ghidracopilot;

import ghidracopilot.ai.AiProvider;
import ghidracopilot.ai.SpringAiChatServiceFactory;

/**
 * Shared constants for Copilot option names and default values.
 */
public final class CopilotOptions {

	public static final String OPTIONS_CATEGORY = "Ghidra Copilot";

	public static final String OPTION_PROVIDER = "Provider";
	public static final String OPTION_SYSTEM_PROMPT = "System Prompt";

	public static final String OPTION_OPENAI_API_KEY = "OpenAI API Key";
	public static final String OPTION_OPENAI_BASE_URL = "OpenAI Base URL";
	public static final String OPTION_OPENAI_MODEL = "OpenAI Model";

	public static final String OPTION_AZURE_API_KEY = "Azure OpenAI API Key";
	public static final String OPTION_AZURE_ENDPOINT = "Azure OpenAI Endpoint";
	public static final String OPTION_AZURE_DEPLOYMENT = "Azure OpenAI Deployment";
	public static final String OPTION_AZURE_MODEL = "Azure OpenAI Model";

	public static final String OPTION_ANTHROPIC_API_KEY = "Anthropic API Key";
	public static final String OPTION_ANTHROPIC_MODEL = "Anthropic Model";

	public static final String OPTION_OLLAMA_BASE_URL = "Ollama Base URL";
	public static final String OPTION_OLLAMA_MODEL = "Ollama Model";

	public static final String DEFAULT_PROVIDER = AiProvider.OPENAI.id();
	public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
	public static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
	public static final String DEFAULT_OLLAMA_MODEL = "llama3.1";
	public static final String DEFAULT_ANTHROPIC_MODEL = "claude-3-5-sonnet-latest";

	private CopilotOptions() {
		// Utility class
	}

	public static String defaultSystemPrompt() {
		return SpringAiChatServiceFactory.DEFAULT_SYSTEM_PROMPT;
	}
}
