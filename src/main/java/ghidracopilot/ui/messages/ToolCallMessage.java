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
package ghidracopilot.ui.messages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

/**
 * Message bubble used to display assistant tool invocations.
 */
public class ToolCallMessage extends AbstractChatMessage {

	private static final Color BACKGROUND = new Color(0xE0EEF9);
	private static final Color BORDER = new Color(0x9FC2E5);
	private static final Color TEXT = new Color(0x1C2333);
	private static final Color DETAIL_BACKGROUND = new Color(0xF6FAFF);
	private static final Color DETAIL_BORDER = new Color(0xD2E2F3);
	private static final String TOOL_ICON = "\uD83D\uDD27";

	private final String toolName;
	private final JLabel statusLabel;
	private final JLabel toolLabel;
	private final JButton toggleButton;
	private final JPanel detailPanel;
	private final JTextArea detailArea;

	private ToolCallState state;
	private String inputJson;
	private String outputJson;
	private String errorMessage;

	public ToolCallMessage(String toolName, String inputJson) {
		super("", ChatAlignment.LEFT, BACKGROUND, BORDER, TEXT);
		this.toolName = toolName;
		this.inputJson = inputJson;
		this.outputJson = "";
		this.errorMessage = "";
		this.state = ToolCallState.INVOKED;

		JPanel bubble = getBubblePanel();
		bubble.removeAll();
		bubble.setLayout(new BorderLayout(0, 8));

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setOpaque(false);
		header.setBorder(new EmptyBorder(0, 0, 0, 0));

		toolLabel = new JLabel(TOOL_ICON + " " + toolName);
		toolLabel.setFont(toolLabel.getFont().deriveFont(Font.BOLD));
		header.add(toolLabel, BorderLayout.WEST);

		statusLabel = new JLabel();
		header.add(statusLabel, BorderLayout.CENTER);

		toggleButton = new JButton("Show Details");
		toggleButton.addActionListener(e -> toggleDetailVisibility());
		header.add(toggleButton, BorderLayout.EAST);

		detailArea = new JTextArea();
		detailArea.setEditable(false);
		detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		detailArea.setLineWrap(true);
		detailArea.setWrapStyleWord(true);
		detailArea.setOpaque(true);
		detailArea.setForeground(TEXT);
		detailArea.setBackground(DETAIL_BACKGROUND);
		detailArea.setMargin(new java.awt.Insets(0, 0, 0, 0));
		detailArea.setCaretPosition(0);

		detailPanel = new JPanel(new BorderLayout());
		detailPanel.setOpaque(false);
		detailPanel.setBorder(new EmptyBorder(4, 4, 0, 0));
		JScrollPane detailScroll = new JScrollPane(detailArea);
		detailScroll.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			javax.swing.BorderFactory.createLineBorder(DETAIL_BORDER, 1, true),
			new EmptyBorder(6, 8, 6, 8)));
		detailScroll.getViewport().setBackground(DETAIL_BACKGROUND);
		detailScroll.setOpaque(false);
		detailPanel.add(detailScroll, BorderLayout.CENTER);
		detailPanel.setVisible(false);

		bubble.add(header, BorderLayout.NORTH);
		bubble.add(detailPanel, BorderLayout.CENTER);

		updateStatusLabel();
		updateDetailArea();
	}

	public void setState(ToolCallState newState) {
		if (newState == null) {
			return;
		}
		this.state = newState;
		updateStatusLabel();
	}

	public void setOutputJson(String outputJson) {
		this.outputJson = outputJson != null ? outputJson : "";
		updateDetailArea();
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage != null ? errorMessage : "";
		updateStatusLabel();
		updateDetailArea();
	}

	private void toggleDetailVisibility() {
		boolean newVisible = !detailPanel.isVisible();
		detailPanel.setVisible(newVisible);
		toggleButton.setText(newVisible ? "Hide Details" : "Show Details");
		getBubblePanel().revalidate();
		getBubblePanel().repaint();
	}

	private void updateStatusLabel() {
		StringBuilder sb = new StringBuilder();
		sb.append("State: ").append(state.name().replace('_', ' ').toLowerCase());
		statusLabel.setText(sb.toString());
		statusLabel.setForeground(stateColor(state));

		if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
			statusLabel.setText(sb.append(" • ").append(errorMessage).toString());
		}
	}

	private void updateDetailArea() {
		StringBuilder sb = new StringBuilder();
		sb.append("Input:\n");
		sb.append(inputJson == null || inputJson.isEmpty() ? "(none)" : inputJson);

		if (!outputJson.isEmpty()) {
			sb.append("\n\nOutput:\n");
			sb.append(outputJson);
		}

		if (state == ToolCallState.FAILED && !errorMessage.isEmpty()) {
			sb.append("\n\nError:\n").append(errorMessage);
		}

		detailArea.setText(sb.toString());
	}

	private Color stateColor(ToolCallState state) {
		return switch (state) {
			case INVOKED -> new Color(0x005A8C);
			case IN_PROGRESS -> new Color(0x8C5A00);
			case COMPLETED -> new Color(0x1E7F46);
			case FAILED -> new Color(0xB3261E);
		};
	}
}
