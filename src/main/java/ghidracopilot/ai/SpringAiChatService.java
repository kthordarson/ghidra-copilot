package ghidracopilot.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.util.StringUtils;

import ghidracopilot.ai.tools.CopilotToolRegistry;
import ghidracopilot.ai.tools.IntentionSummariser;
import ghidracopilot.ai.tools.MutationTool;
import ghidracopilot.ai.tools.ToolResult;

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
			checkCancelled();
			ToolCallingChatOptions toolOptions =
				prepareToolOptions(buildOptions(request.modelId()));
			Prompt prompt = new Prompt(buildConversation(request), toolOptions);
			checkCancelled();
			ChatResponse chatResponse = callWithRetry(prompt);
			checkCancelled();

			while (chatResponse != null && chatResponse.hasToolCalls()) {
				checkCancelled();
				AssistantMessage assistantMessage = chatResponse.getResult() != null
						? chatResponse.getResult().getOutput()
						: null;

				if (assistantMessage == null || assistantMessage.getToolCalls().isEmpty()) {
					break;
				}

				notifyInvoked(request, assistantMessage);
				notifyInProgress(request, assistantMessage);

				ToolExecutionResult executionResult = executeToolsWithRecovery(prompt, chatResponse, request,
						assistantMessage);

				prompt = new Prompt(executionResult.conversationHistory(), toolOptions);
				checkCancelled();
				chatResponse = callWithRetry(prompt);
				checkCancelled();
			}

			checkCancelled();
			return extractAssistantText(chatResponse);
		}
		catch (ChatServiceException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new ChatServiceException("Failed to obtain response from AI model", ex);
		}
	}

	@Override
	public void streamChat(ChatRequest request, ChatEventListener listener) throws ChatServiceException {
		try {
			Objects.requireNonNull(request, "request must not be null");
			Objects.requireNonNull(listener, "listener must not be null");
			checkCancelled();

			ToolCallingChatOptions toolOptions =
				prepareToolOptions(buildOptions(request.modelId()));
			List<Message> conversation = buildConversation(request);
			Prompt prompt = new Prompt(conversation, toolOptions);

			StringBuilder fullContent = new StringBuilder();
			boolean toolLooping = true;

			while (toolLooping) {
				checkCancelled();

				// Stream the model response, emitting content deltas as they arrive
				AtomicReference<ChatResponse> lastChunk = new AtomicReference<>();
				StringBuilder turnContent = new StringBuilder();

				try {
					streamWithRetry(prompt)
						.doOnNext(chunk -> {
							lastChunk.set(chunk);
							if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
								String text = chunk.getResult().getOutput().getText();
								if (text != null && !text.isEmpty()) {
									turnContent.append(text);
									listener.onDelta(text);
								}
							}
						})
						.blockLast();
				}
				catch (Exception ex) {
					if (isCancelException(ex)) {
						throw new ChatServiceCancelledException("Chat request was cancelled.", ex);
					}
					throw ex;
				}

				checkCancelled();
				fullContent.append(turnContent);

				// Extract usage from the last chunk if available
				ChatResponse finalChunk = lastChunk.get();
				if (finalChunk != null && finalChunk.getMetadata() != null
						&& finalChunk.getMetadata().getUsage() != null) {
					var usage = finalChunk.getMetadata().getUsage();
					listener.onUsage(
						(int) usage.getPromptTokens(),
						(int) usage.getCompletionTokens());
				}

				// Check if the model wants tool calls
				ChatResponse completeResponse = finalChunk;

				if (completeResponse != null && completeResponse.hasToolCalls()) {
					AssistantMessage assistantMessage = completeResponse.getResult() != null
							? completeResponse.getResult().getOutput()
							: null;
					if (assistantMessage != null && !assistantMessage.getToolCalls().isEmpty()) {
						// Notify UI about tool calls
						notifyInvoked(request, assistantMessage);
						notifyInProgress(request, assistantMessage);

						// Execute tools with error recovery
						ToolExecutionResult executionResult = executeToolsWithRecovery(
								prompt, completeResponse, request, assistantMessage);

						notifyCompletion(request, assistantMessage, executionResult);

						// Continue the loop with updated conversation
						prompt = new Prompt(executionResult.conversationHistory(), toolOptions);
						continue;
					}
				}

				// No tool calls — we're done
				toolLooping = false;
			}

			listener.onComplete(fullContent.toString());
		}
		catch (ChatServiceCancelledException ex) {
			throw ex;
		}
		catch (ChatServiceException ex) {
			listener.onError(ex.getMessage());
			throw ex;
		}
		catch (Exception ex) {
			String message = "Failed to obtain response from AI model";
			listener.onError(ex.getMessage() != null ? ex.getMessage() : message);
			throw new ChatServiceException(message, ex);
		}
	}

	/**
	 * Execute tool calls with error recovery. Instead of throwing on tool failures,
	 * feeds the error back to the model so it can adapt.
	 */
	private ToolExecutionResult executeToolsWithRecovery(Prompt prompt, ChatResponse chatResponse,
			ChatRequest request, AssistantMessage assistantMessage) throws ChatServiceException {
		// Check permissions for mutation tools before executing
		ToolExecutionResult denied = checkToolPermissions(prompt, assistantMessage);
		if (denied != null) {
			return denied;
		}
		try {
			checkCancelled();
			return toolCallingManager.executeToolCalls(prompt, chatResponse);
		}
		catch (Exception ex) {
			checkCancelled();
			// Instead of killing the conversation, build a synthetic tool response
			// with the error message so the model can see what went wrong and adapt.
			notifyFailure(request, assistantMessage, ex);

			String errorMsg = ex.getMessage() != null ? ex.getMessage() : "Tool execution failed.";
			List<Message> recoveryHistory = new ArrayList<>(prompt.getInstructions());
			recoveryHistory.add(assistantMessage);

			List<ToolResponseMessage.ToolResponse> errorResponses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
				errorResponses.add(new ToolResponseMessage.ToolResponse(
					call.id(),
					call.name(),
					ToolResult.error(errorMsg).toJson()));
			}
			recoveryHistory.add(new ToolResponseMessage(errorResponses));

			return DefaultToolExecutionResult.builder()
				.conversationHistory(recoveryHistory)
				.returnDirect(false)
				.build();
		}
	}

	/**
	 * Checks permissions for any mutation tools in the tool call batch.
	 * Returns a synthetic denied result if any mutation tool is denied, or null to proceed.
	 */
	private ToolExecutionResult checkToolPermissions(Prompt prompt, AssistantMessage assistantMessage) {
		PermissionManager pm = CopilotToolRegistry.permissionManager();
		if (pm == null) {
			return null;
		}
		List<ToolResponseMessage.ToolResponse> deniedResponses = new ArrayList<>();
		boolean anyDenied = false;

		for (AssistantMessage.ToolCall call : assistantMessage.getToolCalls()) {
			if (CopilotToolRegistry.requiresPermissionByName(call.name())) {
				String summary = summariseIntention(call.name(), call.arguments());
				String desc = call.name() + (summary != null ? ": " + summary : "");
				if (!pm.checkPermission(call.name(), desc)) {
					deniedResponses.add(new ToolResponseMessage.ToolResponse(
						call.id(), call.name(),
						ToolResult.error("Permission denied by user for " + call.name()).toJson()));
					anyDenied = true;
				}
			}
		}

		if (!anyDenied) {
			return null;
		}

		// Build a response with denied results for mutation tools
		List<Message> history = new ArrayList<>(prompt.getInstructions());
		history.add(assistantMessage);
		history.add(new ToolResponseMessage(deniedResponses));

		return DefaultToolExecutionResult.builder()
			.conversationHistory(history)
			.returnDirect(false)
			.build();
	}

	private boolean isCancelException(Throwable ex) {
		Throwable cursor = ex;
		while (cursor != null) {
			if (cursor instanceof InterruptedException ||
				cursor instanceof ChatServiceCancelledException ||
				cursor instanceof java.util.concurrent.CancellationException) {
				return true;
			}
			cursor = cursor.getCause();
		}
		return false;
	}

	// --- API retry with exponential backoff ---

	private static final int MAX_RETRIES = 3;
	private static final long INITIAL_BACKOFF_MS = 1000;

	private ChatResponse callWithRetry(Prompt prompt) throws Exception {
		Exception lastException = null;
		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				checkCancelled();
				return chatModel.call(prompt);
			}
			catch (Exception ex) {
				if (isCancelException(ex) || !isRetryable(ex)) {
					throw ex;
				}
				lastException = ex;
				if (attempt < MAX_RETRIES - 1) {
					long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
					Thread.sleep(backoff);
				}
			}
		}
		throw lastException;
	}

	private reactor.core.publisher.Flux<ChatResponse> streamWithRetry(Prompt prompt) throws Exception {
		Exception lastException = null;
		for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
			try {
				checkCancelled();
				return chatModel.stream(prompt);
			}
			catch (Exception ex) {
				if (isCancelException(ex) || !isRetryable(ex)) {
					throw ex;
				}
				lastException = ex;
				if (attempt < MAX_RETRIES - 1) {
					long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
					Thread.sleep(backoff);
				}
			}
		}
		throw lastException;
	}

	private static boolean isRetryable(Throwable ex) {
		String message = ex.getMessage();
		if (message == null) {
			return false;
		}
		String lower = message.toLowerCase();
		// Retry on 5xx, 429 rate limits, timeouts, and connection errors
		if (lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests")) {
			return true;
		}
		if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504")) {
			return true;
		}
		if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connection reset")
				|| lower.contains("connection refused")) {
			return true;
		}
		// Do NOT retry 4xx auth/validation errors
		return false;
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
			case OPENAI, GITHUB_COPILOT -> buildOpenAiOptions(modelOverride);
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

	private ToolCallingChatOptions prepareToolOptions(ChatOptions baseOptions) {
		ToolCallingChatOptions toolOptions;
		if (baseOptions instanceof ToolCallingChatOptions tcOptions) {
			toolOptions = tcOptions;
		}
		else {
			toolOptions = DefaultToolCallingChatOptions.builder().build();
		}

		toolOptions.setInternalToolExecutionEnabled(Boolean.FALSE);
		var existingCallbacks = toolOptions.getToolCallbacks();
		var registeredTools = CopilotToolRegistry.tools();
		boolean overrideCallbacks = existingCallbacks == null || existingCallbacks.isEmpty();
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
						null,
						summariseIntention(call.name(), call.arguments())));
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
						null,
						summariseIntention(call.name(), call.arguments())));
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
			ToolResult parsed = ToolResult.tryParse(output);
			ToolCallUpdate.State state = ToolCallUpdate.State.COMPLETED;
			String errorMessage = null;
			if (parsed != null && !parsed.success()) {
				state = ToolCallUpdate.State.FAILED;
				errorMessage = parsed.errorMessage();
			}
			request.toolCallObserver()
					.onToolCallUpdate(new ToolCallUpdate(
						call.id(),
						call.name(),
						normalizeJson(call.arguments()),
						output,
						state,
						errorMessage,
						summariseIntention(call.name(), call.arguments())));
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
						message,
						summariseIntention(call.name(), call.arguments())));
		}
	}

	/**
	 * Derive a short human-readable summary of why a tool was called,
	 * similar to Copilot CLI's summariseIntention pattern.
	 */
	private String summariseIntention(String toolName, String argsJson) {
		return IntentionSummariser.summarise(toolName, argsJson);
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
		String s = json.trim();
		// Spring AI's ToolCall.arguments() may return Map.toString() format
		// e.g. {addressText=100000f38, length=64} instead of valid JSON.
		// Detect and convert: valid JSON has quoted keys, Map.toString() uses '='.
		if (s.startsWith("{") && !s.startsWith("{\"") && s.contains("=")) {
			String inner = s.substring(1, s.length() - 1).trim();
			if (inner.isEmpty()) return "{}";
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (String pair : inner.split(",\\s*")) {
				int eq = pair.indexOf('=');
				if (eq < 0) continue;
				String key = pair.substring(0, eq).trim();
				String val = pair.substring(eq + 1).trim();
				if (!first) sb.append(',');
				sb.append('"').append(key).append("\":\"")
				  .append(val.replace("\\", "\\\\").replace("\"", "\\\""))
				  .append('"');
				first = false;
			}
			sb.append('}');
			return sb.toString();
		}
		return s;
	}

	private void checkCancelled() throws ChatServiceCancelledException {
		if (Thread.currentThread().isInterrupted()) {
			throw new ChatServiceCancelledException(
				"Chat request was cancelled.",
				new InterruptedException("Chat request interrupted."));
		}
	}
}
