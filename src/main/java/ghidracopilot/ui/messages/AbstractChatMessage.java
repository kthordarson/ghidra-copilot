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
import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

/**
 * Base class for chat messages. Subclasses define their own layout
 * and add {@link #getContentPanel()} wherever content should appear.
 */
public abstract class AbstractChatMessage extends JPanel {

	private final Color textColor;
	private final JPanel contentPanel;
	private JComponent contentComponent;

	protected AbstractChatMessage(String markdown, Color textColor) {
		this.textColor = textColor;

		setOpaque(false);
		setAlignmentY(Component.TOP_ALIGNMENT);
		setAlignmentX(Component.LEFT_ALIGNMENT);

		contentPanel = new JPanel(new BorderLayout());
		contentPanel.setOpaque(false);

		contentComponent = MarkdownRenderer.render(markdown, textColor);
		applyTextColor(contentComponent, textColor);
		contentPanel.add(contentComponent, BorderLayout.CENTER);
	}

	public ChatAlignment getAlignment() {
		return ChatAlignment.LEFT;
	}

	protected JPanel getContentPanel() {
		return contentPanel;
	}

	protected JComponent getContentComponent() {
		return contentComponent;
	}

	public void setMarkdown(String markdown) {
		contentPanel.remove(contentComponent);
		contentComponent = MarkdownRenderer.render(markdown, textColor);
		applyTextColor(contentComponent, textColor);
		contentPanel.add(contentComponent, BorderLayout.CENTER);
		MarkdownRenderer.refreshLayout(contentComponent);
		contentPanel.revalidate();
		contentPanel.repaint();
		revalidateUpTree();
		SwingUtilities.invokeLater(this::refreshLayout);
	}

	public void refreshLayout() {
		MarkdownRenderer.refreshLayout(contentComponent);
		contentPanel.revalidate();
		contentPanel.repaint();
		revalidateUpTree();
	}

	/**
	 * Marks this message invalid so the layout hierarchy re-validates on the
	 * next EDT pass. {@link #revalidate()} already propagates up to the
	 * nearest validate root (the scroll pane's viewport) on its own — walking
	 * the ancestor chain here and calling revalidate/repaint at every level
	 * was redundant and, worse, triggered extra top-down layout passes over
	 * the whole transcript on every streaming render tick.
	 */
	protected void revalidateUpTree() {
		revalidate();
		repaint();
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
