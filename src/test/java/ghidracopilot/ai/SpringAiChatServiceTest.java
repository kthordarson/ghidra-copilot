package ghidracopilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
	 import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.definition.ToolDefinition;

class SpringAiChatServiceTest {

	private static final String TOOL_ID = "call-1";
	private static final String TOOL_NAME = "annotate";
	private static final String TOOL_ARGUMENTS = "{\"address\":\"0xdeadbeef\"}";
	private static final String TOOL_OUTPUT = "{\"status\":\"ok\"}";

	private RecordingChatModel chatModel;
	private RecordingToolCallingManager toolCallingManager;
	private SpringAiChatService chatService;

	@BeforeEach
	void setUp() throws Exception {
		chatModel = new RecordingChatModel(buildToolCallResponse(), buildFinalResponse());
		chatService = new SpringAiChatService(
			null,
			chatModel,
			AiProvider.OPENAI,
			"gpt-4o-mini",
			null,
			null,
			"You are Ghidra Copilot.");

		toolCallingManager = new RecordingToolCallingManager();
		Field managerField = SpringAiChatService.class.getDeclaredField("toolCallingManager");
		managerField.setAccessible(true);
		managerField.set(chatService, toolCallingManager);
	}

	@Test
	void toolCallLifecycleNotifiesObserverAndYieldsFinalResponse() throws Exception {
		List<ToolCallUpdate> updates = new ArrayList<>();
		ChatRequest request = new ChatRequest(
			"Describe the function at 0xdeadbeef",
			null,
			null,
			List.of(),
			updates::add,
			InteractionMode.AGENT);

		String finalResponse = chatService.chat(request);

		assertEquals("Tool output consumed.", finalResponse);

		assertEquals(2, chatModel.prompts().size(), "Chat model should have been invoked twice");
		assertTrue(toolCallingManager.executed, "Tool manager should have executed the tool call");
		assertFalse(toolCallingManager.returnDirect, "Execution should not request direct return");
		assertTrue(toolCallingManager.latestHistory.stream().anyMatch(ToolResponseMessage.class::isInstance),
			"Conversation history should include a ToolResponseMessage");

		List<ToolCallUpdate.State> observedStates = updates.stream()
				.map(ToolCallUpdate::state)
				.toList();
		assertIterableEquals(
			List.of(ToolCallUpdate.State.INVOKED, ToolCallUpdate.State.IN_PROGRESS, ToolCallUpdate.State.COMPLETED),
			observedStates,
			"Observer should receive the expected lifecycle states");

		ToolCallUpdate completion = updates.get(updates.size() - 1);
		assertEquals(TOOL_ID, completion.id());
		assertEquals(TOOL_NAME, completion.toolName());
		assertEquals(TOOL_ARGUMENTS, completion.argumentsJson());
		assertEquals(TOOL_OUTPUT, completion.outputJson());
		assertEquals(ToolCallUpdate.State.COMPLETED, completion.state());
	}

	private ChatResponse buildToolCallResponse() {
		AssistantMessage.ToolCall toolCall =
			new AssistantMessage.ToolCall(TOOL_ID, "function", TOOL_NAME, TOOL_ARGUMENTS);
		AssistantMessage assistant = new AssistantMessage(
			"Let me inspect the binary.",
			Map.of(),
			List.of(toolCall));
		return ChatResponse.builder()
				.generations(List.of(new Generation(assistant)))
				.build();
	}

	private ChatResponse buildFinalResponse() {
		AssistantMessage assistant = new AssistantMessage("Tool output consumed.", Map.of());
		return ChatResponse.builder()
				.generations(List.of(new Generation(assistant)))
				.build();
	}

	private static final class RecordingChatModel implements ChatModel {

		private final List<ChatResponse> scriptedResponses;
		private final List<Prompt> prompts = new ArrayList<>();
		private int invocationIndex = 0;

		private RecordingChatModel(ChatResponse... responses) {
			this.scriptedResponses = List.of(responses);
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			prompts.add(prompt);
			if (invocationIndex >= scriptedResponses.size()) {
				return scriptedResponses.get(scriptedResponses.size() - 1);
			}
			return scriptedResponses.get(invocationIndex++);
		}

		@Override
		public ChatOptions getDefaultOptions() {
			return null;
		}

		@Override
		public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
			throw new UnsupportedOperationException("Streaming not supported in test stub");
		}

		public List<Prompt> prompts() {
			return prompts;
		}
	}

	private final class RecordingToolCallingManager implements ToolCallingManager {

		private boolean executed;
		private boolean returnDirect;
		private List<org.springframework.ai.chat.messages.Message> latestHistory = List.of();

		@Override
		public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
			return List.of();
		}

		@Override
		public org.springframework.ai.model.tool.ToolExecutionResult executeToolCalls(
				Prompt prompt, ChatResponse chatResponse) {

			executed = true;
			var history = new ArrayList<>(prompt.getInstructions());
			var response = new ToolResponseMessage.ToolResponse(TOOL_ID, TOOL_NAME, TOOL_OUTPUT);
			history.add(new ToolResponseMessage(List.of(response)));

			latestHistory = List.copyOf(history);
			var result = org.springframework.ai.model.tool.DefaultToolExecutionResult.builder()
					.conversationHistory(history)
					.returnDirect(false)
					.build();
			returnDirect = result.returnDirect();
			return result;
		}
	}
}
