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

import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Assistant response with a {@code ●} dot prefix and inline content.
 * Supports incremental streaming via {@link #appendDelta(String)}.
 */
public class AssistantMessage extends AbstractChatMessage {

	private final StringBuilder accumulated = new StringBuilder();

	public AssistantMessage(String markdown) {
		this(markdown, false);
	}

	/**
	 * @param muted if true, renders with muted colors (for thinking/reasoning)
	 */
	public AssistantMessage(String markdown, boolean muted) {
		super(markdown, muted ? CopilotTheme.thinkingText() : CopilotTheme.assistantText());

		setLayout(new BorderLayout(6, 0));

		Color dotColor = muted ? CopilotTheme.thinkingText() : CopilotTheme.copilotDotColor();
		JLabel dot = new JLabel("\u25CF");
		dot.setForeground(dotColor);
		dot.setFont(dot.getFont().deriveFont(Font.BOLD, dot.getFont().getSize2D()));
		dot.setVerticalAlignment(SwingConstants.TOP);
		dot.setBorder(new EmptyBorder(3, 0, 0, 0));

		add(dot, BorderLayout.WEST);
		add(getContentPanel(), BorderLayout.CENTER);

		if (markdown != null) {
			accumulated.append(markdown);
		}

		if (muted) {
			MarkdownRenderer.applyFontStyle(getContentComponent(), Font.ITALIC);
		}
	}

	/**
	 * Append a streaming text delta and re-render the markdown content.
	 *
	 * @param delta incremental text fragment from the model
	 */
	public void appendDelta(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		accumulated.append(delta);
		setMarkdown(accumulated.toString());
	}

	/**
	 * Returns the full accumulated response text.
	 */
	public String getAccumulatedText() {
		return accumulated.toString();
	}
}
