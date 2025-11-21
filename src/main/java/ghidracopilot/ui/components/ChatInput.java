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
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;

import ghidracopilot.model.ModelRegistry.ModelEntry;
import ghidracopilot.ai.InteractionMode;

/**
 * Input area for composing chat requests to Copilot.
 */
public class ChatInput extends JPanel {

	private final JTextField promptField;
	private final JButton sendButton;
	private final JComboBox<ModelItem> modelCombo;
	private final ModelComboBoxModel modelComboModel;
	private final JComboBox<InteractionMode> modeCombo;

	public ChatInput() {
		super(new BorderLayout());
		setBorder(new EmptyBorder(8, 10, 10, 10));

		promptField = new JTextField();
		sendButton = new JButton("Send");
		modelComboModel = new ModelComboBoxModel();
		modelCombo = new JComboBox<>(modelComboModel);
		modelCombo.setRenderer(new ModelItemRenderer());
		modelCombo.setPrototypeDisplayValue(ModelItem.prototype());
		modelCombo.putClientProperty("JComboBox.isTableCellEditor", Boolean.TRUE);

		modeCombo = new JComboBox<>(InteractionMode.values());
		modeCombo.setPrototypeDisplayValue(InteractionMode.AGENT);
		modeCombo.setSelectedItem(InteractionMode.ASK);

		JPanel promptRow = new JPanel(new BorderLayout(8, 0));
		promptRow.add(promptField, BorderLayout.CENTER);
		promptRow.add(sendButton, BorderLayout.EAST);

		JPanel controlsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		controlsRow.add(modelCombo);
		controlsRow.add(modeCombo);
		controlsRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		JPanel stack = new JPanel();
		stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
		stack.setOpaque(false);
		stack.add(promptRow);
		stack.add(Box.createVerticalStrut(0));
		stack.add(controlsRow);

		add(stack, BorderLayout.CENTER);
	}

	public void addSendAction(ActionListener listener) {
		sendButton.addActionListener(listener);
		promptField.addActionListener(listener);
	}

	public String getPromptText() {
		return promptField.getText();
	}

	public InteractionMode getInteractionMode() {
		Object selected = modeCombo.getSelectedItem();
		return selected instanceof InteractionMode mode ? mode : InteractionMode.ASK;
	}

	public ModelEntry getSelectedModel() {
		return modelComboModel.getSelectedEntry();
	}

	public void setModelEntries(List<ModelEntry> entries, String defaultModelKey) {
		modelComboModel.setEntries(entries != null ? entries : List.of(), defaultModelKey);
		updateComboEnabledState();
	}

	public void clearPrompt() {
		promptField.setText("");
	}

	public void setInputEnabled(boolean enabled) {
		promptField.setEnabled(enabled);
		sendButton.setEnabled(enabled);
		modeCombo.setEnabled(enabled);
		updateComboEnabledState();
	}

	public void setSendingEnabled(boolean enabled) {
		sendButton.setEnabled(enabled);
	}

	private void updateComboEnabledState() {
		boolean enableCombo = promptField.isEnabled() && modelComboModel.hasSelectableModels();
		modelCombo.setEnabled(enableCombo);
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
