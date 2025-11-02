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
import ghidracopilot.ai.ChatRequest;
import ghidracopilot.ai.ChatService;
import ghidracopilot.ai.ChatServiceException;
import ghidracopilot.ai.SpringAiChatServiceFactory.Result;
import ghidracopilot.ai.ToolCallObserver;
import ghidracopilot.ai.ToolCallUpdate;
import ghidracopilot.ui.components.ChatHeader;
import ghidracopilot.ui.components.ChatInput;
import ghidracopilot.ui.components.ChatMessages;
import ghidracopilot.ui.messages.SystemMessage;
import ghidracopilot.ui.messages.ToolCallMessage;
import ghidracopilot.ui.messages.ToolCallState;
import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidra.framework.plugintool.Plugin;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.CodeViewerService;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;
import ghidra.util.Msg;
import org.springframework.util.StringUtils;
import resources.Icons;

/**
 * Dockable provider for Ghidra Copilot chat interface.
 */
public class CopilotProvider extends ComponentProvider {

	private final JPanel panel;
	private final ChatHeader chatHeader;
	private final ChatMessages chatMessages;
	private final ChatInput chatInput;
	private final ProgramPlugin programPlugin;
	private final List<ChatMessage> messageHistory = new ArrayList<>();
	private ChatService chatService;
	private String chatInitializationError;
	private String providerDisplayName;
	private String providerId;
	private DockingAction sampleAction;

	public CopilotProvider(Plugin plugin, String owner) {
		super(plugin.getTool(), "Ghidra Copilot", owner);
		this.programPlugin = plugin instanceof ProgramPlugin pp ? pp : null;

		setTitle("Ghidra Copilot");
		setIcon(Icons.ADD_ICON);
		setDefaultWindowPosition(WindowPosition.RIGHT);

		panel = new JPanel(new BorderLayout());
		chatHeader = new ChatHeader();
		chatMessages = new ChatMessages();
		chatInput = new ChatInput();

		panel.add(chatHeader, BorderLayout.NORTH);
		panel.add(chatMessages, BorderLayout.CENTER);
		panel.add(chatInput, BorderLayout.SOUTH);

		chatInput.addSendAction(e -> handleSend());
		chatInput.setInputEnabled(false);

		buildActions();
	}

	private void buildActions() {
		sampleAction = new DockingAction("Copilot Hello", getOwner()) {
			@Override
			public void actionPerformed(ActionContext context) {
				Msg.showInfo(getClass(), panel, "Copilot", "Chat action placeholder");
			}
		};
		sampleAction.setToolBarData(new ToolBarData(Icons.ADD_ICON, null));
		sampleAction.markHelpUnnecessary();
		sampleAction.setEnabled(true);
		addLocalAction(sampleAction);
	}

	private void handleSend() {
		String prompt = chatInput.getPromptText().trim();
		if (prompt.isEmpty()) {
			return;
		}
		chatMessages.addUserMessage(prompt);
		chatInput.clearPrompt();

		ModelEntry selectedModel = chatInput.getSelectedModel();
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

		chatInput.setInputEnabled(false);

		List<ChatMessage> historySnapshot = List.copyOf(messageHistory);
		String contextualSystemPrompt = buildDynamicSystemContext();
		Map<String, ToolCallMessage> activeToolMessages = new ConcurrentHashMap<>();
		ToolCallObserver toolCallObserver = update -> SwingUtilities.invokeLater(
			() -> handleToolCallUpdate(update, activeToolMessages));
		ChatRequest chatRequest =
			new ChatRequest(prompt, modelIdentifier, contextualSystemPrompt, historySnapshot, toolCallObserver);
		ChatMessage userEntry = ChatMessage.user(prompt);
		messageHistory.add(userEntry);

		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return chatService.chat(chatRequest);
			}

			@Override
			protected void done() {
				chatInput.setInputEnabled(true);
				try {
					String response = get();
					response = response != null ? response.trim() : "";
					if (response.isEmpty()) {
						chatMessages.addAssistantMessage(
							"(The AI model did not return any content.)");
					}
					else {
						chatMessages.addAssistantMessage(response);
						messageHistory.add(ChatMessage.assistant(response));
					}
				}
				catch (Exception ex) {
					String message = extractErrorMessage(ex);
					chatMessages.addAssistantMessage("I ran into a problem talking to " +
						providerName + ": " + message);
					messageHistory.remove(userEntry);
					Msg.error(getClass(), "Spring AI chat request failed", ex);
				}
			}
		}.execute();
	}

	@Override
	public JComponent getComponent() {
		return panel;
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

		if (result.isSuccess()) {
			chatService = result.chatService();
			providerDisplayName = result.providerDisplayName();
			providerId = result.providerId();
			chatInitializationError = null;
			chatInput.setInputEnabled(true);

			if (!previouslyConfigured || !Objects.equals(previousProviderId, providerId)
					|| previousError != null) {
				chatMessages.addSystemMessage(
					"Connected to **" + providerDisplayName + "** via Spring AI.");
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

	private void handleToolCallUpdate(ToolCallUpdate update, Map<String, ToolCallMessage> registry) {
		if (update == null || !StringUtils.hasText(update.id())) {
			return;
		}

		String callId = update.id();
		String arguments = StringUtils.hasText(update.argumentsJson()) ? update.argumentsJson() : "{}";

		ToolCallMessage message = registry.computeIfAbsent(callId,
			key -> chatMessages.addToolCallMessage(update.toolName(), arguments));

		switch (update.state()) {
			case INVOKED -> message.setState(ToolCallState.INVOKED);
			case IN_PROGRESS -> message.setState(ToolCallState.IN_PROGRESS);
			case COMPLETED -> {
				if (StringUtils.hasText(update.outputJson())) {
					message.setOutputJson(update.outputJson());
				}
				message.setState(ToolCallState.COMPLETED);
				registry.remove(callId);
			}
			case FAILED -> {
				if (StringUtils.hasText(update.outputJson())) {
					message.setOutputJson(update.outputJson());
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
		if (cause instanceof ChatServiceException && cause.getMessage() != null) {
			return cause.getMessage();
		}
		if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
			return cause.getMessage();
		}
		return "Unexpected error (see log for details).";
	}

	private String providerName() {
		return providerDisplayName != null ? providerDisplayName : "Spring AI";
	}

	private String providerIdentifier() {
		return providerId != null ? providerId : "unknown";
	}

	private String buildDynamicSystemContext() {
		if (programPlugin == null) {
			return null;
		}
		Program program = programPlugin.getCurrentProgram();
		if (program == null) {
			return "Current context: no program is active.";
		}

		StringBuilder builder = new StringBuilder("Current Ghidra context:\n");
		builder.append("- Program: ").append(program.getName());
		String executablePath = program.getExecutablePath();
		if (executablePath != null && !executablePath.isBlank()) {
			builder.append(" (").append(executablePath).append(")");
		}
		builder.append('\n');

		CodeViewerService codeViewer = programPlugin.getTool().getService(CodeViewerService.class);
		ProgramLocation location = codeViewer != null ? codeViewer.getCurrentLocation() : null;
		Address address = location != null ? location.getAddress() : null;
		if (address != null) {
			builder.append("- Address: ").append(address).append('\n');
			FunctionManager functionManager = program.getFunctionManager();
			Function function = functionManager != null ? functionManager.getFunctionContaining(address) : null;
			if (function != null) {
				builder.append("- Function: ").append(function.getName())
					.append(" @ ").append(function.getEntryPoint()).append('\n');
			}
		}
		else if (location != null) {
			builder.append("- Location: ").append(location).append('\n');
		}

		ProgramSelection selection = codeViewer != null ? codeViewer.getCurrentSelection() : null;
		if (selection != null && !selection.isEmpty()) {
			builder.append("- Selection size: ")
				.append(selection.getNumAddresses())
				.append(" addresses\n");
		}

		return builder.toString().trim();
	}
}
