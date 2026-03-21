package ghidracopilot.ai;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.SystemMessageMode;
import com.github.copilot.sdk.events.*;
import com.github.copilot.sdk.json.*;

import ghidra.util.Msg;
import ghidracopilot.ai.tools.CopilotToolRegistry;
import ghidracopilot.ai.tools.IntentionSummariser;
import ghidracopilot.ai.tools.MutationTool;
import ghidracopilot.ai.tools.ReportIntentTool;
import ghidracopilot.ai.tools.ToolResult;

/**
 * {@link ChatService} implementation backed by the official Copilot Java SDK.
 * <p>
 * Launches the {@code copilot} CLI subprocess, handles auth/tokens automatically,
 * and bridges our Ghidra tools to the SDK's tool-call mechanism.
 */
public final class CopilotSdkChatService implements ChatService {

	private final String model;
	private final String systemPrompt;
	private volatile boolean cancelled = false;

	public CopilotSdkChatService(String model, String systemPrompt) {
		this.model = model;
		this.systemPrompt = systemPrompt;
	}

	public void cancel() {
		cancelled = true;
	}

	@Override
	public String chat(ChatRequest request) throws ChatServiceException {
		StringBuilder accumulated = new StringBuilder();
		streamChat(request, new ChatEventListener() {
			@Override public void onDelta(String delta) { accumulated.append(delta); }
			@Override public void onToolCallUpdate(ToolCallUpdate update) {}
			@Override public void onComplete(String fullResponse) {}
			@Override public void onError(String errorMessage) {}
		});
		return accumulated.toString();
	}

	@Override
	public void streamChat(ChatRequest request, ChatEventListener listener) throws ChatServiceException {
		cancelled = false;
		try (CopilotClient client = new CopilotClient()) {
			client.start().get(30, TimeUnit.SECONDS);

			List<ToolDefinition> sdkTools = convertTools();

			String effectiveSystemPrompt = systemPrompt;
			if (request.systemContext() != null && !request.systemContext().isBlank()) {
				effectiveSystemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "")
					+ request.systemContext();
			}

			SessionConfig config = new SessionConfig()
				.setModel(request.modelId() != null ? request.modelId() : model)
				.setStreaming(true)
				.setClientName("ghidra-copilot")
				.setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
				.setTools(sdkTools);

			if (effectiveSystemPrompt != null && !effectiveSystemPrompt.isBlank()) {
				config.setSystemMessage(
					new SystemMessageConfig()
						.setMode(SystemMessageMode.APPEND)
						.setContent(effectiveSystemPrompt));
			}

			CopilotSession session = client.createSession(config).get(30, TimeUnit.SECONDS);

			StringBuilder fullResponse = new StringBuilder();
			// Track toolCallId → toolName for completion events
			Map<String, String> toolCallNames = new HashMap<>();

			session.on(AssistantMessageDeltaEvent.class, event -> {
				String delta = event.getData().deltaContent();
				if (delta != null && !delta.isEmpty()) {
					fullResponse.append(delta);
					listener.onDelta(delta);
				}
			});

			session.on(ToolExecutionStartEvent.class, event -> {
				var data = event.getData();
				String args = data.arguments() != null ? data.arguments().toString() : "{}";
				toolCallNames.put(data.toolCallId(), data.toolName());
				listener.onToolCallUpdate(new ToolCallUpdate(
					data.toolCallId(), data.toolName(), args,
					null, ToolCallUpdate.State.INVOKED, null,
					summariseIntention(data.toolName(), args)));
			});

			session.on(ToolExecutionCompleteEvent.class, event -> {
				var data = event.getData();
				String name = toolCallNames.getOrDefault(data.toolCallId(), "tool");
				String output = null;
				String error = null;
				if (data.result() != null) {
					output = data.result().content();
					if (output == null) output = data.result().detailedContent();
				}
				if (data.error() != null) {
					error = data.error().message();
				}
				listener.onToolCallUpdate(new ToolCallUpdate(
					data.toolCallId(), name, null,
					output,
					data.success() ? ToolCallUpdate.State.COMPLETED : ToolCallUpdate.State.FAILED,
					error));
			});

			session.on(AssistantReasoningDeltaEvent.class, event -> {
				String delta = event.getData().deltaContent();
				if (delta != null && !delta.isEmpty()) {
					listener.onThinking(delta);
				}
			});

			session.on(AssistantIntentEvent.class, event -> {
				String intent = event.getData().intent();
				if (intent != null && !intent.isBlank()) {
					listener.onIntent(intent);
				}
			});

			// Build the prompt — include history context for multi-turn
			String prompt = request.prompt();
			if (!request.history().isEmpty()) {
				StringBuilder contextualPrompt = new StringBuilder();
				for (ChatMessage msg : request.history()) {
					contextualPrompt.append(msg.role() == ChatMessage.Role.USER ? "User: " : "Assistant: ");
					contextualPrompt.append(msg.content()).append("\n\n");
				}
				contextualPrompt.append("User: ").append(prompt);
				prompt = contextualPrompt.toString();
			}

			session.sendAndWait(new MessageOptions().setPrompt(prompt), 300_000)
				.get(6, TimeUnit.MINUTES);

			listener.onComplete(fullResponse.toString());

		} catch (Exception ex) {
			String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			listener.onError(msg);
			throw new ChatServiceException("Copilot SDK error: " + msg, ex);
		}
	}

	/**
	 * Converts our Spring AI {@code @Tool}-annotated tool objects into
	 * Copilot SDK {@link ToolDefinition}s.
	 */
	private List<ToolDefinition> convertTools() {
		List<Object> springTools = CopilotToolRegistry.tools();
		List<ToolDefinition> sdkTools = new ArrayList<>();

		for (Object toolObj : springTools) {
			for (Method method : toolObj.getClass().getDeclaredMethods()) {
				Tool toolAnnotation = method.getAnnotation(Tool.class);
				if (toolAnnotation == null) {
					continue;
				}
				method.setAccessible(true);

				String name = toolAnnotation.name().isEmpty()
					? method.getName() : toolAnnotation.name();
				String description = toolAnnotation.description();

				Map<String, Object> schema = buildJsonSchema(method);

				ToolHandler handler = invocation -> {
					try {
						// Check permission for mutation tools (skip read-only methods)
						if (CopilotToolRegistry.requiresPermission(toolObj, name)) {
							PermissionManager pm = CopilotToolRegistry.permissionManager();
							if (pm != null) {
								String summary = summariseIntention(name,
									invocation.getArguments() != null
										? invocation.getArguments().toString() : null);
								String desc = name + (summary != null ? ": " + summary : "");
								if (!pm.checkPermission(name, desc)) {
									return CompletableFuture.completedFuture(
										"Permission denied by user for " + name);
								}
							}
						}
						Object[] args = resolveArguments(method, invocation);
						Object result = method.invoke(toolObj, args);
						if (result instanceof ToolResult tr) {
							String text = tr.success()
								? (tr.data() != null ? tr.message() + "\n" + tr.data() : tr.message())
								: "ERROR: " + tr.message();
							return CompletableFuture.completedFuture(text);
						}
						return CompletableFuture.completedFuture(
							result != null ? result.toString() : "");
					} catch (Exception ex) {
						Msg.error(this, "Tool '" + name + "' failed: " + ex.getMessage(), ex);
						return CompletableFuture.completedFuture(
							"Error executing " + name + ": " + ex.getMessage());
					}
				};

			boolean isOverride = ReportIntentTool.TOOL_NAME.equals(name);
				if (isOverride) {
					sdkTools.add(ToolDefinition.createOverride(name, description, schema, handler));
				} else {
					sdkTools.add(ToolDefinition.create(name, description, schema, handler));
				}
			}
		}
		return sdkTools;
	}

	/**
	 * Builds a JSON Schema object for the given tool method's parameters.
	 */
	private Map<String, Object> buildJsonSchema(Method method) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();

		for (Parameter param : method.getParameters()) {
			ToolParam tp = param.getAnnotation(ToolParam.class);
			String paramName = param.getName();
			String paramDesc = tp != null ? tp.description() : paramName;
			boolean isRequired = tp != null && tp.required();

			Map<String, Object> paramSchema = new LinkedHashMap<>();
			paramSchema.put("type", jsonType(param.getType()));
			paramSchema.put("description", paramDesc);

			properties.put(paramName, paramSchema);
			if (isRequired) {
				required.add(paramName);
			}
		}

		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		if (!required.isEmpty()) {
			schema.put("required", required);
		}
		return schema;
	}

	private String jsonType(Class<?> type) {
		if (type == String.class) return "string";
		if (type == int.class || type == Integer.class ||
			type == long.class || type == Long.class) return "integer";
		if (type == double.class || type == Double.class ||
			type == float.class || type == Float.class) return "number";
		if (type == boolean.class || type == Boolean.class) return "boolean";
		return "string";
	}

	/**
	 * Resolves method arguments from the tool invocation's argument map.
	 */
	private Object[] resolveArguments(Method method, ToolInvocation invocation) {
		Map<String, Object> args = invocation.getArguments();
		if (args == null) args = Map.of();

		Parameter[] params = method.getParameters();
		Object[] resolved = new Object[params.length];
		for (int i = 0; i < params.length; i++) {
			Object value = args.get(params[i].getName());
			resolved[i] = coerce(value, params[i].getType());
		}
		return resolved;
	}

	private Object coerce(Object value, Class<?> target) {
		if (value == null) return null;
		if (target.isInstance(value)) return value;
		String str = value.toString();
		if (target == String.class) return str;
		if (target == Boolean.class || target == boolean.class) return Boolean.parseBoolean(str);
		if (target == Integer.class || target == int.class) return Integer.parseInt(str);
		if (target == Long.class || target == long.class) return Long.parseLong(str);
		if (target == Double.class || target == double.class) return Double.parseDouble(str);
		return str;
	}

	private String summariseIntention(String toolName, String argsJson) {
		return IntentionSummariser.summarise(toolName, argsJson);
	}
}
