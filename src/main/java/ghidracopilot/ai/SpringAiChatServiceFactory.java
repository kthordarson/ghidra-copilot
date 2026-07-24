package ghidracopilot.ai;

import java.util.Locale;
import java.util.Objects;

import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.AzureKeyCredential;

import ghidra.util.Msg;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.azure.openai.AzureOpenAiChatModel;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.util.StringUtils;

import ghidracopilot.ai.tools.CopilotToolRegistry;

/**
 * Factory for wiring Spring AI chat clients from user-configurable options.
 */
public final class SpringAiChatServiceFactory {

	private static final String BASE_SYSTEM_PROMPT = """
			You are Ghidra Copilot, an assistant that helps with reverse engineering tasks inside Ghidra. \
			Provide concise, technically accurate guidance and clearly call out any assumptions you make. \
			Act on implied intent: rename symbols, add or update comments, and refactor for readability when it supports \
			the request, without waiting for confirmation unless you risk altering behavior. \
			Use whatever inspection tools are available (or ask for them) to review existing names, comments, and context \
			before making changes. \
			Only change the user's Ghidra UI state (including cursor positioning or navigation tools) when they explicitly ask \
			or when it is essential to avoid confusion."""
			.strip();

	private static final String INTENT_SYSTEM_PROMPT = """
			IMPORTANT: Always call report_intent to keep the user informed about what you are doing. \
			Call it before starting any task, and update it whenever your focus changes. \
			Use short gerund-form phrases like "Decompiling function", "Analyzing call graph", "Renaming variables". \
			Call report_intent alongside your other tool calls, not in isolation."""
			.strip();

	private static final String COPILOT_INTENT_SYSTEM_PROMPT = """
			IMPORTANT: Keep the user informed about what you are doing by updating your current intent/status. \
			Set an intent before starting a task, and update it whenever your focus changes. \
			Use short gerund-form phrases like "Decompiling function", "Analyzing call graph", "Renaming variables"."""
			.strip();

	public static final String DEFAULT_SYSTEM_PROMPT =
		(BASE_SYSTEM_PROMPT + "\n\n" + INTENT_SYSTEM_PROMPT).strip();

	public static final String DEFAULT_COPILOT_SYSTEM_PROMPT =
		(BASE_SYSTEM_PROMPT + "\n\n" + COPILOT_INTENT_SYSTEM_PROMPT).strip();

	static {
		// Avoid JDK module access warnings/failures when Netty tries to reach jdk.internal.misc.Unsafe.
		System.setProperty("io.netty.tryReflectionSetAccessible", "false");
		System.setProperty("io.netty.noUnsafe", "true");
	}

	private SpringAiChatServiceFactory() {
		// utility
	}

	/**
	 * Attempts to create a {@link ChatService} from the supplied settings.
	 *
	 * @param settings user controlled configuration (must not be null)
	 * @return a result containing the chat service or an error message explaining why it
	 * could not be created
	 */
	public static Result create(ChatSettings settings) {
		if (settings == null) {
			return Result.failure(
					"Copilot is not configured. Update Tool Options > Ghidra Copilot with your provider credentials.");
		}

		AiProvider provider = settings.provider() != null ? settings.provider() : AiProvider.OPENAI;

		// GitHub Copilot uses the dedicated Copilot SDK (spawns CLI subprocess)
		if (provider == AiProvider.GITHUB_COPILOT) {
			return createCopilotSdk(settings);
		}

		try {
		ClientContext context = buildClientContext(settings);
		ChatService chatService = new SpringAiChatService(
			context.chatClient(),
			context.chatModel(),
			context.provider(),
			context.defaultModel(),
			context.azureDeployment(),
			context.azureModel(),
			context.systemPrompt());
			return Result.success(chatService, context.provider().displayName(), context.provider().id(),
					context.copilotTokenProvider());
		}
		catch (IllegalStateException ex) {
			Msg.warn(SpringAiChatServiceFactory.class, ex.getMessage());
			return Result.failure(ex.getMessage());
		}
	}

	private static Result createCopilotSdk(ChatSettings settings) {
		try {
			String modelName = textOrDefault(settings.copilotModel(), "claude-sonnet-4");
			String systemPrompt =
				buildEffectiveSystemPrompt(settings.systemPrompt(), AiProvider.GITHUB_COPILOT);

			CopilotSdkChatService chatService = new CopilotSdkChatService(modelName, systemPrompt);
			CopilotTokenProvider tokenProvider = new CopilotTokenProvider();
			return Result.success(chatService, AiProvider.GITHUB_COPILOT.displayName(),
					AiProvider.GITHUB_COPILOT.id(), tokenProvider);
		}
		catch (Exception ex) {
			String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			Msg.warn(SpringAiChatServiceFactory.class, "Failed to initialize Copilot SDK: " + msg);
			return Result.failure(
				"Failed to initialize GitHub Copilot. Ensure the 'copilot' CLI is installed and in PATH. Error: " + msg);
		}
	}

	private static ClientContext buildClientContext(ChatSettings settings) {
		AiProvider provider = settings.provider() != null ? settings.provider() : AiProvider.OPENAI;
		ProviderModelContext modelContext = switch (provider) {
			case OPENAI -> buildOpenAiContext(settings);
			case AZURE_OPENAI -> buildAzureOpenAiContext(settings);
			case ANTHROPIC -> buildAnthropicContext(settings);
			case OLLAMA -> buildOllamaContext(settings);
			case GITHUB_COPILOT -> throw new IllegalStateException("Copilot uses dedicated SDK path");
		};

		String systemPrompt = buildEffectiveSystemPrompt(settings.systemPrompt(), provider);

		ChatClient.Builder builder = ChatClient.builder(modelContext.chatModel())
				.defaultSystem(systemPrompt);
		var tools = CopilotToolRegistry.tools();
		if (tools != null && !tools.isEmpty()) {
			builder.defaultTools(tools.toArray());
		}
		ChatClient chatClient = builder.build();
		return new ClientContext(chatClient, modelContext.chatModel(), provider, modelContext.defaultModel(),
				modelContext.azureDeployment(), modelContext.azureModel(), systemPrompt,
				modelContext.copilotTokenProvider());
	}

	private static ProviderModelContext buildOpenAiContext(ChatSettings settings) {
		String apiKey = require(settings.openAiApiKey(),
				"OpenAI API key is required. Configure it under Tool Options > Ghidra Copilot.");
		String baseUrl = trimToNull(settings.openAiBaseUrl());
		String modelName = textOrDefault(settings.openAiModel(), "gpt-4o-mini");

		OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey);
		if (StringUtils.hasText(baseUrl)) {
			apiBuilder.baseUrl(baseUrl);
		}

		OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
				.model(modelName)
				.build();

		ChatModel chatModel = OpenAiChatModel.builder()
				.openAiApi(apiBuilder.build())
				.defaultOptions(chatOptions)
				.build();
		return new ProviderModelContext(chatModel, modelName, null, null);
	}

	private static ProviderModelContext buildAzureOpenAiContext(ChatSettings settings) {
		String apiKey = require(settings.azureApiKey(),
				"Azure OpenAI API key is required. Configure it under Tool Options > Ghidra Copilot.");
		String endpoint = require(settings.azureEndpoint(),
				"Azure OpenAI endpoint is required. Configure it under Tool Options > Ghidra Copilot.");
		String deployment = require(settings.azureDeployment(),
				"Azure OpenAI deployment name is required. Configure it under Tool Options > Ghidra Copilot.");
		String modelName = trimToNull(settings.azureModel());

		OpenAIClientBuilder clientBuilder = new OpenAIClientBuilder()
				.credential(new AzureKeyCredential(apiKey))
				.endpoint(endpoint);

		AzureOpenAiChatOptions options = AzureOpenAiChatOptions.builder()
				.deploymentName(deployment)
				.build();
		if (StringUtils.hasText(modelName)) {
			options.setModel(modelName);
		}

		ChatModel chatModel = AzureOpenAiChatModel.builder()
				.openAIClientBuilder(clientBuilder)
				.defaultOptions(options)
				.build();
		return new ProviderModelContext(chatModel, modelName, deployment, modelName);
	}

	private static ProviderModelContext buildAnthropicContext(ChatSettings settings) {
		String apiKey = require(settings.anthropicApiKey(),
				"Anthropic API key is required. Configure it under Tool Options > Ghidra Copilot.");
		String modelName = textOrDefault(settings.anthropicModel(), "claude-3-5-sonnet-latest");

		AnthropicChatOptions chatOptions = AnthropicChatOptions.builder()
				.model(modelName)
				.build();

		AnthropicApi.Builder apiBuilder = new AnthropicApi.Builder()
				.apiKey(apiKey);

		ChatModel chatModel = AnthropicChatModel.builder()
				.anthropicApi(apiBuilder.build())
				.defaultOptions(chatOptions)
				.build();
		return new ProviderModelContext(chatModel, modelName, null, null);
	}

	private static ProviderModelContext buildOllamaContext(ChatSettings settings) {
		String baseUrl = textOrDefault(settings.ollamaBaseUrl(), "http://localhost:11434");
		String modelName = textOrDefault(settings.ollamaModel(), "llama3.1");

		OllamaApi.Builder apiBuilder = new OllamaApi.Builder();
		if (StringUtils.hasText(baseUrl)) {
			apiBuilder.baseUrl(baseUrl);
		}

		OllamaOptions options = OllamaOptions.builder()
				.model(modelName)
				.build();

		ChatModel chatModel = OllamaChatModel.builder()
				.ollamaApi(apiBuilder.build())
				.defaultOptions(options)
				.modelManagementOptions(ModelManagementOptions.defaults())
				.build();
		return new ProviderModelContext(chatModel, modelName, null, null);
	}

	private static String require(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(message);
		}
		return value;
	}

	private static String textOrDefault(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value.trim() : defaultValue;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String buildEffectiveSystemPrompt(String configuredPrompt, AiProvider provider) {
		String systemPrompt = trimToNull(configuredPrompt);
		if (!StringUtils.hasText(systemPrompt)) {
			return provider == AiProvider.GITHUB_COPILOT
				? DEFAULT_COPILOT_SYSTEM_PROMPT
				: DEFAULT_SYSTEM_PROMPT;
		}
		if (provider == AiProvider.GITHUB_COPILOT) {
			return ensureCopilotIntentGuidance(systemPrompt);
		}
		return ensureIntentGuidance(systemPrompt);
	}

	private static String ensureIntentGuidance(String systemPrompt) {
		String normalized = systemPrompt.toLowerCase(Locale.ROOT);
		if (normalized.contains("report_intent")) {
			return systemPrompt;
		}
		return (systemPrompt.strip() + "\n\n" + INTENT_SYSTEM_PROMPT).strip();
	}

	private static String ensureCopilotIntentGuidance(String systemPrompt) {
		String stripped = systemPrompt.strip();
		if (DEFAULT_SYSTEM_PROMPT.equals(stripped) || BASE_SYSTEM_PROMPT.equals(stripped)) {
			return DEFAULT_COPILOT_SYSTEM_PROMPT;
		}
		if (stripped.endsWith(INTENT_SYSTEM_PROMPT)) {
			String base = stripped.substring(0, stripped.length() - INTENT_SYSTEM_PROMPT.length())
				.strip();
			return base.isEmpty()
				? COPILOT_INTENT_SYSTEM_PROMPT
				: (base + "\n\n" + COPILOT_INTENT_SYSTEM_PROMPT).strip();
		}
		String normalized = stripped.toLowerCase(Locale.ROOT);
		if (normalized.contains("current intent") || normalized.contains("intent/status")) {
			return stripped;
		}
		return (stripped + "\n\n" + COPILOT_INTENT_SYSTEM_PROMPT).strip();
	}

	/**
	 * Represents the outcome of attempting to create a chat service.
	 */
	public static final class Result {

		private final ChatService chatService;

		private final String providerDisplayName;

		private final String providerId;

		private final String errorMessage;

		private final CopilotTokenProvider copilotTokenProvider;

		private Result(ChatService chatService, String providerDisplayName, String providerId,
				String errorMessage, CopilotTokenProvider copilotTokenProvider) {
			this.chatService = chatService;
			this.providerDisplayName = providerDisplayName;
			this.providerId = providerId;
			this.errorMessage = errorMessage;
			this.copilotTokenProvider = copilotTokenProvider;
		}

		public static Result success(ChatService chatService, String providerDisplayName, String providerId,
				CopilotTokenProvider copilotTokenProvider) {
			return new Result(
					Objects.requireNonNull(chatService, "chatService"),
					Objects.requireNonNull(providerDisplayName, "providerDisplayName"),
					Objects.requireNonNull(providerId, "providerId"),
					null,
					copilotTokenProvider);
		}

		public static Result failure(String errorMessage) {
			return new Result(null, null, null, Objects.requireNonNull(errorMessage, "errorMessage"), null);
		}

		public boolean isSuccess() {
			return chatService != null;
		}

		public ChatService chatService() {
			return chatService;
		}

		public String providerDisplayName() {
			return providerDisplayName;
		}

		public String providerId() {
			return providerId;
		}

		public String errorMessage() {
			return errorMessage;
		}

		/**
		 * Returns the Copilot token provider if the selected provider is GitHub Copilot,
		 * or null otherwise. Use this to fetch the dynamic model catalog.
		 */
		public CopilotTokenProvider copilotTokenProvider() {
			return copilotTokenProvider;
		}
	}

	private record ClientContext(ChatClient chatClient, ChatModel chatModel, AiProvider provider, String defaultModel,
			String azureDeployment, String azureModel, String systemPrompt, CopilotTokenProvider copilotTokenProvider) {
	}

	private record ProviderModelContext(ChatModel chatModel, String defaultModel, String azureDeployment,
			String azureModel, CopilotTokenProvider copilotTokenProvider) {

		ProviderModelContext(ChatModel chatModel, String defaultModel, String azureDeployment,
				String azureModel) {
			this(chatModel, defaultModel, azureDeployment, azureModel, null);
		}
	}
}
