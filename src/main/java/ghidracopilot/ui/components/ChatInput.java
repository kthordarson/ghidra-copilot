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
package ghidracopilot.ui.components;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import ghidracopilot.ai.PermissionManager;
import ghidracopilot.model.ModelRegistry.ModelEntry;

/**
 * Input area for composing chat requests to Copilot.
 */
public class ChatInput extends JPanel {

	private static final int MIN_ROWS = 1;
	private static final int MAX_ROWS = 6;
	private static final String CARD_PROMPT = "prompt";
	private static final String CARD_PERMISSION = "permission";

	private final JTextArea promptField;
	private final JScrollPane promptScroll;
	private final JButton sendButton;
	private final JComboBox<ModelItem> modelCombo;
	private final ModelComboBoxModel modelComboModel;
	private final JLabel usageLabel;
	private final CardLayout cardLayout;
	private final JPanel cardPanel;
	private final JPanel promptCard;
	private boolean requestInProgress;
	private boolean sendEnabled = true;
	private ActionListener sendAction;
	private ActionListener stopAction;

	private final List<String> promptHistory = new ArrayList<>();
	private int historyIndex = -1;
	private String draftText = "";

	// Permission prompt state
	private JPanel permissionCard;
	private CompletableFuture<PermissionManager.Decision> pendingDecision;
	private int selectedOption;
	private List<PermissionOption> permissionOptions;

	public ChatInput() {
		super(new BorderLayout());
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0,
				ghidracopilot.ui.CopilotTheme.codeBorder()),
			new EmptyBorder(8, 10, 10, 10)));

		promptField = new JTextArea(MIN_ROWS, 0);
		promptField.setLineWrap(true);
		promptField.setWrapStyleWord(true);

		promptScroll = new JScrollPane(promptField);
		promptScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
		promptScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		promptScroll.setBorder(BorderFactory.createEmptyBorder());

		// Enter sends the message; Shift+Enter inserts a newline
		promptField.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
		promptField.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK),
			"insert-break");
		promptField.getActionMap().put("send-message", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (sendAction != null && sendEnabled && !requestInProgress) {
					sendAction.actionPerformed(e);
				}
			}
		});

		// Escape cancels the active request
		promptField.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-request");
		promptField.getActionMap().put("cancel-request", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (requestInProgress && stopAction != null) {
					stopAction.actionPerformed(e);
				}
			}
		});

		// Up arrow recalls previous prompt (only when field is empty or at start)
		promptField.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "history-up");
		promptField.getActionMap().put("history-up", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (promptHistory.isEmpty()) return;
				if (promptField.getCaretPosition() > 0) return;
				if (historyIndex < 0) {
					draftText = promptField.getText();
					historyIndex = promptHistory.size() - 1;
				}
				else if (historyIndex > 0) {
					historyIndex--;
				}
				promptField.setText(promptHistory.get(historyIndex));
				promptField.setCaretPosition(0);
			}
		});

		// Down arrow goes forward in history
		promptField.getInputMap().put(
			KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "history-down");
		promptField.getActionMap().put("history-down", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (historyIndex < 0) return;
				if (historyIndex < promptHistory.size() - 1) {
					historyIndex++;
					promptField.setText(promptHistory.get(historyIndex));
				}
				else {
					historyIndex = -1;
					promptField.setText(draftText);
				}
			}
		});

		// Auto-grow the text area as the user types
		promptField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { adjustHeight(); }
			@Override public void removeUpdate(DocumentEvent e) { adjustHeight(); }
			@Override public void changedUpdate(DocumentEvent e) { adjustHeight(); }
		});

		sendButton = new JButton("Send");
		modelComboModel = new ModelComboBoxModel();
		modelCombo = new JComboBox<>(modelComboModel);
		modelCombo.setRenderer(new ModelItemRenderer());
		modelCombo.setPrototypeDisplayValue(ModelItem.prototype());
		modelCombo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);

		JPanel promptRow = new JPanel(new BorderLayout(8, 0));
		promptRow.setOpaque(false);
		promptRow.add(promptScroll, BorderLayout.CENTER);
		promptRow.add(sendButton, BorderLayout.EAST);

		JPanel controlsRow = new JPanel(new BorderLayout());
		controlsRow.setOpaque(false);
		JPanel leftControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		leftControls.setOpaque(false);
		leftControls.add(modelCombo);
		usageLabel = new JLabel("");
		usageLabel.setForeground(ghidracopilot.ui.CopilotTheme.systemText());
		usageLabel.setFont(usageLabel.getFont().deriveFont(usageLabel.getFont().getSize2D() - 1f));
		controlsRow.add(leftControls, BorderLayout.WEST);
		controlsRow.add(usageLabel, BorderLayout.EAST);
		controlsRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		promptCard = new JPanel();
		promptCard.setLayout(new BoxLayout(promptCard, BoxLayout.Y_AXIS));
		promptCard.setOpaque(false);
		promptCard.add(promptRow);
		promptCard.add(Box.createVerticalStrut(0));
		promptCard.add(controlsRow);

		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		cardPanel.setOpaque(false);
		cardPanel.add(promptCard, CARD_PROMPT);

		add(cardPanel, BorderLayout.CENTER);
	}

	// ---- Permission prompt ----

	/**
	 * Show a permission prompt that replaces the chat input.
	 * The user picks an option with up/down + enter or click.
	 * Returns a future that completes with their decision.
	 */
	public CompletableFuture<PermissionManager.Decision> showPermissionPrompt(
			String toolName, String description) {
		if (pendingDecision != null && !pendingDecision.isDone()) {
			pendingDecision.complete(PermissionManager.Decision.DENY);
		}

		pendingDecision = new CompletableFuture<>();
		selectedOption = 0;
		permissionOptions = List.of(
			new PermissionOption("Allow once", PermissionManager.Decision.ALLOW_ONCE),
			new PermissionOption("Always allow " + toolName, PermissionManager.Decision.ALLOW_TOOL),
			new PermissionOption("Allow all write operations", PermissionManager.Decision.ALLOW_ALL),
			new PermissionOption("Deny", PermissionManager.Decision.DENY)
		);

		// Build permission panel
		if (permissionCard != null) {
			cardPanel.remove(permissionCard);
		}
		permissionCard = buildPermissionPanel(toolName, description);
		cardPanel.add(permissionCard, CARD_PERMISSION);
		cardLayout.show(cardPanel, CARD_PERMISSION);
		permissionCard.requestFocusInWindow();
		revalidate();
		repaint();

		pendingDecision.whenComplete((d, ex) -> {
			javax.swing.SwingUtilities.invokeLater(() -> {
				cardLayout.show(cardPanel, CARD_PROMPT);
				promptField.requestFocusInWindow();
				revalidate();
				repaint();
			});
		});

		return pendingDecision;
	}

	private JPanel buildPermissionPanel(String toolName, String description) {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setOpaque(false);

		// Header: what the tool wants to do
		String headerText = description != null && !description.isBlank()
			? description
			: toolName + " requires write permission";
		JLabel header = new JLabel("\u25CF " + headerText);
		header.setForeground(ghidracopilot.ui.CopilotTheme.stateInProgress());
		header.setFont(header.getFont().deriveFont(Font.BOLD));
		panel.add(header, BorderLayout.NORTH);

		// Options list
		JPanel optionsPanel = new JPanel();
		optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
		optionsPanel.setOpaque(false);
		optionsPanel.setBorder(new EmptyBorder(0, 4, 0, 0));

		List<JLabel> optionLabels = new ArrayList<>();
		for (int i = 0; i < permissionOptions.size(); i++) {
			PermissionOption opt = permissionOptions.get(i);
			JLabel label = new JLabel((i == selectedOption ? "❯ " : "  ") + opt.label);
			label.setForeground(i == selectedOption
				? ghidracopilot.ui.CopilotTheme.chevronColor()
				: ghidracopilot.ui.CopilotTheme.assistantText());
			label.setFont(label.getFont().deriveFont(
				i == selectedOption ? Font.BOLD : Font.PLAIN));
			label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			label.setBorder(new EmptyBorder(2, 0, 2, 0));

			final int idx = i;
			label.addMouseListener(new java.awt.event.MouseAdapter() {
				@Override
				public void mouseClicked(java.awt.event.MouseEvent e) {
					completePermission(permissionOptions.get(idx).decision);
				}
			});
			optionLabels.add(label);
			optionsPanel.add(label);
		}
		panel.add(optionsPanel, BorderLayout.CENTER);

		// Key bindings on the panel itself
		panel.setFocusable(true);
		panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "perm-up");
		panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "perm-down");
		panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "perm-select");
		panel.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "perm-deny");

		panel.getActionMap().put("perm-up", new AbstractAction() {
			@Override public void actionPerformed(java.awt.event.ActionEvent e) {
				if (selectedOption > 0) {
					selectedOption--;
					updateOptionHighlights(optionLabels);
				}
			}
		});
		panel.getActionMap().put("perm-down", new AbstractAction() {
			@Override public void actionPerformed(java.awt.event.ActionEvent e) {
				if (selectedOption < permissionOptions.size() - 1) {
					selectedOption++;
					updateOptionHighlights(optionLabels);
				}
			}
		});
		panel.getActionMap().put("perm-select", new AbstractAction() {
			@Override public void actionPerformed(java.awt.event.ActionEvent e) {
				completePermission(permissionOptions.get(selectedOption).decision);
			}
		});
		panel.getActionMap().put("perm-deny", new AbstractAction() {
			@Override public void actionPerformed(java.awt.event.ActionEvent e) {
				completePermission(PermissionManager.Decision.DENY);
			}
		});

		return panel;
	}

	private void updateOptionHighlights(List<JLabel> labels) {
		for (int i = 0; i < labels.size(); i++) {
			JLabel lbl = labels.get(i);
			boolean selected = (i == selectedOption);
			lbl.setText((selected ? "❯ " : "  ") + permissionOptions.get(i).label);
			lbl.setForeground(selected
				? ghidracopilot.ui.CopilotTheme.chevronColor()
				: ghidracopilot.ui.CopilotTheme.assistantText());
			lbl.setFont(lbl.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
		}
	}

	private void completePermission(PermissionManager.Decision decision) {
		CompletableFuture<PermissionManager.Decision> future = pendingDecision;
		if (future != null && !future.isDone()) {
			future.complete(decision);
		}
	}

	private record PermissionOption(String label, PermissionManager.Decision decision) {}

	// ---- Normal input methods ----

	private void adjustHeight() {
		int lineCount = promptField.getLineCount();
		int rows = Math.max(MIN_ROWS, Math.min(lineCount, MAX_ROWS));
		promptField.setRows(rows);
		revalidate();
	}

	public void addSendAction(ActionListener listener) {
		if (listener == null) {
			return;
		}
		sendAction = listener;
		sendButton.addActionListener(listener);
	}

	public void addStopAction(ActionListener listener) {
		stopAction = listener;
		if (requestInProgress) {
			updateButtonState();
		}
	}

	public String getPromptText() {
		return promptField.getText();
	}

	public ModelEntry getSelectedModel() {
		return modelComboModel.getSelectedEntry();
	}

	public void setModelEntries(List<ModelEntry> entries, String defaultModelKey) {
		modelComboModel.setEntries(entries != null ? entries : List.of(), defaultModelKey);
		updateComboEnabledState();
	}

	public void clearPrompt() {
		String text = promptField.getText().trim();
		if (!text.isEmpty()) {
			promptHistory.add(text);
		}
		historyIndex = -1;
		draftText = "";
		promptField.setText("");
	}

	/**
	 * Update the token usage display in the controls row.
	 */
	public void updateUsage(int promptTokens, int completionTokens) {
		int total = promptTokens + completionTokens;
		String display = total >= 1000
				? String.format("%.1fk tokens", total / 1000.0)
				: total + " tokens";
		usageLabel.setText(display);
	}

	public void setInputEnabled(boolean enabled) {
		promptField.setEnabled(enabled);
		sendEnabled = enabled;
		updateButtonState();
		updateComboEnabledState();
	}

	public void setSendingEnabled(boolean enabled) {
		sendEnabled = enabled;
		if (!requestInProgress) {
			updateButtonState();
		}
	}

	public void setRequestInProgress(boolean inProgress) {
		requestInProgress = inProgress;
		updateButtonState();
	}

	private void updateComboEnabledState() {
		boolean enableCombo = promptField.isEnabled() && modelComboModel.hasSelectableModels();
		modelCombo.setEnabled(enableCombo);
	}

	private void updateButtonState() {
		sendButton.setText(requestInProgress ? "Stop" : "Send");
		resetButtonListeners(requestInProgress ? stopAction : sendAction);
		boolean enabled = promptField.isEnabled();
		if (requestInProgress) {
			enabled = enabled && stopAction != null;
		}
		else {
			enabled = enabled && sendEnabled;
		}
		sendButton.setEnabled(enabled);
	}

	private void resetButtonListeners(ActionListener targetListener) {
		for (ActionListener existing : sendButton.getActionListeners()) {
			sendButton.removeActionListener(existing);
		}
		if (targetListener != null) {
			sendButton.addActionListener(targetListener);
		}
	}

	private static final class ModelComboBoxModel extends javax.swing.AbstractListModel<ModelItem>
			implements javax.swing.ComboBoxModel<ModelItem> {

		private final List<ModelItem> items = new ArrayList<>();
		private ModelItem selectedItem;

		void setEntries(List<ModelEntry> entries, String defaultKey) {
			items.clear();
			String lastGroup = null;
			for (ModelEntry entry : entries) {
				String group = entry.provider().displayName();
				if (!Objects.equals(group, lastGroup)) {
					items.add(ModelItem.group(group));
					lastGroup = group;
				}
				items.add(ModelItem.model(entry));
			}
			if (defaultKey != null) {
				selectByKey(defaultKey);
			}
			else {
				selectFirstModel();
			}
			fireContentsChanged(this, -1, -1);
		}

		@Override
		public int getSize() {
			return items.size();
		}

		@Override
		public ModelItem getElementAt(int index) {
			return items.get(index);
		}

		@Override
		public void setSelectedItem(Object anItem) {
			if (anItem instanceof ModelItem item) {
				if (item.type() == ModelItem.Type.GROUP) {
					return;
				}
				selectedItem = item;
				fireContentsChanged(this, -1, -1);
			}
			else if (anItem instanceof ModelEntry entry) {
				selectByKey(entry.key());
			}
			else if (anItem == null) {
				selectedItem = null;
				fireContentsChanged(this, -1, -1);
			}
		}

		@Override
		public ModelItem getSelectedItem() {
			return selectedItem;
		}

		ModelEntry getSelectedEntry() {
			return selectedItem != null ? selectedItem.entry() : null;
		}

		boolean hasSelectableModels() {
			return items.stream().anyMatch(ModelItem::isSelectable);
		}

		private void selectByKey(String key) {
			if (key == null) {
				selectFirstModel();
				return;
			}
			for (ModelItem item : items) {
				if (item.isSelectable() && key.equals(item.entry().key())) {
					selectedItem = item;
					fireContentsChanged(this, -1, -1);
					return;
				}
			}
			selectFirstModel();
		}

		private void selectFirstModel() {
			for (ModelItem item : items) {
				if (item.isSelectable()) {
					selectedItem = item;
					fireContentsChanged(this, -1, -1);
					return;
				}
			}
			selectedItem = null;
		}
	}

	private static final class ModelItemRenderer extends javax.swing.DefaultListCellRenderer {

		private final Font groupFont;

		ModelItemRenderer() {
			Font baseFont = getFont();
			groupFont = baseFont != null ? baseFont.deriveFont(Font.BOLD) : null;
		}

		@Override
		public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus) {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (!(value instanceof ModelItem item)) {
				setText("");
				return this;
			}
			if (item.type() == ModelItem.Type.GROUP) {
				setText("[" + item.groupLabel() + "]");
				if (groupFont != null) {
					setFont(groupFont);
				}
				setEnabled(false);
			}
			else {
				setEnabled(true);
				ModelEntry entry = item.entry();
				if (index < 0) {
					setText(entry.displayName() + " · " + entry.provider().displayName());
				}
				else {
					setText(entry.displayName());
				}
			}
			return this;
		}
	}

	private record ModelItem(Type type, String groupLabel, ModelEntry entry) {

		enum Type {
			GROUP,
			MODEL
		}

		static ModelItem group(String label) {
			return new ModelItem(Type.GROUP, label, null);
		}

		static ModelItem model(ModelEntry entry) {
			return new ModelItem(Type.MODEL, null, entry);
		}

		static ModelItem prototype() {
			return model(new ModelEntry(ghidracopilot.ai.AiProvider.OPENAI, "placeholder", "Placeholder Model"));
		}

		boolean isSelectable() {
			return type == Type.MODEL;
		}
	}
}
