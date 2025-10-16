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

import java.util.List;
import java.util.Objects;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import docking.ActionContext;
import docking.ComponentProvider;
import docking.WindowPosition;
import docking.action.DockingAction;
import docking.action.ToolBarData;
import ghidracopilot.ai.ChatRequest;
import ghidracopilot.ai.ChatService;
import ghidracopilot.ai.ChatServiceException;
import ghidracopilot.ai.SpringAiChatServiceFactory.Result;
import ghidracopilot.ui.components.ChatHeader;
import ghidracopilot.ui.components.ChatInput;
import ghidracopilot.ui.components.ChatMessages;
import ghidracopilot.ui.messages.ReasoningMessage;
import ghidracopilot.ui.messages.ToolCallMessage;
import ghidracopilot.ui.messages.ToolCallState;
import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidra.framework.plugintool.Plugin;
import ghidra.util.Msg;
import resources.Icons;

/**
 * Dockable provider for Ghidra Copilot chat interface.
 */
public class CopilotProvider extends ComponentProvider {

	private final JPanel panel;
	private final ChatHeader chatHeader;
	private final ChatMessages chatMessages;
	private final ChatInput chatInput;
	private ChatService chatService;
	private String chatInitializationError;
	private String providerDisplayName;
	private String providerId;
	private DockingAction sampleAction;

	public CopilotProvider(Plugin plugin, String owner) {
		super(plugin.getTool(), "Ghidra Copilot", owner);

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
		String modelDisplayName = selectedModel != null ? selectedModel.displayName() : null;
		String modelKey = selectedModel != null ? selectedModel.key() : null;

		String reasoningText = "_Contacting " + providerName +
			(modelDisplayName != null ? " — " + modelDisplayName : "") + "…_";
		ReasoningMessage reasoningMessage = chatMessages.addReasoningMessage(reasoningText);

		String toolPayload = buildToolPayload(providerName, providerIdentifier, modelIdentifier, modelDisplayName,
			modelKey, prompt);
		ToolCallMessage toolMessage = chatMessages.addToolCallMessage(
			"spring-ai:" + providerIdentifier,
			toolPayload);
		toolMessage.setState(ToolCallState.IN_PROGRESS);

		chatInput.setInputEnabled(false);

		ChatRequest chatRequest = new ChatRequest(prompt, modelIdentifier);

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
					toolMessage.setState(ToolCallState.COMPLETED);
					toolMessage.setOutputJson(
						"{\"response\":\"" + escapeJson(truncate(response, 512)) + "\"}");
					if (response.isEmpty()) {
						reasoningMessage.setMarkdown(
							"_No content returned by " + providerName + "._");
						chatMessages.addAssistantMessage(
							"(The AI model did not return any content.)");
					}
					else {
						reasoningMessage.setMarkdown(
							"_Response received from " + providerName + "._");
						chatMessages.addAssistantMessage(response);
					}
				}
				catch (Exception ex) {
					String message = extractErrorMessage(ex);
					toolMessage.setState(ToolCallState.FAILED);
					toolMessage.setErrorMessage(message);
					reasoningMessage.setMarkdown(
						"_Encountered an error while contacting " + providerName + "._");
					chatMessages.addAssistantMessage("I ran into a problem talking to " +
						providerName + ": " + message);
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
				chatMessages.addAssistantMessage(
					"Connected to **" + providerDisplayName + "** via Spring AI.");
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
				chatMessages.addAssistantMessage(
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

	private String escapeJson(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\r", "\\r")
				.replace("\n", "\\n");
	}

	private String truncate(String text, int maxLength) {
		if (text == null) {
			return "";
		}
		if (text.length() <= maxLength) {
			return text;
		}
		return text.substring(0, Math.max(0, maxLength - 3)) + "...";
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

	private String buildToolPayload(String providerName, String providerIdentifier, String modelIdentifier,
			String modelDisplayName, String modelKey, String prompt) {
		StringBuilder builder = new StringBuilder("{\"provider\":\"")
				.append(escapeJson(providerName))
				.append("\",\"providerId\":\"")
				.append(escapeJson(providerIdentifier))
				.append("\"");
		if (modelIdentifier != null) {
			builder.append(",\"model\":\"").append(escapeJson(modelIdentifier)).append("\"");
		}
		if (modelDisplayName != null) {
			builder.append(",\"modelDisplayName\":\"").append(escapeJson(modelDisplayName)).append("\"");
		}
		if (modelKey != null) {
			builder.append(",\"modelKey\":\"").append(escapeJson(modelKey)).append("\"");
		}
		builder.append(",\"prompt\":\"").append(escapeJson(prompt)).append("\"}");
		return builder.toString();
	}
}
