package ghidracopilot.ai;

import java.util.Objects;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
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

	public SpringAiChatService(ChatClient chatClient, AiProvider provider, String defaultModel,
			String defaultAzureDeployment, String defaultAzureModel) {
		this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
		this.provider = Objects.requireNonNull(provider, "provider must not be null");
		this.defaultModel = StringUtils.hasText(defaultModel) ? defaultModel.trim() : null;
		this.defaultAzureDeployment = StringUtils.hasText(defaultAzureDeployment)
				? defaultAzureDeployment.trim()
				: null;
		this.defaultAzureModel = StringUtils.hasText(defaultAzureModel) ? defaultAzureModel.trim() : null;
	}

	@Override
	public String chat(ChatRequest request) throws ChatServiceException {
		try {
			Objects.requireNonNull(request, "request must not be null");
			var prompt = chatClient.prompt().user(request.prompt());
			ChatOptions options = buildOptions(request.modelId());
			if (options != null) {
				prompt = prompt.options(options);
			}
			return prompt.call().content();
		}
		catch (Exception ex) {
			throw new ChatServiceException("Failed to obtain response from AI model", ex);
		}
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
}
