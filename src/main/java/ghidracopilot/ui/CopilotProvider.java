/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidracopilot.ui;

import java.awt.BorderLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import docking.ActionContext;
import docking.ComponentProvider;
import docking.WindowPosition;
import docking.action.DockingAction;
import docking.action.ToolBarData;
import ghidracopilot.ai.ChatMessage;
import ghidracopilot.ai.ChatEventListener;
import ghidracopilot.ai.ChatRequest;
import ghidracopilot.ai.ChatService;
import ghidracopilot.ai.ChatServiceException;
import ghidracopilot.ai.PermissionManager;
import ghidracopilot.ai.SpringAiChatServiceFactory.Result;
import ghidracopilot.ai.ToolCallObserver;
import ghidracopilot.ai.ToolCallUpdate;
import ghidracopilot.ai.ChatServiceCancelledException;
import ghidracopilot.ai.tools.ReportIntentTool;
import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidracopilot.ui.components.ChatInput;
import ghidracopilot.ui.components.ChatMessages;
import ghidracopilot.ui.components.IntentStrip;
import ghidracopilot.ui.context.ContextSnapshotBuilder;
import ghidracopilot.ui.messages.ToolCallMessage;
import ghidracopilot.ui.messages.ToolCallState;
import ghidra.framework.plugintool.Plugin;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.util.Msg;
import org.springframework.util.StringUtils;
import resources.Icons;

/**
 * Dockable provider for Ghidra Copilot chat interface.
 */
public class CopilotProvider extends ComponentProvider {

	private final JPanel panel;
	private final ChatMessages chatMessages;
	private final ChatInput chatInput;
	private final IntentStrip intentStrip;
	private final ProgramPlugin programPlugin;
	private final List<ChatMessage> messageHistory = new ArrayList<>();
	private final PermissionManager permissionManager = new PermissionManager();
	private ChatService chatService;
	private String chatInitializationError;
	private String providerDisplayName;
	private String providerId;
	private DockingAction newChatAction;
	private DockingAction sessionListAction;
	private boolean requestInFlight;
	private SwingWorker<String, Void> activeRequest;
	private boolean stopRequested;
	private ghidracopilot.ai.session.ChatSession currentSession;

	public CopilotProvider(Plugin plugin, String owner) {
		super(plugin.getTool(), "Ghidra Copilot", owner);
		this.programPlugin = plugin instanceof ProgramPlugin pp ? pp : null;

		setTitle("Ghidra Copilot");
		setIcon(Icons.ADD_ICON);
		setDefaultWindowPosition(WindowPosition.RIGHT);

		panel = new JPanel(new BorderLayout());
		panel.setBackground(ghidracopilot.ui.CopilotTheme.chatBackground());
		chatMessages = new ChatMessages();
		chatInput = new ChatInput();
		intentStrip = new IntentStrip();

		// Bottom section: intent strip + input stacked vertically
		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
		bottomPanel.setOpaque(false);
		bottomPanel.add(intentStrip);
		bottomPanel.add(chatInput);

		panel.add(chatMessages, BorderLayout.CENTER);
		panel.add(bottomPanel, BorderLayout.SOUTH);

		chatInput.addSendAction(e -> handleSend());
		chatInput.addStopAction(e -> handleStop());
		chatInput.setInputEnabled(false);

		// Wire permission manager to show prompts in the input area
		permissionManager.setPrompter(request ->
			chatInput.showPermissionPrompt(request.toolName(), request.description()));

		// Escape cancels the active request from anywhere in the panel
		javax.swing.KeyStroke escKey = javax.swing.KeyStroke.getKeyStroke(
			java.awt.event.KeyEvent.VK_ESCAPE, 0);
		panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(escKey, "cancel-request");
		panel.getActionMap().put("cancel-request", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				handleStop();
			}
		});

		buildActions();
	}

	private void buildActions() {
		newChatAction = new DockingAction("New Chat", getOwner()) {
			@Override
			public void actionPerformed(ActionContext context) {
				resetConversation("Started a new chat.");
			}
		};
		newChatAction.setToolBarData(new ToolBarData(Icons.ADD_ICON, null));
		newChatAction.markHelpUnnecessary();
		newChatAction.setEnabled(true);
		addLocalAction(newChatAction);

		sessionListAction = new DockingAction("Session History", getOwner()) {
			@Override
			public void actionPerformed(ActionContext context) {
				showSessionList();
			}
		};
		sessionListAction.setToolBarData(new ToolBarData(Icons.NAVIGATE_ON_INCOMING_EVENT_ICON, null));
		sessionListAction.markHelpUnnecessary();
		sessionListAction.setEnabled(true);
		addLocalAction(sessionListAction);
	}

	public PermissionManager permissionManager() {
		return permissionManager;
	}

	private void resetConversation(String reason) {
		Runnable reset = () -> {
			// Save current session before clearing
			saveCurrentSession();

			chatMessages.clearMessages();
			messageHistory.clear();
			chatInput.clearPrompt();
			currentSession = null;

			if (reason != null && !reason.isBlank()) {
				chatMessages.addSystemMessage(reason);
			}
			if (providerDisplayName != null && !providerDisplayName.isBlank()) {
				chatMessages.addSystemMessage("Connected to provider: **" + providerDisplayName + "**");
			}
			else if (chatInitializationError != null && !chatInitializationError.isBlank()) {
				chatMessages.addSystemMessage("Spring AI is unavailable: " + chatInitializationError);
			}
		};
		if (SwingUtilities.isEventDispatchThread()) {
			reset.run();
		}
		else {
			SwingUtilities.invokeLater(reset);
		}
	}

	private void handleSend() {
		if (requestInFlight) {
			return;
		}
		String prompt = chatInput.getPromptText().trim();
		if (prompt.isEmpty()) {
			return;
		}
		chatInput.clearPrompt();
		sendPrompt(prompt);
	}

	@Override
	public JComponent getComponent() {
		return panel;
	}

	public void sendPrompt(String promptText) {
		if (promptText == null) {
			return;
		}
		String prompt = promptText.trim();
		if (prompt.isEmpty()) {
			return;
		}
		if (requestInFlight) {
			SwingUtilities.invokeLater(() -> chatMessages.addSystemMessage(
				"Finish the active request or stop it before sending another prompt."));
			return;
		}
		SwingUtilities.invokeLater(() -> beginChatRequest(prompt, chatInput.getSelectedModel()));
	}

	public void sendPrompt(String promptText, String preface) {
		if (org.springframework.util.StringUtils.hasText(preface)) {
			addSystemMessage(preface);
		}
		sendPrompt(promptText);
	}

	public void addSystemMessage(String text) {
		if (!StringUtils.hasText(text)) {
			return;
		}
		Runnable add = () -> chatMessages.addSystemMessage(text);
		if (SwingUtilities.isEventDispatchThread()) {
			add.run();
		}
		else {
			SwingUtilities.invokeLater(add);
		}
	}

	private void beginChatRequest(String prompt, ModelEntry selectedModel) {
		if (requestInFlight) {
			return;
		}
		ensureCurrentSession();
		stopRequested = false;
		chatMessages.addUserMessage(prompt);

		String providerName = providerName();
		String providerIdentifier = providerIdentifier();
		if (selectedModel != null && providerIdentifier != null
				&& !selectedModel.provider().id().equals(providerIdentifier)) {
			chatMessages.addAssistantMessage(
				"The model **" + selectedModel.displayName() + "** belongs to " +
					selectedModel.provider().displayName() +
					", but Copilot is currently connected to " + providerName +
					". Update the Copilot settings if you want to use this provider.");
			return;
		}

		if (chatService == null) {
			String reason = (chatInitializationError != null && !chatInitializationError.isBlank())
					? chatInitializationError
					: "Update Tool Options > Ghidra Copilot with your provider credentials.";
			chatMessages.addAssistantMessage("AI provider is not configured. " + reason);
			return;
		}

		String modelIdentifier = selectedModel != null ? selectedModel.identifier() : null;

		requestInFlight = true;
		chatInput.setRequestInProgress(true);
		chatInput.setSendingEnabled(false);

		List<ChatMessage> historySnapshot = List.copyOf(messageHistory);
		Map<String, ToolCallMessage> activeToolMessages = new ConcurrentHashMap<>();
		ToolCallObserver toolCallObserver = update -> SwingUtilities.invokeLater(
			() -> handleToolCallUpdate(update, activeToolMessages));
		ChatMessage userEntry = ChatMessage.user(prompt);
		messageHistory.add(userEntry);

		// Intent strip shows "Thinking" until first delta or tool call
		intentStrip.setThinking();

			// Streaming state — sealed when tool calls interrupt, so next delta creates a new message
			final ghidracopilot.ui.messages.AssistantMessage[] streamingMessage = { null };
			final ghidracopilot.ui.messages.AssistantMessage[] thinkingMessage = { null };
			final boolean[] sealedByToolCall = { false };
			final StringBuilder pendingLeadingWhitespace = new StringBuilder();
			final StringBuilder allAccumulatedText = new StringBuilder();

		// Track mutation tool completions for undo checkpoint
		final java.util.concurrent.atomic.AtomicInteger turnMutationCount =
			new java.util.concurrent.atomic.AtomicInteger(0);
		final java.util.List<String> turnMutationDescriptions =
			java.util.Collections.synchronizedList(new java.util.ArrayList<>());

		ChatEventListener streamListener = new ChatEventListener() {
				@Override
				public void onDelta(String delta) {
					SwingUtilities.invokeLater(() -> {
						if (delta == null || delta.isEmpty()) {
							return;
						}
						boolean creatingNewMessage = streamingMessage[0] == null || sealedByToolCall[0];
						if (creatingNewMessage && delta.isBlank()) {
							pendingLeadingWhitespace.append(delta);
							allAccumulatedText.append(delta);
							return;
						}
						// Seal thinking message when real content starts
						if (thinkingMessage[0] != null) {
							thinkingMessage[0] = null;
						}
						if (creatingNewMessage) {
							streamingMessage[0] = chatMessages.addAssistantMessage("");
							sealedByToolCall[0] = false;
							if (pendingLeadingWhitespace.length() > 0) {
								streamingMessage[0].appendDelta(pendingLeadingWhitespace.toString());
								pendingLeadingWhitespace.setLength(0);
							}
						}
						streamingMessage[0].appendDelta(delta);
						allAccumulatedText.append(delta);
				});
			}

			@Override
			public void onThinking(String delta) {
				SwingUtilities.invokeLater(() -> {
					if (thinkingMessage[0] == null) {
						thinkingMessage[0] = chatMessages.addThinkingContentMessage("");
					}
					thinkingMessage[0].appendDelta(delta);
				});
			}

			@Override
			public void onIntent(String intent) {
				SwingUtilities.invokeLater(() -> {
					if (intent != null && !intent.isBlank()) {
						// Msg.debug(this, "[CopilotProvider] onIntent callback: '" + intent.trim() + "'");
						intentStrip.setIntent(intent.trim());
					}
				});
			}

			@Override
			public void onToolCallUpdate(ToolCallUpdate update) {
				SwingUtilities.invokeLater(() -> {
					sealedByToolCall[0] = true;
					handleToolCallUpdate(update, activeToolMessages);

					// Track completed mutation tools for undo checkpoint
					if (update.state() == ghidracopilot.ai.ToolCallUpdate.State.COMPLETED
							&& ghidracopilot.ai.tools.CopilotToolRegistry
								.requiresPermissionByName(update.toolName())) {
						turnMutationCount.incrementAndGet();
						turnMutationDescriptions.add(
							ToolCallMessage.displayNameFor(update.toolName()));
					}
				});
			}

			@Override
			public void onUsage(int promptTokens, int completionTokens) {
				SwingUtilities.invokeLater(() ->
					chatInput.updateUsage(promptTokens, completionTokens));
			}

			@Override
			public void onComplete(String fullResponse) {
				// Completion handled in SwingWorker.done()
			}

			@Override
			public void onError(String errorMessage) {
				// Errors handled in SwingWorker.done()
			}
		};

		activeRequest = new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				String contextualSystemPrompt = ContextSnapshotBuilder.build(programPlugin, null);
				ChatRequest chatRequest = new ChatRequest(
					prompt, modelIdentifier, contextualSystemPrompt, historySnapshot, toolCallObserver);
				chatService.streamChat(chatRequest, streamListener);
				return null;
			}

			@Override
			protected void done() {
				requestInFlight = false;
				activeRequest = null;
				chatInput.setRequestInProgress(false);
				chatInput.setSendingEnabled(true);
				intentStrip.setIdle();
				if (streamingMessage[0] != null) {
					streamingMessage[0].flush();
				}
				if (thinkingMessage[0] != null) {
					thinkingMessage[0].flush();
				}
				if (isCancelled() || stopRequested) {
					handleCancellation(userEntry);
					return;
				}
					try {
						get();
						if (streamingMessage[0] != null &&
							streamingMessage[0].getAccumulatedText().isBlank()) {
							chatMessages.removeMessage(streamingMessage[0]);
							streamingMessage[0] = null;
						}
						String fullText = allAccumulatedText.toString();
						if (fullText.isBlank() && streamingMessage[0] == null) {
							chatMessages.addAssistantMessage(
								"(The AI model did not return any content.)");
					}
					else if (!fullText.isBlank()) {
						messageHistory.add(ChatMessage.assistant(fullText));
					}

					// Add undo checkpoint if this turn had mutations
					int mutations = turnMutationCount.get();
					if (mutations > 0 && programPlugin != null
							&& programPlugin.getCurrentProgram() != null) {
						chatMessages.addUndoCheckpoint(
							mutations,
							new ArrayList<>(turnMutationDescriptions),
							programPlugin.getCurrentProgram());
					}

					saveCurrentSession();
				}
				catch (Exception ex) {
					if (stopRequested || isCancellation(ex)) {
						handleCancellation(userEntry);
						return;
					}
					String message = extractErrorMessage(ex);
					chatMessages.addAssistantMessage("I ran into a problem talking to " +
						providerName + ": " + message);
					messageHistory.remove(userEntry);
					Msg.error(getClass(), "Spring AI chat request failed", ex);
				}
			}
		};

		activeRequest.execute();
	}

	public void applyConfiguration(Result result) {
		if (result == null) {
			return;
		}
		SwingUtilities.invokeLater(() -> applyConfigurationOnEdt(result));
	}

	private void applyConfigurationOnEdt(Result result) {
		boolean previouslyConfigured = chatService != null;
		String previousProviderId = providerId;
		String previousError = chatInitializationError;

		requestInFlight = false;

		if (result.isSuccess()) {
			chatService = result.chatService();
			providerDisplayName = result.providerDisplayName();
			providerId = result.providerId();
			chatInitializationError = null;
			chatInput.setInputEnabled(true);

			if (!previouslyConfigured || !Objects.equals(previousProviderId, providerId)
					|| previousError != null) {
				chatMessages.addSystemMessage(
					"Connected to provider: **" + providerDisplayName + "**");
				messageHistory.clear();
			}
		}
		else {
			chatService = null;
			providerDisplayName = null;
			providerId = null;
			chatInitializationError = result.errorMessage();
			chatInput.setInputEnabled(false);
			if (chatInitializationError != null
					&& !chatInitializationError.isBlank()
					&& !Objects.equals(previousError, chatInitializationError)) {
				chatMessages.addSystemMessage(
					"Spring AI is unavailable: " + chatInitializationError);
			}
		}
	}

	public void updateModelCatalog(List<ModelEntry> entries, String defaultModelKey) {
		if (entries == null) {
			return;
		}
		SwingUtilities.invokeLater(() -> chatInput.setModelEntries(entries, defaultModelKey));
	}

	/**
	 * Connect the report_intent tool to this provider's intent strip.
	 */
	public void wireIntentTool() {
		ReportIntentTool tool = ghidracopilot.ai.tools.CopilotToolRegistry.reportIntentTool();
		if (tool != null) {
			tool.setIntentListener(intent ->
				SwingUtilities.invokeLater(() -> intentStrip.setIntent(intent)));
		}
	}

	private void handleToolCallUpdate(ToolCallUpdate update, Map<String, ToolCallMessage> registry) {
		if (update == null || !StringUtils.hasText(update.id())) {
			return;
		}

		// Suppress report_intent from the transcript — route to intent strip
		if (ReportIntentTool.TOOL_NAME.equals(update.toolName())) {
			if (StringUtils.hasText(update.intentionSummary())) {
				intentStrip.setIntent(update.intentionSummary());
			}
			return;
		}

		String callId = update.id();
		String arguments = StringUtils.hasText(update.argumentsJson()) ? update.argumentsJson() : "{}";

		// Msg.debug(this, "ToolCallUpdate [" + update.state() + "] " + update.toolName()
		// 	+ " id=" + callId
		// 	+ " args=" + (update.argumentsJson() != null ? update.argumentsJson().length() + " chars" : "null")
		// 	+ " output=" + (update.outputJson() != null ? update.outputJson().length() + " chars" : "null")
		// 	+ " error=" + (update.errorMessage() != null ? "yes" : "no"));

		ToolCallMessage message = registry.computeIfAbsent(callId,
			key -> chatMessages.addToolCallMessage(update.toolName(), arguments,
				update.intentionSummary()));

		if (StringUtils.hasText(update.intentionSummary())) {
			message.setIntentionSummary(update.intentionSummary());
		}

		switch (update.state()) {
			case INVOKED -> message.setState(ToolCallState.INVOKED);
			case IN_PROGRESS -> message.setState(ToolCallState.IN_PROGRESS);
			case COMPLETED -> {
				String output = update.outputJson();
				if (output != null && !output.isEmpty()) {
					message.setOutputJson(output);
				}
				message.setState(ToolCallState.COMPLETED);
				registry.remove(callId);
			}
			case FAILED -> {
				String output = update.outputJson();
				if (output != null && !output.isEmpty()) {
					message.setOutputJson(output);
				}
				if (StringUtils.hasText(update.errorMessage())) {
					message.setErrorMessage(update.errorMessage());
				}
				message.setState(ToolCallState.FAILED);
				registry.remove(callId);
			}
		}
	}

	private String extractErrorMessage(Exception ex) {
		Throwable cause = ex;
		if (cause instanceof java.util.concurrent.ExecutionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof ChatServiceCancelledException || cause instanceof InterruptedException) {
			return "Request was cancelled.";
		}
		if (cause instanceof ChatServiceException && cause.getMessage() != null) {
			return cause.getMessage();
		}
		if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
			return cause.getMessage();
		}
		return "Unexpected error (see log for details).";
	}

	private void handleStop() {
		if (!requestInFlight) {
			return;
		}
		stopRequested = true;
		SwingWorker<String, Void> worker = activeRequest;
		if (worker != null) {
			worker.cancel(true);
		}
	}

	private void handleCancellation(ChatMessage userEntry) {
		messageHistory.remove(userEntry);
		chatMessages.addSystemMessage("Stopped the request.");
		stopRequested = false;
	}

	private boolean isCancellation(Throwable ex) {
		Throwable cursor = ex;
		while (cursor != null) {
			if (cursor instanceof java.util.concurrent.CancellationException ||
				cursor instanceof InterruptedException ||
				cursor instanceof ChatServiceCancelledException) {
				return true;
			}
			cursor = cursor.getCause();
		}
		return false;
	}

	private String providerName() {
		return providerDisplayName != null ? providerDisplayName : "Spring AI";
	}

	private String providerIdentifier() {
		return providerId != null ? providerId : "unknown";
	}

	// ── Session persistence ──────────────────────────────────────────────

	private void ensureCurrentSession() {
		if (currentSession == null) {
			String progName = null;
			if (programPlugin != null && programPlugin.getCurrentProgram() != null) {
				progName = programPlugin.getCurrentProgram().getName();
			}
			ModelEntry model = chatInput.getSelectedModel();
			String modelId = model != null ? model.identifier() : null;
			currentSession = new ghidracopilot.ai.session.ChatSession(
				providerIdentifier(), modelId, progName);
		}
	}

	private void saveCurrentSession() {
		if (currentSession == null || messageHistory.isEmpty()) {
			return;
		}
		currentSession.setMessages(new ArrayList<>(messageHistory));
		currentSession.setProviderId(providerIdentifier());
		ModelEntry model = chatInput.getSelectedModel();
		if (model != null) {
			currentSession.setModelId(model.identifier());
		}
		ghidracopilot.ai.session.SessionStorage.save(currentSession);
	}

	private void loadSession(String sessionId) {
		ghidracopilot.ai.session.ChatSession session =
			ghidracopilot.ai.session.SessionStorage.load(sessionId);
		if (session == null) {
			chatMessages.addSystemMessage("Failed to load session.");
			return;
		}
		chatMessages.clearMessages();
		messageHistory.clear();
		currentSession = session;

		for (ChatMessage msg : session.getMessages()) {
			switch (msg.role()) {
				case USER -> chatMessages.addUserMessage(msg.content());
				case ASSISTANT -> chatMessages.addAssistantMessage(msg.content());
				case SYSTEM -> chatMessages.addSystemMessage(msg.content());
			}
			messageHistory.add(msg);
		}
		chatMessages.addSystemMessage("Restored session from " + formatTime(session.getUpdatedAt()));
	}

	private void showSessionList() {
		List<ghidracopilot.ai.session.SessionStorage.SessionSummary> sessions =
			ghidracopilot.ai.session.SessionStorage.listSessions();

		if (sessions.isEmpty()) {
			chatMessages.addSystemMessage("No saved sessions.");
			return;
		}

		javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
		int shown = 0;
		for (var summary : sessions) {
			if (shown >= 20) break;
			// Skip the current session
			if (currentSession != null && summary.id().equals(currentSession.getId())) {
				continue;
			}
			String label = truncateTitle(summary.title(), 40)
				+ "  (" + formatTime(summary.updatedAt()) + ")";
			javax.swing.JMenuItem item = new javax.swing.JMenuItem(label);
			item.addActionListener(e -> loadSession(summary.id()));
			popup.add(item);
			shown++;
		}

		if (shown == 0) {
			chatMessages.addSystemMessage("No other saved sessions.");
			return;
		}

		// Show popup below the toolbar
		java.awt.Component comp = panel;
		popup.show(comp, 0, 0);
	}

	private static String truncateTitle(String title, int max) {
		if (title == null) return "Untitled";
		if (title.length() <= max) return title;
		return title.substring(0, max - 3) + "...";
	}

	private static String formatTime(long epochMillis) {
		java.time.LocalDateTime dt = java.time.Instant.ofEpochMilli(epochMillis)
			.atZone(java.time.ZoneId.systemDefault())
			.toLocalDateTime();
		java.time.LocalDate today = java.time.LocalDate.now();
		if (dt.toLocalDate().equals(today)) {
			return dt.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
		}
		return dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"));
	}
}
