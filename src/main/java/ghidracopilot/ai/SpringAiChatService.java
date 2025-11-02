package ghidracopilot.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.util.StringUtils;

/**
 * {@link ChatService} implementation backed by a Spring AI {@link ChatClient}.
 */
public class SpringAiChatService implements ChatService {

	private final ChatClient chatClient;
	private final AiProvider provider;
	private final String defaultModel;
	private final String defaultAzureDeployment;
	private final String defaultAzureModel;
	private final String baseSystemPrompt;

	public SpringAiChatService(ChatClient chatClient, AiProvider provider, String defaultModel,
			String defaultAzureDeployment, String defaultAzureModel, String baseSystemPrompt) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		this.provider = Objects.requireNonNull(provider, "provider must not be null");
		this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : null;
		this.defaultAzureDeployment = StringUtils.hasText(defaultAzureDeployment)
				? defaultAzureDeployment.trim()
				: null;
		this.defaultAzureModel = StringUtils.hasText(defaultAzureModel) ? defaultAzureModel.trim() : null;
		this.baseSystemPrompt = StringUtils.hasText(baseSystemPrompt) ? baseSystemPrompt.trim() : null;
	}

	@Override
	public String chat(ChatRequest request) throws ChatServiceException {
		try {
			Objects.requireNonNull(request, "request must not be null");
			List<Message> conversation = buildConversation(request);
			var promptSpec = chatClient.prompt().messages(conversation);
			ChatOptions options = buildOptions(request.modelId());
			if (options != null) {
				promptSpec = promptSpec.options(options);
			}
			return promptSpec.call().content();
		}
		catch (Exception ex) {
			throw new ChatServiceException("Failed to obtain response from AI model", ex);
		}
	}

	private String buildSystemPrompt(String contextual) {
		boolean hasBase = StringUtils.hasText(baseSystemPrompt);
		boolean hasContext = StringUtils.hasText(contextual);
		if (!hasBase && !hasContext) {
			return null;
		}
		if (!hasBase) {
			return contextual.trim();
		}
		if (!hasContext) {
			return baseSystemPrompt;
		}
		return baseSystemPrompt + "\n\n" + contextual.trim();
	}

	private ChatOptions buildOptions(String requestedModel) {
		String modelOverride = StringUtils.hasText(requestedModel) ? requestedModel.trim() : defaultModel;
		return switch (provider) {
			case OPENAI -> buildOpenAiOptions(modelOverride);
			case AZURE_OPENAI -> buildAzureOptions(modelOverride);
			case ANTHROPIC -> buildAnthropicOptions(modelOverride);
			case OLLAMA -> buildOllamaOptions(modelOverride);
		};
	}

	private ChatOptions buildOpenAiOptions(String modelOverride) {
		if (!StringUtils.hasText(modelOverride)) {
			return null;
		}
		return OpenAiChatOptions.builder()
				.model(modelOverride)
				.build();
	}

	private ChatOptions buildAnthropicOptions(String modelOverride) {
		if (!StringUtils.hasText(modelOverride)) {
			return null;
		}
		return AnthropicChatOptions.builder()
				.model(modelOverride)
				.build();
	}

	private ChatOptions buildOllamaOptions(String modelOverride) {
		if (!StringUtils.hasText(modelOverride)) {
			return null;
		}
		return OllamaOptions.builder()
				.model(modelOverride)
				.build();
	}

	private ChatOptions buildAzureOptions(String requestedDeployment) {
		String deployment = StringUtils.hasText(requestedDeployment)
				? requestedDeployment.trim()
				: defaultAzureDeployment;
		if (!StringUtils.hasText(deployment) && !StringUtils.hasText(defaultAzureModel)) {
			return null;
		}
		AzureOpenAiChatOptions options = AzureOpenAiChatOptions.builder()
				.deploymentName(deployment)
				.build();
		if (StringUtils.hasText(defaultAzureModel)) {
			options.setModel(defaultAzureModel);
		}
		return options;
	}

	private List<Message> buildConversation(ChatRequest request) {
		List<Message> messages = new ArrayList<>();
		String resolvedSystemPrompt = buildSystemPrompt(request.systemContext());
		if (StringUtils.hasText(resolvedSystemPrompt)) {
			messages.add(new SystemMessage(resolvedSystemPrompt));
		}
		if (request.history() != null) {
			for (ChatMessage historyMessage : request.history()) {
				if (historyMessage == null || !StringUtils.hasText(historyMessage.content())) {
					continue;
				}
				switch (historyMessage.role()) {
					case SYSTEM -> messages.add(new SystemMessage(historyMessage.content()));
					case USER -> messages.add(new UserMessage(historyMessage.content()));
					case ASSISTANT -> messages.add(new AssistantMessage(historyMessage.content()));
				}
			}
		}
		messages.add(new UserMessage(request.prompt()));
		return messages;
	}
}
