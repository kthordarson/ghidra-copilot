package ghidracopilot.ai;

import java.io.Closeable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.SystemMessageMode;
import com.github.copilot.sdk.events.*;
import com.github.copilot.sdk.json.*;

import ghidra.util.Msg;
import ghidracopilot.ai.tools.CopilotToolRegistry;
import ghidracopilot.ai.tools.IntentionSummariser;
import ghidracopilot.ai.tools.ReportIntentTool;
import ghidracopilot.ai.tools.ToolResult;

/**
 * {@link ChatService} implementation backed by the official Copilot Java SDK.
 * <p>
 * Launches the {@code copilot} CLI subprocess, handles auth/tokens automatically,
 * and bridges our Ghidra tools to the SDK's tool-call mechanism.
 * <p>
 * The SDK session is kept alive across messages so the model maintains
 * its own conversation context natively — no need to replay history.
 */
public final class CopilotSdkChatService implements ChatService {

	private final String model;
	private final String systemPrompt;
	private volatile boolean cancelled = false;

	// Persistent session state — reused across messages
	private CopilotClient client;
	private CopilotSession session;
	private boolean sessionReady;
	private String sessionModel; // model the current session was created with
	private final Object sessionLock = new Object();

	public CopilotSdkChatService(String model, String systemPrompt) {
		this.model = model;
		this.systemPrompt = systemPrompt;
	}

	public void cancel() {
		cancelled = true;
	}

	/** Tear down the persistent session and subprocess. */
	public void close() {
		synchronized (sessionLock) {
			sessionReady = false;
			sessionModel = null;
			if (client != null) {
				try { client.close(); } catch (Exception ignored) {}
				client = null;
				session = null;
			}
		}
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
		List<Closeable> listenerRegistrations = List.of();
		try {
			CopilotSession activeSession = ensureSession(request);

			StringBuilder fullResponse = new StringBuilder();
			Map<String, String> toolCallNames = new HashMap<>();
			String[] lastIntent = { null };

			listenerRegistrations = registerSessionListeners(activeSession, listener, fullResponse,
				toolCallNames, lastIntent);

			// With a persistent session the SDK tracks context — just send the new prompt
			activeSession.sendAndWait(new MessageOptions().setPrompt(request.prompt()), 300_000)
				.get(6, TimeUnit.MINUTES);

			listener.onComplete(fullResponse.toString());

		} catch (Exception ex) {
			// Session may be stale — tear it down so next call creates a fresh one
			close();
			String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
			listener.onError(msg);
			throw new ChatServiceException("Copilot SDK error: " + msg, ex);
		} finally {
			closeListenerRegistrations(listenerRegistrations);
		}
	}

	/**
	 * Returns the persistent SDK session, creating it on first call.
	 * If history is provided on the very first message (e.g. restored from disk),
	 * it is prepended as context text.
	 */
	private CopilotSession ensureSession(ChatRequest request) throws Exception {
		synchronized (sessionLock) {
			String requestedModel = request.modelId() != null ? request.modelId() : model;

			// If the model changed mid-session, tear down so we recreate with the new model.
			// The SDK binds model at session creation; per-message overrides aren't supported.
			if (sessionReady && session != null) {
				if (requestedModel != null && !requestedModel.equals(sessionModel)) {
					Msg.info(this, "Model changed from '" + sessionModel + "' to '" +
						requestedModel + "' — recreating Copilot SDK session");
					close();
				} else {
					return session;
				}
			}

			// Start fresh client + session
			if (client != null) {
				try { client.close(); } catch (Exception ignored) {}
			}

			client = new CopilotClient();
			client.start().get(30, TimeUnit.SECONDS);

			List<ToolDefinition> sdkTools = convertTools();

			String effectiveSystemPrompt = systemPrompt;
			if (request.systemContext() != null && !request.systemContext().isBlank()) {
				effectiveSystemPrompt = (systemPrompt != null ? systemPrompt + "\n\n" : "")
					+ request.systemContext();
			}

			SessionConfig config = new SessionConfig()
				.setModel(requestedModel)
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

			session = client.createSession(config).get(30, TimeUnit.SECONDS);
			sessionModel = requestedModel;
			sessionReady = true;

			// If restoring from disk, seed the session with prior context
			if (!request.history().isEmpty()) {
				StringBuilder contextSeed = new StringBuilder("Prior conversation context:\n\n");
				for (ChatMessage msg : request.history()) {
					contextSeed.append(msg.role() == ChatMessage.Role.USER ? "User: " : "Assistant: ");
					contextSeed.append(msg.content()).append("\n\n");
				}
				// Send as a silent context message — the SDK will remember it
				session.send(new MessageOptions().setPrompt(contextSeed.toString()))
					.get(2, TimeUnit.MINUTES);
			}

			return session;
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

				// Skip report_intent — the Copilot SDK has it built-in
				// and fires AssistantIntentEvent instead
				if (ReportIntentTool.TOOL_NAME.equals(name)) {
					continue;
				}

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
										ToolResult.error("Permission denied by user for " + name).toJson());
								}
							}
						}
						Object[] args = resolveArguments(method, invocation);
						Object result = method.invoke(toolObj, args);
						if (result instanceof ToolResult tr) {
							return CompletableFuture.completedFuture(tr.toJson());
						}
						return CompletableFuture.completedFuture(
							result != null ? result.toString() : "");
					} catch (Exception ex) {
						Msg.error(this, "Tool '" + name + "' failed: " + ex.getMessage(), ex);
						return CompletableFuture.completedFuture(
							ToolResult.error("Error executing " + name + ": " + ex.getMessage()).toJson());
					}
				};

			sdkTools.add(ToolDefinition.create(name, description, schema, handler));
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

	private List<Closeable> registerSessionListeners(CopilotSession activeSession,
			ChatEventListener listener, StringBuilder fullResponse,
			Map<String, String> toolCallNames, String[] lastIntent) {
		List<Closeable> registrations = new ArrayList<>();

		registrations.add(activeSession.on(AssistantMessageDeltaEvent.class, event -> {
			String delta = event.getData().deltaContent();
			if (delta != null && !delta.isEmpty()) {
				fullResponse.append(delta);
				listener.onDelta(delta);
			}
		}));

		registrations.add(activeSession.on(AssistantMessageEvent.class, event -> {
			String intent = extractReportIntent(event.getData());
			emitIntent(listener, lastIntent, intent, "assistant.tool_request");
		}));

		registrations.add(activeSession.on(ToolExecutionStartEvent.class, event -> {
			var data = event.getData();
			String args = data.arguments() != null ? data.arguments().toString() : "{}";
			toolCallNames.put(data.toolCallId(), data.toolName());
			listener.onToolCallUpdate(new ToolCallUpdate(
				data.toolCallId(), data.toolName(), args,
				null, ToolCallUpdate.State.INVOKED, null,
				summariseIntention(data.toolName(), args)));
		}));

		registrations.add(activeSession.on(ToolExecutionCompleteEvent.class, event -> {
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
			ToolResult parsed = ToolResult.tryParse(output);
			ToolCallUpdate.State state = data.success()
				? ToolCallUpdate.State.COMPLETED
				: ToolCallUpdate.State.FAILED;
			if (parsed != null && !parsed.success()) {
				state = ToolCallUpdate.State.FAILED;
				if (error == null || error.isBlank()) {
					error = parsed.errorMessage();
				}
			}
			listener.onToolCallUpdate(new ToolCallUpdate(
				data.toolCallId(), name, null,
				output,
				state,
				error));
		}));

		registrations.add(activeSession.on(AssistantReasoningDeltaEvent.class, event -> {
			String delta = event.getData().deltaContent();
			if (delta != null && !delta.isEmpty()) {
				listener.onThinking(delta);
			}
		}));

		registrations.add(activeSession.on(AssistantIntentEvent.class, event -> {
			String intent = event.getData().intent();
			emitIntent(listener, lastIntent, intent, "assistant.intent");
		}));

		return registrations;
	}

	private void closeListenerRegistrations(List<Closeable> listenerRegistrations) {
		for (Closeable registration : listenerRegistrations) {
			if (registration == null) {
				continue;
			}
			try {
				registration.close();
			} catch (Exception ex) {
				Msg.debug(this, "[CopilotSdkChatService] Failed to close listener registration", ex);
			}
		}
	}

	static String extractReportIntent(AssistantMessageEvent.AssistantMessageData data) {
		if (data == null || data.toolRequests() == null) {
			return null;
		}
		for (AssistantMessageEvent.AssistantMessageData.ToolRequest toolRequest : data.toolRequests()) {
			if (toolRequest == null || !ReportIntentTool.TOOL_NAME.equals(toolRequest.name())) {
				continue;
			}
			String intent = extractIntentArgument(toolRequest.arguments());
			if (intent != null) {
				return intent;
			}
		}
		return null;
	}

	private static String extractIntentArgument(Object arguments) {
		if (arguments == null) {
			return null;
		}
		if (arguments instanceof Map<?, ?> map) {
			Object intent = map.get("intent");
			return normalizeIntent(intent != null ? intent.toString() : null);
		}
		if (arguments instanceof JsonNode node) {
			return node.hasNonNull("intent") ? normalizeIntent(node.get("intent").asText()) : null;
		}
		return null;
	}

	private void emitIntent(ChatEventListener listener, String[] lastIntent, String intent,
			String source) {
		String normalized = normalizeIntent(intent);
		if (normalized == null || normalized.equals(lastIntent[0])) {
			return;
		}
		lastIntent[0] = normalized;
		Msg.debug(this, "[CopilotSdkChatService] " + source + ": '" + normalized + "'");
		listener.onIntent(normalized);
	}

	private static String normalizeIntent(String intent) {
		if (intent == null) {
			return null;
		}
		String normalized = intent.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
