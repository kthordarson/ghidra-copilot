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
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import ghidracopilot.ui.CopilotTheme;

/**
 * Assistant response with a {@code ●} dot prefix and inline content.
 * Supports incremental streaming via {@link #appendDelta(String)}.
 *
 * <p>Streamed deltas arrive far faster than a full markdown re-parse and
 * Swing component rebuild can keep up with, so renders are throttled to
 * {@link #RENDER_INTERVAL_MS} rather than performed on every delta.
 */
public class AssistantMessage extends AbstractChatMessage {

	private static final int RENDER_INTERVAL_MS = 60;

	private final StringBuilder accumulated = new StringBuilder();
	private final Timer renderTimer;
	private boolean dirty;

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

		renderTimer = new Timer(RENDER_INTERVAL_MS, e -> {
			if (dirty) {
				dirty = false;
				setMarkdown(accumulated.toString());
			}
			else {
				((Timer) e.getSource()).stop();
			}
		});
		renderTimer.setInitialDelay(0);
	}

	/**
	 * Append a streaming text delta. The visible markdown is re-rendered at
	 * most every {@value #RENDER_INTERVAL_MS}ms rather than on every delta,
	 * since a full re-parse + Swing component rebuild per token can't keep
	 * up with model streaming speed and floods the EDT.
	 *
	 * @param delta incremental text fragment from the model
	 */
	public void appendDelta(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		accumulated.append(delta);
		dirty = true;
		if (!renderTimer.isRunning()) {
			renderTimer.start();
		}
	}

	/**
	 * Forces any pending throttled render to complete immediately. Call this
	 * once streaming for this message has finished so the final content is
	 * never left waiting on the render timer.
	 */
	public void flush() {
		renderTimer.stop();
		if (dirty) {
			dirty = false;
			setMarkdown(accumulated.toString());
		}
	}

	/**
	 * Returns the full accumulated response text.
	 */
	public String getAccumulatedText() {
		return accumulated.toString();
	}
}
