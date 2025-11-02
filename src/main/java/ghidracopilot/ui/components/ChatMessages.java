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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.messages.AbstractChatMessage;
import ghidracopilot.ui.messages.ChatAlignment;
import ghidracopilot.ui.messages.SystemMessage;
import ghidracopilot.ui.messages.ToolCallMessage;

/**
 * Message transcript area for Copilot chat.
 */
public class ChatMessages extends JPanel {

	private final MessageListPanel messageList;
	private final JScrollPane scrollPane;

	public ChatMessages() {
		super(new BorderLayout());
		setBorder(new EmptyBorder(0, 10, 0, 10));

		messageList = new MessageListPanel();

		scrollPane = new JScrollPane(messageList);
		scrollPane.setBorder(null);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);
	}

	public void appendMessage(AbstractChatMessage message) {
		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setBorder(new EmptyBorder(4, 16, 4, 16));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);

		message.setAlignmentY(Component.TOP_ALIGNMENT);

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
		messageList.add(Box.createVerticalStrut(12));

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

	public SystemMessage addSystemMessage(String markdown) {
		SystemMessage message = new SystemMessage(markdown);
		appendMessage(message);
		return message;
	}

	public ToolCallMessage addToolCallMessage(String toolName, String inputJson) {
		ToolCallMessage message = new ToolCallMessage(toolName, inputJson);
		appendMessage(message);
		return message;
	}

	private static class MessageListPanel extends JPanel implements Scrollable {

		MessageListPanel() {
			super();
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return Math.max(visibleRect.height - 16, 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}
}
