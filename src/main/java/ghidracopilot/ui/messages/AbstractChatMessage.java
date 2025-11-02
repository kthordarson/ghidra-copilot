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
import java.awt.Dimension;
import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Base message bubble used for rendering chat messages with common styling.
 */
public abstract class AbstractChatMessage extends JPanel {

	private static final int MAX_CONTENT_WIDTH = 520;

	private final ChatAlignment alignment;
	private final JPanel bubblePanel;
	private final Color textColor;
	private JComponent contentComponent;

	protected AbstractChatMessage(String markdown, ChatAlignment alignment, Color backgroundColor,
			Color borderColor, Color textColor) {
		this(markdown, alignment, backgroundColor, borderColor, textColor, true);
	}

	protected AbstractChatMessage(String markdown, ChatAlignment alignment, Color backgroundColor,
			Color borderColor, Color textColor, boolean bubbleChrome) {

		this.alignment = alignment;
		this.textColor = textColor;

		setLayout(new BorderLayout());
		setOpaque(false);
		setAlignmentY(Component.TOP_ALIGNMENT);
		setAlignmentX(switch (alignment) {
			case LEFT -> Component.LEFT_ALIGNMENT;
			case RIGHT -> Component.RIGHT_ALIGNMENT;
			case CENTER -> Component.CENTER_ALIGNMENT;
		});
		setMaximumSize(new Dimension(MAX_CONTENT_WIDTH, Integer.MAX_VALUE));

		contentComponent = MarkdownRenderer.render(markdown, textColor);
		applyTextColor(contentComponent, textColor);

		if (bubbleChrome) {
			bubblePanel = new SpeechBubblePanel(alignment, backgroundColor, borderColor);
		}
		else {
			bubblePanel = new JPanel(new BorderLayout());
			bubblePanel.setOpaque(false);
			bubblePanel.setBorder(new EmptyBorder(4, 0, 4, 0));
		}
		bubblePanel.add(contentComponent, BorderLayout.CENTER);
		bubblePanel.setMaximumSize(new Dimension(MAX_CONTENT_WIDTH, Integer.MAX_VALUE));

		add(bubblePanel, BorderLayout.CENTER);
	}

	public ChatAlignment getAlignment() {
		return alignment;
	}

	protected JPanel getBubblePanel() {
		return bubblePanel;
	}

	protected JComponent getContentComponent() {
		return contentComponent;
	}

	public void setMarkdown(String markdown) {
		bubblePanel.remove(contentComponent);
		contentComponent = MarkdownRenderer.render(markdown, textColor);
		applyTextColor(contentComponent, textColor);
		bubblePanel.add(contentComponent, BorderLayout.CENTER);
		bubblePanel.revalidate();
		bubblePanel.repaint();
	}

	private void applyTextColor(Component component, Color color) {
		if (color == null || component == null) {
			return;
		}

		if (!(component instanceof RSyntaxTextArea)) {
			component.setForeground(color);
		}

		if (component instanceof Container container) {
			for (Component child : container.getComponents()) {
				applyTextColor(child, color);
			}
		}
	}
}
