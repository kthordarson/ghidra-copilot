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

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;

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

	/**
	 * True while the user has a mouse button held down anywhere inside this
	 * transcript (e.g. dragging a text selection). Autoscroll is suppressed
	 * during this window so a streaming response can't yank the viewport out
	 * from under an in-progress selection.
	 *
	 * <p>Registered as a global {@link AWTEventListener} rather than a normal
	 * {@link java.awt.event.MouseListener} because mouse events are delivered
	 * directly to the deepest component under the cursor (a code block's
	 * {@code RSyntaxTextArea}, an html pane, etc.) and do not bubble up to
	 * ancestors on their own.
	 */
	private boolean userInteracting;

	private final AWTEventListener mouseInteractionListener = event -> {
		if (!(event instanceof MouseEvent mouseEvent)) {
			return;
		}
		Component source = mouseEvent.getComponent();
		if (source == null || !SwingUtilities.isDescendingFrom(source, this)) {
			return;
		}
		if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED) {
			userInteracting = true;
		}
		else if (mouseEvent.getID() == MouseEvent.MOUSE_RELEASED) {
			userInteracting = false;
		}
	};

	public ChatMessages() {
		super(new BorderLayout());
		setOpaque(true);
		setBackground(ghidracopilot.ui.CopilotTheme.chatBackground());
		setBorder(new EmptyBorder(0, 10, 0, 10));

		messageList = new MessageListPanel();

		scrollPane = new JScrollPane(messageList);
		scrollPane.setBorder(null);
		scrollPane.setOpaque(true);
		scrollPane.getViewport().setOpaque(true);
		scrollPane.setBackground(ghidracopilot.ui.CopilotTheme.chatBackground());
		scrollPane.getViewport().setBackground(ghidracopilot.ui.CopilotTheme.chatBackground());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);
		scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				refreshMessageLayouts();
			}
		});

		add(scrollPane, BorderLayout.CENTER);
	}

	@Override
	public void addNotify() {
		super.addNotify();
		Toolkit.getDefaultToolkit().addAWTEventListener(mouseInteractionListener, AWTEvent.MOUSE_EVENT_MASK);
	}

	@Override
	public void removeNotify() {
		Toolkit.getDefaultToolkit().removeAWTEventListener(mouseInteractionListener);
		super.removeNotify();
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
		SwingUtilities.invokeLater(this::refreshMessageLayouts);

		maybeAutoScroll(wasAtBottom);
	}

	public void clearMessages() {
		messageList.removeAll();
		messageList.revalidate();
		messageList.repaint();
	}

	public void removeMessage(AbstractChatMessage message) {
		if (message == null) {
			return;
		}
		for (int i = 0; i < messageList.getComponentCount(); i++) {
			Component child = messageList.getComponent(i);
			if (!(child instanceof JPanel row)) {
				continue;
			}
			for (Component inner : row.getComponents()) {
				if (inner != message) {
					continue;
				}
				messageList.remove(i);
				if (i < messageList.getComponentCount() &&
					messageList.getComponent(i) instanceof Box.Filler) {
					messageList.remove(i);
				}
				messageList.revalidate();
				messageList.repaint();
				return;
			}
		}
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

	public ghidracopilot.ui.messages.UndoCheckpointMessage addUndoCheckpoint(
			int mutationCount, java.util.List<String> mutationDescriptions,
			ghidra.program.model.listing.Program program) {
		ghidracopilot.ui.messages.UndoCheckpointMessage checkpoint =
			new ghidracopilot.ui.messages.UndoCheckpointMessage(
				mutationCount, mutationDescriptions, program);
		appendMessage(checkpoint);
		return checkpoint;
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

		maybeAutoScroll(wasAtBottom);

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
		if (!userInteracting && isNearBottom()) {
			scrollToBottom();
		}
	}

	/**
	 * Scrolls to the bottom if {@code wasAtBottom}, unless the user currently
	 * has a mouse button held down inside the transcript (e.g. dragging a
	 * selection), in which case autoscroll is skipped so it can't yank the
	 * viewport out from under them.
	 */
	private void maybeAutoScroll(boolean wasAtBottom) {
		if (wasAtBottom && !userInteracting) {
			scrollToBottom();
		}
	}

	private void refreshMessageLayouts() {
		for (Component rowComponent : messageList.getComponents()) {
			if (rowComponent instanceof JPanel row) {
				for (Component child : row.getComponents()) {
					if (child instanceof AbstractChatMessage message) {
						message.refreshLayout();
					}
				}
			}
		}
		messageList.revalidate();
		messageList.repaint();
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
