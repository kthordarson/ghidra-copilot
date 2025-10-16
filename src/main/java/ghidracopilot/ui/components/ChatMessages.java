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

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.messages.AbstractChatMessage;
import ghidracopilot.ui.messages.ChatAlignment;
import ghidracopilot.ui.messages.ToolCallMessage;

/**
 * Message transcript area for Copilot chat.
 */
public class ChatMessages extends JPanel {

	private final JPanel messageList;
	private final JScrollPane scrollPane;

	public ChatMessages() {
		super(new BorderLayout());
		setBorder(new EmptyBorder(0, 10, 0, 10));

		messageList = new JPanel();
		messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
		messageList.setOpaque(false);

		scrollPane = new JScrollPane(messageList);
		scrollPane.setBorder(null);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);
	}

	public void appendMessage(AbstractChatMessage message) {
		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));

		if (message.getAlignment() == ChatAlignment.RIGHT) {
			row.add(Box.createHorizontalGlue());
			row.add(message);
		}
		else if (message.getAlignment() == ChatAlignment.CENTER) {
			row.add(Box.createHorizontalGlue());
			row.add(message);
			row.add(Box.createHorizontalGlue());
		}
		else {
			row.add(message);
			row.add(Box.createHorizontalGlue());
		}

		messageList.add(row);
		messageList.add(Box.createVerticalStrut(8));

		messageList.revalidate();
		messageList.repaint();

		SwingUtilities.invokeLater(() -> {
			scrollPane.getVerticalScrollBar().setValue(
				scrollPane.getVerticalScrollBar().getMaximum());
		});
	}

	public void clearMessages() {
		messageList.removeAll();
		messageList.revalidate();
		messageList.repaint();
	}

	public ghidracopilot.ui.messages.UserMessage addUserMessage(String markdown) {
		ghidracopilot.ui.messages.UserMessage message =
			new ghidracopilot.ui.messages.UserMessage(markdown);
		appendMessage(message);
		return message;
	}

	public ghidracopilot.ui.messages.AssistantMessage addAssistantMessage(String markdown) {
		ghidracopilot.ui.messages.AssistantMessage message =
			new ghidracopilot.ui.messages.AssistantMessage(markdown);
		appendMessage(message);
		return message;
	}

	public ghidracopilot.ui.messages.ReasoningMessage addReasoningMessage(String markdown) {
		ghidracopilot.ui.messages.ReasoningMessage message =
			new ghidracopilot.ui.messages.ReasoningMessage(markdown);
		appendMessage(message);
		return message;
	}

	public ToolCallMessage addToolCallMessage(String toolName, String inputJson) {
		ToolCallMessage message = new ToolCallMessage(toolName, inputJson);
		appendMessage(message);
		return message;
	}
}
