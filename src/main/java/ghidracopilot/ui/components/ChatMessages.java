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
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.messages.AbstractChatMessage;
import ghidracopilot.ui.messages.SystemMessage;
import ghidracopilot.ui.messages.ThinkingMessage;
import ghidracopilot.ui.messages.ToolCallMessage;

/**
 * Message transcript area for Copilot chat.
 */
public class ChatMessages extends JPanel {

	private final MessageListPanel messageList;
	private final JScrollPane scrollPane;

	public ChatMessages() {
		super(new BorderLayout());
		setOpaque(false);
		setBorder(new EmptyBorder(0, 10, 0, 10));

		messageList = new MessageListPanel();

		scrollPane = new JScrollPane(messageList);
		scrollPane.setBorder(null);
		scrollPane.setOpaque(false);
		scrollPane.getViewport().setOpaque(false);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(scrollPane, BorderLayout.CENTER);
	}

	public void appendMessage(AbstractChatMessage message) {
		int verticalGap = 8;
		boolean wasAtBottom = isNearBottom();

		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(message, BorderLayout.CENTER);

		messageList.add(row);
		messageList.add(Box.createVerticalStrut(verticalGap));

		messageList.revalidate();
		messageList.repaint();

		if (wasAtBottom) {
			scrollToBottom();
		}
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

	public ghidracopilot.ui.messages.AssistantMessage addThinkingContentMessage(String markdown) {
		ghidracopilot.ui.messages.AssistantMessage message =
			new ghidracopilot.ui.messages.AssistantMessage(markdown, true);
		appendMessage(message);
		return message;
	}

	public SystemMessage addSystemMessage(String markdown) {
		SystemMessage message = new SystemMessage(markdown);
		appendMessage(message);
		return message;
	}

	public ToolCallMessage addToolCallMessage(String toolName, String inputJson) {
		return addToolCallMessage(toolName, inputJson, null);
	}

	public ToolCallMessage addToolCallMessage(String toolName, String inputJson,
			String intentionSummary) {
		ToolCallMessage message = new ToolCallMessage(toolName, inputJson, intentionSummary);
		appendMessage(message);
		return message;
	}

	/**
	 * Add an animated thinking indicator to the transcript.
	 */
	public ThinkingMessage addThinkingIndicator() {
		ThinkingMessage indicator = new ThinkingMessage();
		boolean wasAtBottom = isNearBottom();

		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(4, 0, 4, 0));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(indicator, BorderLayout.CENTER);

		messageList.add(row);
		messageList.revalidate();
		messageList.repaint();

		if (wasAtBottom) {
			scrollToBottom();
		}

		return indicator;
	}

	/**
	 * Remove the thinking indicator from the transcript.
	 */
	public void removeThinkingIndicator(ThinkingMessage indicator) {
		if (indicator == null) {
			return;
		}
		indicator.dismiss();
		// Find and remove the row containing this indicator
		for (int i = messageList.getComponentCount() - 1; i >= 0; i--) {
			Component child = messageList.getComponent(i);
			if (child instanceof JPanel row) {
				for (Component inner : row.getComponents()) {
					if (inner == indicator) {
						messageList.remove(i);
						messageList.revalidate();
						messageList.repaint();
						return;
					}
				}
			}
		}
	}

	/**
	 * Returns {@code true} when the viewport is scrolled to within ~50px of the bottom.
	 */
	private boolean isNearBottom() {
		JScrollBar vbar = scrollPane.getVerticalScrollBar();
		int extent = vbar.getModel().getExtent();
		int max = vbar.getMaximum();
		int value = vbar.getValue();
		return value + extent >= max - 50;
	}

	/**
	 * Scrolls to the bottom on the next EDT pass. Public so streaming deltas
	 * can keep the view pinned when the user was already at the bottom.
	 */
	public void scrollToBottom() {
		SwingUtilities.invokeLater(() ->
			scrollPane.getVerticalScrollBar().setValue(
				scrollPane.getVerticalScrollBar().getMaximum()));
	}

	/**
	 * Scrolls to the bottom only if the user is already near the bottom.
	 * Call this when content grows (e.g. streaming deltas) so the view stays
	 * pinned unless the user intentionally scrolled up.
	 */
	public void scrollIfAtBottom() {
		if (isNearBottom()) {
			scrollToBottom();
		}
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
