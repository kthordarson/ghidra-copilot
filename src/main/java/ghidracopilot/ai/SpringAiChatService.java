package ghidracopilot.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.util.StringUtils;

import ghidracopilot.ai.tools.CopilotToolRegistry;

/**
 * {@link ChatService} implementation backed by a Spring AI {@link ChatModel}.
 */
public class SpringAiChatService implements ChatService {

	private final ChatModel chatModel;
	private final ToolCallingManager toolCallingManager;
	private final AiProvider provider;
	private final String defaultModel;
	private final String defaultAzureDeployment;
	private final String defaultAzureModel;
	private final String baseSystemPrompt;

	public SpringAiChatService(ChatClient chatClient, ChatModel chatModel, AiProvider provider, String defaultModel,
			String defaultAzureDeployment, String defaultAzureModel, String baseSystemPrompt) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
		this.toolCallingManager = ToolCallingManager.builder().build();
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
			ToolCallingChatOptions toolOptions =
				prepareToolOptions(buildOptions(request.modelId()), request.interactionMode());
			Prompt prompt = new Prompt(buildConversation(request), toolOptions);
			ChatResponse chatResponse = chatModel.call(prompt);

			while (chatResponse != null && chatResponse.hasToolCalls()) {
				AssistantMessage assistantMessage = chatResponse.getResult() != null
						? chatResponse.getResult().getOutput()
						: null;

				if (assistantMessage == null || assistantMessage.getToolCalls().isEmpty()) {
					break;
				}

				notifyInvoked(request, assistantMessage);
				notifyInProgress(request, assistantMessage);

				ToolExecutionResult executionResult;
				try {
					executionResult = toolCallingManager.executeToolCalls(prompt, chatResponse);
				}
				catch (Exception ex) {
					notifyFailure(request, assistantMessage, ex);
					throw new ChatServiceException(ex.getMessage() != null
							? ex.getMessage()
							: "Tool execution failed.", ex);
				}

				notifyCompletion(request, assistantMessage, executionResult);

				prompt = new Prompt(executionResult.conversationHistory(), toolOptions);
				chatResponse = chatModel.call(prompt);
			}

			return extractAssistantText(chatResponse);
		}
		catch (ChatServiceException ex) {
			throw ex;
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

	private ToolCallingChatOptions prepareToolOptions(ChatOptions baseOptions, InteractionMode mode) {
		ToolCallingChatOptions toolOptions;
		if (baseOptions instanceof ToolCallingChatOptions tcOptions) {
			toolOptions = tcOptions;
		}
		else {
			toolOptions = DefaultToolCallingChatOptions.builder().build();
		}

		toolOptions.setInternalToolExecutionEnabled(Boolean.FALSE);
		var existingCallbacks = toolOptions.getToolCallbacks();
		var registeredTools = CopilotToolRegistry.toolsForMode(mode);
		boolean overrideCallbacks = existingCallbacks == null || existingCallbacks.isEmpty() ||
			(mode != null && mode.isReadOnly());
		if (overrideCallbacks) {
			if (registeredTools != null && !registeredTools.isEmpty()) {
				var callbacks = ToolCallbacks.from(registeredTools.toArray());
				toolOptions.setToolCallbacks(Arrays.asList(callbacks));
			}
			else {
				toolOptions.setToolCallbacks(List.of());
			}
		}
		return toolOptions;
	}

	private void notifyInvoked(ChatRequest request, AssistantMessage assistantMessage) {
		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			request.toolCallObserver()
					.onToolCallUpdate(new ToolCallUpdate(
						call.id(),
						call.name(),
						normalizeJson(call.arguments()),
						null,
						ToolCallUpdate.State.INVOKED,
						null));
		}
	}

	private void notifyInProgress(ChatRequest request, AssistantMessage assistantMessage) {
		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			request.toolCallObserver()
					.onToolCallUpdate(new ToolCallUpdate(
						call.id(),
						call.name(),
						normalizeJson(call.arguments()),
						null,
						ToolCallUpdate.State.IN_PROGRESS,
						null));
		}
	}

	private void notifyCompletion(ChatRequest request, AssistantMessage assistantMessage,
			ToolExecutionResult executionResult) {

		var responsesById = new java.util.HashMap<String, org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse>();
		ToolResponseMessage toolResponse = executionResult.conversationHistory().stream()
				.filter(ToolResponseMessage.class::isInstance)
				.map(ToolResponseMessage.class::cast)
				.reduce((first, second) -> second)
				.orElse(null);
		if (toolResponse != null) {
			for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
				responsesById.put(response.id(), response);
			}
		}

		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			ToolResponseMessage.ToolResponse response = responsesById.get(call.id());
			String output = response != null ? response.responseData() : null;
			request.toolCallObserver()
					.onToolCallUpdate(new ToolCallUpdate(
						call.id(),
						call.name(),
						normalizeJson(call.arguments()),
						output,
						ToolCallUpdate.State.COMPLETED,
						null));
		}
	}

	private void notifyFailure(ChatRequest request, AssistantMessage assistantMessage, Exception ex) {
		String message = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "Tool execution failed.";
		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			request.toolCallObserver()
					.onToolCallUpdate(new ToolCallUpdate(
						call.id(),
						call.name(),
						normalizeJson(call.arguments()),
						null,
						ToolCallUpdate.State.FAILED,
						message));
		}
	}

	private String extractAssistantText(ChatResponse chatResponse) {
		if (chatResponse == null || chatResponse.getResult() == null) {
			return "";
		}
		AssistantMessage message = chatResponse.getResult().getOutput();
		if (message == null || !StringUtils.hasText(message.getText())) {
			return "";
		}
		return message.getText().trim();
	}

	private String normalizeJson(String json) {
		if (!StringUtils.hasText(json)) {
			return "{}";
		}
		return json.trim();
	}
}
